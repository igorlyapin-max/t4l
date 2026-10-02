using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Security;
using T4L.Api.Sync;
using Xunit;

namespace T4L.Api.Tests;

[Collection(PostgresSyncTestGroup.Name)]
public sealed class PaletteSyncTests
{
    [Fact]
    public async Task TwoClientsSharePaletteAndTreeColorWithRevisionConflicts()
    {
        var rootConnection = Environment.GetEnvironmentVariable("T4L_TEST_POSTGRES");
        Assert.SkipWhen(string.IsNullOrWhiteSpace(rootConnection), "Set T4L_TEST_POSTGRES to run PostgreSQL sync integration tests.");
        var ct = TestContext.Current.CancellationToken;
        var databaseName = $"t4l_palette_test_{Guid.NewGuid():N}";
        var adminBuilder = new NpgsqlConnectionStringBuilder(rootConnection!) { Database = "postgres" };
        var testBuilder = new NpgsqlConnectionStringBuilder(rootConnection) { Database = databaseName };
        await using var admin = new NpgsqlConnection(adminBuilder.ConnectionString); await admin.OpenAsync(ct);
        await new NpgsqlCommand($"CREATE DATABASE \"{databaseName}\"", admin).ExecuteNonQueryAsync(ct);
        try
        {
            await using var db = new T4LDbContext(new DbContextOptionsBuilder<T4LDbContext>().UseNpgsql(testBuilder.ConnectionString).Options);
            await DatabaseBootstrap.InitializeAsync(db, ct);
            var services = new ServiceCollection(); services.AddLogging(); services.AddSignalR();
            await using var provider = services.BuildServiceProvider();
            var actor = new Actor();
            var sync = new SyncService(db, provider.GetRequiredService<IHubContext<ChangeHub>>(), new WorkspaceAccess(actor), TimeProvider.System, NullLogger<SyncService>.Instance);
            var treeId = Guid.NewGuid(); var categoryId = Guid.NewGuid(); var paletteId = Guid.NewGuid();
            var clientA = Guid.NewGuid(); var clientB = Guid.NewGuid();
            async Task<MutationResult> Push(Guid client, MutationDto mutation) => (await sync.PushAsync(new PushRequest(client, [mutation]), ct)).Results.Single();
            Assert.Equal("applied", (await Push(clientA, Mutation("categoryTree", treeId, 0, new { name = "Activity", role = "standard", sortOrder = 0, archived = false }))).Status);
            Assert.Equal("applied", (await Push(clientA, Mutation("category", categoryId, 0, new { categoryTreeId = treeId, parentId = (Guid?)null, name = "Work", loadType = "light", sortOrder = 0, archived = false }))).Status);
            Assert.Equal("applied", (await Push(clientA, Mutation("treeAppearance", treeId, 0, new { categoryTreeId = treeId, colorHex = "#1976D2" }))).Status);
            var palettePayload = new { name = "Default", categoryColorsJson = JsonSerializer.Serialize(new Dictionary<Guid, string?> { [categoryId] = null }),
                itemOrderJson = JsonSerializer.Serialize(new[] { $"c:{categoryId:D}" }), archived = false };
            Assert.Equal("applied", (await Push(clientA, Mutation("palette", paletteId, 0, palettePayload))).Status);
            var received = await sync.PullAsync(DevelopmentIdentity.WorkspaceId, 0, 100, ct);
            Assert.Contains(received.Changes, x => x.EntityType == "palette" && x.EntityId == paletteId);
            Assert.Contains(received.Changes, x => x.EntityType == "treeAppearance" && x.EntityId == treeId);
            var clientBUpdate = Mutation("palette", paletteId, 1, palettePayload);
            var clientAUpdate = Mutation("palette", paletteId, 1, new { name = "Changed", palettePayload.categoryColorsJson, palettePayload.itemOrderJson, archived = false });
            Assert.Equal("applied", (await Push(clientA, clientAUpdate)).Status);
            Assert.Equal("conflict", (await Push(clientB, clientBUpdate)).Status);
            Assert.Equal("Changed", (await db.Palettes.SingleAsync(x => x.Id == paletteId, ct)).Name);
            var archive = await Push(clientA, Mutation("palette", paletteId, 2, new { name = "Changed", palettePayload.categoryColorsJson, palettePayload.itemOrderJson, archived = true }));
            Assert.Equal("applied", archive.Status);
            Assert.NotNull((await db.Palettes.SingleAsync(x => x.Id == paletteId, ct)).TrashedAt);
            var restore = await Push(clientB, Mutation("palette", paletteId, 3, new { name = "Changed", palettePayload.categoryColorsJson, palettePayload.itemOrderJson, archived = false }));
            Assert.Equal("applied", restore.Status);
            Assert.Null((await db.Palettes.SingleAsync(x => x.Id == paletteId, ct)).TrashedAt);

            var transfer = new WorkspaceTransferService(db, TimeProvider.System, actor);
            var v3 = JsonSerializer.SerializeToElement(new { formatVersion = 3 });
            await Assert.ThrowsAsync<ArgumentException>(() => transfer.ImportAsync(new WorkspaceImportRequest("Old", v3), ct));
            var snapshot = JsonSerializer.SerializeToElement(new {
                formatVersion = 4,
                categoryTrees = await db.CategoryTrees.AsNoTracking().ToArrayAsync(ct),
                categories = await db.Categories.AsNoTracking().ToArrayAsync(ct),
                events = Array.Empty<TimeEventEntity>(),
                tasks = Array.Empty<TaskEntity>(),
                taskComments = Array.Empty<TaskCommentEntity>(),
                plans = Array.Empty<PlanEntity>(),
                budgetAllocations = Array.Empty<BudgetAllocationEntity>(),
                plannedEvents = Array.Empty<PlannedEventEntity>(),
                treeAppearances = await db.TreeAppearances.AsNoTracking().ToArrayAsync(ct),
                palettes = await db.Palettes.AsNoTracking().ToArrayAsync(ct)
            });
            var imported = await transfer.ImportAsync(new WorkspaceImportRequest("Imported palette", snapshot), ct);
            var importedTree = await db.CategoryTrees.SingleAsync(x => x.WorkspaceId == imported.WorkspaceId, ct);
            var importedCategory = await db.Categories.SingleAsync(x => x.WorkspaceId == imported.WorkspaceId, ct);
            var importedPalette = await db.Palettes.SingleAsync(x => x.WorkspaceId == imported.WorkspaceId, ct);
            Assert.Equal(importedTree.Id, (await db.TreeAppearances.SingleAsync(x => x.WorkspaceId == imported.WorkspaceId, ct)).CategoryTreeId);
            Assert.Contains(importedCategory.Id.ToString("D"), importedPalette.CategoryColorsJson);
            Assert.DoesNotContain(categoryId.ToString("D"), importedPalette.CategoryColorsJson);
        }
        finally
        {
            NpgsqlConnection.ClearAllPools();
            await new NpgsqlCommand($"DROP DATABASE IF EXISTS \"{databaseName}\" WITH (FORCE)", admin).ExecuteNonQueryAsync(ct);
        }

        static MutationDto Mutation(string type, Guid id, long revision, object payload) => new(
            Guid.NewGuid(), DevelopmentIdentity.WorkspaceId, type, id, "upsert", revision, JsonSerializer.SerializeToElement(payload));
    }

    private sealed class Actor : ICurrentActor
    {
        public Task<ActorContext> GetAsync(CancellationToken ct) => Task.FromResult(new ActorContext(DevelopmentIdentity.UserId,
            new Dictionary<Guid, MembershipRole> { [DevelopmentIdentity.WorkspaceId] = MembershipRole.Owner }));
    }
}
