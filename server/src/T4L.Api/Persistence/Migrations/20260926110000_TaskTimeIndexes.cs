using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260926110000_TaskTimeIndexes")]
public sealed class TaskTimeIndexes : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropIndex(
            name: "IX_Events_WorkspaceId_CategoryTreeId_OccurredAt",
            table: "Events");
        migrationBuilder.CreateIndex(
            name: "IX_Events_WorkspaceId_CategoryTreeId_OccurredAt_Id",
            table: "Events",
            columns: new[] { "WorkspaceId", "CategoryTreeId", "OccurredAt", "Id" });
        migrationBuilder.CreateIndex(
            name: "IX_Events_WorkspaceId_TaskId_OccurredAt_Id",
            table: "Events",
            columns: new[] { "WorkspaceId", "TaskId", "OccurredAt", "Id" },
            filter: "\"DeletedAt\" IS NULL AND \"TaskId\" IS NOT NULL");
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropIndex(name: "IX_Events_WorkspaceId_CategoryTreeId_OccurredAt_Id", table: "Events");
        migrationBuilder.DropIndex(name: "IX_Events_WorkspaceId_TaskId_OccurredAt_Id", table: "Events");
        migrationBuilder.CreateIndex(
            name: "IX_Events_WorkspaceId_CategoryTreeId_OccurredAt",
            table: "Events",
            columns: new[] { "WorkspaceId", "CategoryTreeId", "OccurredAt" });
    }
}
