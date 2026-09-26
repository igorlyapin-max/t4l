using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using T4L.Api.Domain;
using T4L.Api.Diagnostics;
using T4L.Api.Persistence;
using T4L.Api.Profiles;
using T4L.Api.Security;
using T4L.Api.Sync;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;
using Xunit;

namespace T4L.Api.Tests;

[Collection(PostgresSyncTestGroup.Name)]
public sealed class SyncHardeningTests
{
    [Fact]
    public void ClientDiagnosticsRejectUnknownEventsAndAttributes()
    {
        var sink = new ClientDiagnosticSink(NullLogger<ClientDiagnosticSink>.Instance);
        var valid = new ClientDiagnosticEvent(DateTimeOffset.UtcNow, "basic", "sync_succeeded",
            new Dictionary<string, string> { ["durationMs"] = "15" }, Guid.NewGuid(), "00.00.00.01");

        sink.Write(valid);
        Assert.Throws<ArgumentException>(() => sink.Write(valid with { EventName = "arbitrary_event" }));
        Assert.Throws<ArgumentException>(() => sink.Write(valid with
        {
            Attributes = new Dictionary<string, string> { ["serverUrl"] = "https://secret.example" }
        }));
    }

    [Fact]
    public void BudgetAllocationIdentityMatchesCrossPlatformContract() => Assert.Equal(
        Guid.Parse("97a22ba9-316f-5ff0-84ba-c5dad9395c8f"),
        DeterministicIds.BudgetAllocation(
            Guid.Parse("018f0000-0000-7000-8000-000000000002"),
            Guid.Parse("01900000-0000-7000-8000-000000000014"),
            Guid.Parse("01900000-0000-7000-8000-000000000013")));

    [Fact]
    public Task RejectedRetryPreservesOriginalOutcome() => WithDatabaseAsync(async (sync, _, ct) =>
    {
        var clientId = Guid.NewGuid();
        var mutation = new MutationDto(Guid.NewGuid(), DevelopmentIdentity.WorkspaceId, "task", Guid.NewGuid(), "upsert", 0,
            JsonSerializer.SerializeToElement(new { title = "", categoryId = Guid.NewGuid(), estimateMinutes = -1 }));

        var first = (await sync.PushAsync(new PushRequest(clientId, [mutation]), ct)).Results.Single();
        var retry = (await sync.PushAsync(new PushRequest(clientId, [mutation]), ct)).Results.Single();

        Assert.Equal("rejected", first.Status);
        Assert.Equal("rejected", retry.Status);
        Assert.False(first.Duplicate);
        Assert.True(retry.Duplicate);
        Assert.Equal(first.ErrorCode, retry.ErrorCode);
    });

    [Fact]
    public Task TwoClientsConvergeBudgetAllocationWithoutUniqueViolation() => WithDatabaseAsync(async (sync, db, ct) =>
    {
        var workspaceId = DevelopmentIdentity.WorkspaceId;
        var treeId = Guid.NewGuid(); var categoryId = Guid.NewGuid(); var planId = Guid.NewGuid();
        var setup = new[]
        {
            Mutation("categoryTree", treeId, new { name = "Activity", role = "primary", sortOrder = 0, archived = false }),
            Mutation("category", categoryId, new { categoryTreeId = treeId, parentId = (Guid?)null, name = "Work", loadType = "focus", sortOrder = 0, archived = false }),
            Mutation("plan", planId, new { name = "Week", startsAt = "2026-09-21T00:00:00Z", endsAt = "2026-09-28T00:00:00Z", zoneId = "Europe/Moscow", archived = false })
        };
        var setupResult = await sync.PushAsync(new PushRequest(Guid.NewGuid(), setup), ct);
        Assert.All(setupResult.Results, x => Assert.Equal("applied", x.Status));

        var firstId = Guid.NewGuid(); var secondId = Guid.NewGuid();
        var first = await sync.PushAsync(new PushRequest(Guid.NewGuid(), [Mutation("budgetAllocation", firstId, new { planId, categoryId, ownMinutes = 30 })]), ct);
        var second = await sync.PushAsync(new PushRequest(Guid.NewGuid(), [Mutation("budgetAllocation", secondId, new { planId, categoryId, ownMinutes = 45 })]), ct);

        Assert.Equal("applied", first.Results.Single().Status);
        Assert.Equal("conflict", second.Results.Single().Status);
        Assert.Equal(first.Results.Single().CanonicalEntityId, second.Results.Single().CanonicalEntityId);
        Assert.Equal(1, await db.BudgetAllocations.CountAsync(ct));

        MutationDto Mutation(string type, Guid id, object payload) => new(
            Guid.NewGuid(), workspaceId, type, id, "upsert", 0, JsonSerializer.SerializeToElement(payload));
    });

    [Fact]
    public Task CategoryTreeCanBeRestoredThenPermanentlyPurged() => WithDatabaseAsync(async (sync, db, ct) =>
    {
        var id = Guid.NewGuid();
        var client = Guid.NewGuid();
        async Task<MutationResult> Push(string operation, long revision, bool archived) =>
            (await sync.PushAsync(new PushRequest(client, [new MutationDto(Guid.NewGuid(), DevelopmentIdentity.WorkspaceId,
                "categoryTree", id, operation, revision,
                JsonSerializer.SerializeToElement(new { name = "Activity", role = "primary", archived, sortOrder = 0 }))]), ct)).Results.Single();

        Assert.Equal("applied", (await Push("upsert", 0, false)).Status);
        Assert.Equal("applied", (await Push("upsert", 1, true)).Status);
        Assert.NotNull((await db.CategoryTrees.SingleAsync(x => x.Id == id, ct)).TrashedAt);
        Assert.Equal("applied", (await Push("upsert", 2, false)).Status);
        Assert.Null((await db.CategoryTrees.SingleAsync(x => x.Id == id, ct)).TrashedAt);
        Assert.Equal("applied", (await Push("upsert", 3, true)).Status);
        Assert.Equal("applied", (await Push("purge", 4, true)).Status);
        Assert.NotNull((await db.CategoryTrees.SingleAsync(x => x.Id == id, ct)).PurgedAt);
        Assert.Equal("category_tree_purged", (await Push("upsert", 5, false)).ErrorCode);
    });

    [Fact]
    public Task ArchivedTreeRejectsNewFactsButPreservesHistoricalTimeEditsAndCategoryState() => WithDatabaseAsync(async (sync, db, ct) =>
    {
        var workspace = DevelopmentIdentity.WorkspaceId;
        var client = Guid.NewGuid(); var tree = Guid.NewGuid(); var category = Guid.NewGuid(); var fact = Guid.NewGuid(); var plan = Guid.NewGuid(); var planned = Guid.NewGuid();
        async Task<MutationResult> Push(string type, Guid id, long revision, object payload) =>
            (await sync.PushAsync(new PushRequest(client, [new MutationDto(Guid.NewGuid(), workspace, type, id,
                "upsert", revision, JsonSerializer.SerializeToElement(payload))]), ct)).Results.Single();
        Assert.Equal("applied", (await Push("categoryTree", tree, 0, new { name = "Activity", role = "primary", archived = false })).Status);
        Assert.Equal("applied", (await Push("category", category, 0, new { categoryTreeId = tree, name = "Work", archived = false })).Status);
        Assert.Equal("applied", (await Push("event", fact, 0, new { categoryTreeId = tree, categoryId = category, occurredAt = "2026-09-22T09:00:00Z" })).Status);
        Assert.Equal("applied", (await Push("plan", plan, 0, new { name = "Day", startsAt = "2026-09-22T00:00:00Z", endsAt = "2026-09-23T00:00:00Z", archived = false })).Status);
        Assert.Equal("applied", (await Push("plannedEvent", planned, 0, new { planId = plan, categoryTreeId = tree, categoryId = category, occurredAt = "2026-09-22T09:00:00Z" })).Status);
        Assert.Equal("applied", (await Push("category", category, 1, new { categoryTreeId = tree, name = "Work", archived = true })).Status);
        Assert.Equal("applied", (await Push("categoryTree", tree, 1, new { name = "Activity", role = "primary", archived = true })).Status);
        Assert.Equal("category_tree_not_found", (await Push("event", Guid.NewGuid(), 0, new { categoryTreeId = tree, categoryId = category, occurredAt = "2026-09-22T10:00:00Z" })).ErrorCode);
        Assert.Equal("applied", (await Push("event", fact, 1, new { categoryTreeId = tree, categoryId = category, occurredAt = "2026-09-22T09:15:00Z" })).Status);
        Assert.Equal("applied", (await Push("plannedEvent", planned, 1, new { planId = plan, categoryTreeId = tree, categoryId = category, occurredAt = "2026-09-22T09:15:00Z" })).Status);
        Assert.Equal("category_tree_not_found", (await Push("plannedEvent", Guid.NewGuid(), 0, new { planId = plan, categoryTreeId = tree, categoryId = category, occurredAt = "2026-09-22T10:00:00Z" })).ErrorCode);
        Assert.Equal("applied", (await Push("categoryTree", tree, 2, new { name = "Activity", role = "primary", archived = false })).Status);
        Assert.True((await db.Categories.SingleAsync(x => x.Id == category, ct)).Archived);
    });

    [Fact]
    public Task TaskClosureGroupCommitsBothOrNeitherAndRetriesIdempotently() => WithDatabaseAsync(async (sync, db, ct) =>
    {
        var workspace = DevelopmentIdentity.WorkspaceId;
        var client = Guid.NewGuid(); var tree = Guid.NewGuid(); var category = Guid.NewGuid(); var task = Guid.NewGuid(); var start = Guid.NewGuid();
        async Task<MutationResult> Push(string type, Guid id, long revision, object payload) =>
            (await sync.PushAsync(new PushRequest(client, [new MutationDto(Guid.NewGuid(), workspace, type, id,
                "upsert", revision, JsonSerializer.SerializeToElement(payload))]), ct)).Results.Single();
        Assert.Equal("applied", (await Push("categoryTree", tree, 0, new { name = "Activity", role = "primary", archived = false })).Status);
        Assert.Equal("applied", (await Push("category", category, 0, new { categoryTreeId = tree, name = "Work", archived = false })).Status);
        Assert.Equal("applied", (await Push("task", task, 0, new { title = "Task", categoryId = category, status = "active", nextActionDate = "2026-09-26", estimateMinutes = 30, sortOrder = 0 })).Status);
        Assert.Equal("applied", (await Push("event", start, 0, new { categoryTreeId = tree, categoryId = category, taskId = task, occurredAt = "2026-09-22T09:00:00Z" })).Status);

        var groupId = Guid.NewGuid(); var closeId = Guid.NewGuid();
        var taskMutation = new MutationDto(Guid.NewGuid(), workspace, "task", task, "upsert", 1,
            JsonSerializer.SerializeToElement(new { title = "Task", categoryId = category, status = "paused", estimateMinutes = 30, sortOrder = 0 }), groupId);
        var invalidClose = new MutationDto(Guid.NewGuid(), workspace, "event", closeId, "upsert", 0,
            JsonSerializer.SerializeToElement(new { categoryTreeId = tree, categoryId = category, taskId = task, occurredAt = "2026-09-22T10:00:00Z" }), groupId);
        var rejected = await sync.PushAsync(new PushRequest(client, [taskMutation, invalidClose]), ct);
        Assert.All(rejected.Results, x => Assert.Equal("rejected", x.Status));
        Assert.Equal(TaskState.Active, (await db.Tasks.SingleAsync(x => x.Id == task, ct)).Status);
        Assert.False(await db.Events.AnyAsync(x => x.Id == closeId, ct));
        Assert.Equal("atomic_group_required", (await Push("task", task, 1, new { title = "Task", categoryId = category, status = "paused", estimateMinutes = 30, sortOrder = 0 })).ErrorCode);

        var close = invalidClose with { Payload = JsonSerializer.SerializeToElement(new { categoryTreeId = tree, categoryId = category, taskId = (Guid?)null, occurredAt = "2026-09-22T10:00:00Z" }) };
        var accepted = await sync.PushAsync(new PushRequest(client, [close, taskMutation]), ct);
        Assert.All(accepted.Results, x => Assert.Equal("applied", x.Status));
        Assert.Equal(TaskState.Paused, (await db.Tasks.SingleAsync(x => x.Id == task, ct)).Status);
        Assert.True(await db.Events.AnyAsync(x => x.Id == closeId, ct));
        var repeated = await sync.PushAsync(new PushRequest(client, [taskMutation, close]), ct);
        Assert.All(repeated.Results, x => { Assert.Equal("applied", x.Status); Assert.True(x.Duplicate); });
    });

    [Fact]
    public Task PullRepairsMalformedHistoricalPlanPeriodFromCanonicalPlan() => WithDatabaseAsync(async (sync, db, ct) =>
    {
        var id = Guid.NewGuid();
        var result = await sync.PushAsync(new PushRequest(Guid.NewGuid(), [new MutationDto(Guid.NewGuid(),
            DevelopmentIdentity.WorkspaceId, "plan", id, "upsert", 0,
            JsonSerializer.SerializeToElement(new { name = "Week", startsAt = "2026-09-21T00:00:00Z", endsAt = "2026-09-28T00:00:00Z", zoneId = "UTC", archived = false }))]), ct);
        Assert.Equal("applied", result.Results.Single().Status);
        var feed = await db.ChangeFeed.SingleAsync(x => x.EntityId == id, ct);
        feed.PayloadJson = "{\"name\":\"Week\",\"startsAt\":\"bad\",\"endsAt\":null}";
        await db.SaveChangesAsync(ct);

        var page = await sync.PullAsync(DevelopmentIdentity.WorkspaceId, 0, 100, ct);
        var plan = Assert.Single(page.Changes, x => x.EntityId == id);
        Assert.Equal("2026-09-21T00:00:00.0000000+00:00", plan.Payload.GetProperty("startsAt").GetString());
        Assert.Equal("2026-09-28T00:00:00.0000000+00:00", plan.Payload.GetProperty("endsAt").GetString());
    });

    [Fact]
    public Task DeletingArchivedPlanRemovesPlanningChildrenButKeepsFacts() => WithDatabaseAsync(async (sync, db, ct) =>
    {
        var workspace = DevelopmentIdentity.WorkspaceId;
        var treeId = Guid.NewGuid(); var categoryId = Guid.NewGuid(); var planId = Guid.NewGuid();
        var factId = Guid.NewGuid(); var plannedId = Guid.NewGuid();
        var client = Guid.NewGuid();
        async Task<MutationResult> Push(string type, Guid id, string operation, long revision, object payload) =>
            (await sync.PushAsync(new PushRequest(client, [new MutationDto(Guid.NewGuid(), workspace, type, id,
                operation, revision, JsonSerializer.SerializeToElement(payload))]), ct)).Results.Single();

        Assert.Equal("applied", (await Push("categoryTree", treeId, "upsert", 0, new { name = "Activity", role = "primary", archived = false })).Status);
        Assert.Equal("applied", (await Push("category", categoryId, "upsert", 0, new { categoryTreeId = treeId, name = "Work", loadType = "focus", archived = false })).Status);
        var activePlan = new { name = "Week", startsAt = "2026-09-21T00:00:00Z", endsAt = "2026-09-28T00:00:00Z", zoneId = "UTC", archived = false };
        Assert.Equal("applied", (await Push("plan", planId, "upsert", 0, activePlan)).Status);
        Assert.Equal("applied", (await Push("budgetAllocation", Guid.NewGuid(), "upsert", 0, new { planId, categoryId, ownMinutes = 30 })).Status);
        Assert.Equal("applied", (await Push("plannedEvent", plannedId, "upsert", 0, new { planId, categoryTreeId = treeId, categoryId, occurredAt = "2026-09-22T09:00:00Z" })).Status);
        Assert.Equal("applied", (await Push("event", factId, "upsert", 0, new { categoryTreeId = treeId, categoryId, occurredAt = "2026-09-22T09:00:00Z", zoneId = "UTC", source = "manual" })).Status);
        Assert.Equal("applied", (await Push("plan", planId, "upsert", 1, new { activePlan.name, activePlan.startsAt, activePlan.endsAt, activePlan.zoneId, archived = true })).Status);
        Assert.Equal("applied", (await Push("plan", planId, "delete", 2, new { })).Status);

        Assert.NotNull((await db.Plans.SingleAsync(x => x.Id == planId, ct)).DeletedAt);
        Assert.All(await db.BudgetAllocations.Where(x => x.PlanId == planId).ToArrayAsync(ct), x => Assert.NotNull(x.DeletedAt));
        Assert.All(await db.PlannedEvents.Where(x => x.PlanId == planId).ToArrayAsync(ct), x => Assert.NotNull(x.DeletedAt));
        Assert.Null((await db.Events.SingleAsync(x => x.Id == factId, ct)).DeletedAt);
    });

    [Fact]
    public Task ProfileMutationIsIdempotentAndDetectsStaleRevision() => WithDatabaseAsync(async (_, db, ct) =>
    {
        var profiles = new UserProfileService(db, new TestActor(), TimeProvider.System);
        var mutationId = Guid.NewGuid();
        var request = new UserProfilePatchRequest(mutationId, 0, ["birthDate", "lifeExpectancyYears"], new DateOnly(1990, 5, 20), 78.6m);
        var first = await profiles.PatchAsync(request, ct);
        var duplicate = await profiles.PatchAsync(request, ct);
        var conflict = await profiles.PatchAsync(request with { ClientMutationId = Guid.NewGuid(), BirthDate = new DateOnly(1991, 1, 1) }, ct);

        Assert.False(first.Conflict);
        Assert.False(duplicate.Conflict);
        Assert.Equal(1, duplicate.Profile.Revision);
        Assert.True(conflict.Conflict);
        Assert.Equal(new DateOnly(1990, 5, 20), conflict.Profile.BirthDate);
    });

    [Fact]
    public Task AvatarMutationValidatesContentAndSupportsDelete() => WithDatabaseAsync(async (_, db, ct) =>
    {
        var profiles = new UserProfileService(db, new TestActor(), TimeProvider.System);
        using var source = new Image<Rgba32>(32, 32, Color.CornflowerBlue);
        using var stream = new MemoryStream();
        await source.SaveAsPngAsync(stream, ct);
        var png = stream.ToArray();
        var put = await profiles.PutAvatarAsync(Guid.NewGuid(), 0, "image/png", png, ct);
        var loaded = await profiles.GetAvatarAsync(ct);
        var deleted = await profiles.DeleteAvatarAsync(Guid.NewGuid(), put.Result.AvatarRevision, ct);

        Assert.False(put.Conflict);
        Assert.True(loaded.Found);
        Assert.Equal("image/jpeg", loaded.ContentType);
        Assert.NotEmpty(loaded.Content!);
        Assert.False(deleted.Conflict);
        Assert.False(deleted.Result.HasAvatar);
        await Assert.ThrowsAsync<ArgumentException>(() => profiles.PutAvatarAsync(Guid.NewGuid(), deleted.Result.AvatarRevision, "image/jpeg", [0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0], ct));
    });

    [Fact]
    public Task ProfileAndAvatarRevisionsAdvanceIndependently() => WithDatabaseAsync(async (_, db, ct) =>
    {
        var profiles = new UserProfileService(db, new TestActor(), TimeProvider.System);
        await profiles.GetAsync(ct);
        using var source = new Image<Rgba32>(8, 8, Color.White);
        using var stream = new MemoryStream();
        await source.SaveAsJpegAsync(stream, ct);

        var avatar = await profiles.PutAvatarAsync(Guid.NewGuid(), 0, "image/jpeg", stream.ToArray(), ct);
        var profile = await profiles.PatchAsync(new UserProfilePatchRequest(
            Guid.NewGuid(), 0, ["birthDate"], new DateOnly(1990, 5, 20), null), ct);

        Assert.False(avatar.Conflict);
        Assert.False(profile.Conflict);
        Assert.Equal(1, profile.Profile.Revision);
        Assert.Equal(1, profile.Profile.AvatarRevision);
    });

    [Fact]
    public Task ConcurrentProfileAndAvatarMutationsDoNotConflict() => WithDatabaseAsync(async (_, db, ct) =>
    {
        var options = new DbContextOptionsBuilder<T4LDbContext>()
            .UseNpgsql(db.Database.GetConnectionString()).Options;
        await new UserProfileService(db, new TestActor(), TimeProvider.System).GetAsync(ct);
        await using var profileDb = new T4LDbContext(options);
        await using var avatarDb = new T4LDbContext(options);
        using var source = new Image<Rgba32>(8, 8, Color.White);
        using var stream = new MemoryStream();
        await source.SaveAsJpegAsync(stream, ct);

        var profileTask = new UserProfileService(profileDb, new TestActor(), TimeProvider.System).PatchAsync(
            new UserProfilePatchRequest(Guid.NewGuid(), 0, ["lifeExpectancyYears"], null, 80m), ct);
        var avatarTask = new UserProfileService(avatarDb, new TestActor(), TimeProvider.System).PutAvatarAsync(
            Guid.NewGuid(), 0, "image/jpeg", stream.ToArray(), ct);
        await Task.WhenAll(profileTask, avatarTask);

        Assert.False((await profileTask).Conflict);
        Assert.False((await avatarTask).Conflict);
    });

    private static async Task WithDatabaseAsync(Func<SyncService, T4LDbContext, CancellationToken, Task> test)
    {
        var rootConnectionString = Environment.GetEnvironmentVariable("T4L_TEST_POSTGRES");
        Assert.SkipWhen(string.IsNullOrWhiteSpace(rootConnectionString), "Set T4L_TEST_POSTGRES to run PostgreSQL sync integration tests.");
        var ct = TestContext.Current.CancellationToken;
        var databaseName = $"t4l_sync_hardening_{Guid.NewGuid():N}";
        var adminBuilder = new NpgsqlConnectionStringBuilder(rootConnectionString!) { Database = "postgres" };
        var testBuilder = new NpgsqlConnectionStringBuilder(rootConnectionString) { Database = databaseName };
        await using var admin = new NpgsqlConnection(adminBuilder.ConnectionString); await admin.OpenAsync(ct);
        await new NpgsqlCommand($"CREATE DATABASE \"{databaseName}\"", admin).ExecuteNonQueryAsync(ct);
        try
        {
            var options = new DbContextOptionsBuilder<T4LDbContext>().UseNpgsql(testBuilder.ConnectionString).Options;
            await using var db = new T4LDbContext(options); await DatabaseBootstrap.InitializeAsync(db, ct);
            var services = new ServiceCollection(); services.AddLogging(); services.AddSignalR();
            await using var provider = services.BuildServiceProvider();
            var actor = new TestActor();
            var sync = new SyncService(db, provider.GetRequiredService<IHubContext<ChangeHub>>(), new WorkspaceAccess(actor), TimeProvider.System, NullLogger<SyncService>.Instance);
            await test(sync, db, ct);
        }
        finally
        {
            NpgsqlConnection.ClearAllPools();
            await new NpgsqlCommand($"DROP DATABASE IF EXISTS \"{databaseName}\" WITH (FORCE)", admin).ExecuteNonQueryAsync(ct);
        }
    }

    private sealed class TestActor : ICurrentActor
    {
        public Task<ActorContext> GetAsync(CancellationToken cancellationToken) => Task.FromResult(
            new ActorContext(DevelopmentIdentity.UserId,
                new Dictionary<Guid, MembershipRole> { [DevelopmentIdentity.WorkspaceId] = MembershipRole.Owner }));
    }
}
