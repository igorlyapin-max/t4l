using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Sync;
using T4L.Api.Security;
using Xunit;

namespace T4L.Api.Tests;

[Collection(PostgresSyncTestGroup.Name)]
public sealed class OverlappingPlanSyncTests
{
    [Fact]
    public async Task TwoClientsCanCreatePlansForTheSamePeriod()
    {
        var rootConnectionString = Environment.GetEnvironmentVariable("T4L_TEST_POSTGRES");
        Assert.SkipWhen(string.IsNullOrWhiteSpace(rootConnectionString), "Set T4L_TEST_POSTGRES to run PostgreSQL sync integration tests.");
        var cancellationToken = TestContext.Current.CancellationToken;
        var databaseName = $"t4l_plan_test_{Guid.NewGuid():N}";
        var adminBuilder = new NpgsqlConnectionStringBuilder(rootConnectionString!) { Database = "postgres" };
        var testBuilder = new NpgsqlConnectionStringBuilder(rootConnectionString) { Database = databaseName };
        await using var admin = new NpgsqlConnection(adminBuilder.ConnectionString); await admin.OpenAsync(cancellationToken);
        await new NpgsqlCommand($"CREATE DATABASE \"{databaseName}\"", admin).ExecuteNonQueryAsync(cancellationToken);
        try
        {
            var options = new DbContextOptionsBuilder<T4LDbContext>().UseNpgsql(testBuilder.ConnectionString).Options;
            await using var db = new T4LDbContext(options); await DatabaseBootstrap.InitializeAsync(db, cancellationToken);
            var services = new ServiceCollection(); services.AddLogging(); services.AddSignalR();
            await using var provider = services.BuildServiceProvider();
            var actor = new TestActor();
            var sync = new SyncService(db, provider.GetRequiredService<IHubContext<ChangeHub>>(), new WorkspaceAccess(actor), TimeProvider.System, NullLogger<SyncService>.Instance);
            var first = Mutation(Guid.NewGuid(), "Day timeline", "timeline");
            var second = Mutation(Guid.NewGuid(), "Week budget", "budget");
            var clientId = Guid.NewGuid();

            var firstResult = await sync.PushAsync(new PushRequest(clientId, [first]), cancellationToken);
            var secondResult = await sync.PushAsync(new PushRequest(clientId, [second]), cancellationToken);

            Assert.Equal("applied", firstResult.Results.Single().Status);
            Assert.Equal("applied", secondResult.Results.Single().Status);
            Assert.Equal(2, await db.Plans.CountAsync(cancellationToken));
            var repeated = await sync.PushAsync(new PushRequest(clientId, [second]), cancellationToken);
            Assert.Equal("applied", repeated.Results.Single().Status);
            Assert.True(repeated.Results.Single().Duplicate);
        }
        finally
        {
            NpgsqlConnection.ClearAllPools();
            await new NpgsqlCommand($"DROP DATABASE IF EXISTS \"{databaseName}\" WITH (FORCE)", admin).ExecuteNonQueryAsync(cancellationToken);
        }

        static MutationDto Mutation(Guid id, string name, string kind) => new(
            Guid.NewGuid(), DevelopmentIdentity.WorkspaceId, "plan", id, "upsert", 0,
            JsonSerializer.SerializeToElement(new { name, kind, startsAt = "2199-09-21T05:00:00Z", endsAt = "2199-09-21T17:00:00Z", zoneId = "Europe/Moscow", archived = false }));
    }

    private sealed class TestActor : ICurrentActor
    {
        public Task<ActorContext> GetAsync(CancellationToken cancellationToken) => Task.FromResult(
            new ActorContext(DevelopmentIdentity.UserId,
                new Dictionary<Guid, MembershipRole> { [DevelopmentIdentity.WorkspaceId] = MembershipRole.Owner }));
    }
}
