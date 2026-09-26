using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260922170000_TaskSortOrder")]
public sealed class TaskSortOrder : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.AddColumn<int>(
            name: "SortOrder",
            table: "Tasks",
            type: "integer",
            nullable: false,
            defaultValue: 0);

        migrationBuilder.Sql("""
            WITH ranked AS (
              SELECT "Id", (ROW_NUMBER() OVER (
                PARTITION BY "WorkspaceId"
                ORDER BY "NextActionDate" NULLS LAST, "NextActionTime" NULLS LAST, LOWER("Title"), "Id"
              ) - 1) * 10 AS value
              FROM "Tasks"
            )
            UPDATE "Tasks" AS task SET "SortOrder" = ranked.value
            FROM ranked WHERE ranked."Id" = task."Id";
            """);

        migrationBuilder.CreateIndex(
            name: "IX_Tasks_WorkspaceId_SortOrder",
            table: "Tasks",
            columns: ["WorkspaceId", "SortOrder"]);

        migrationBuilder.Sql("""
            UPDATE "ChangeFeed" AS change
            SET "PayloadJson" = jsonb_set(change."PayloadJson"::jsonb, '{sortOrder}', to_jsonb(task."SortOrder"))::text
            FROM "Tasks" AS task
            WHERE lower(change."EntityType") = 'task'
              AND change."EntityId" = task."Id"
              AND NOT change."Deleted";
            """);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropIndex(name: "IX_Tasks_WorkspaceId_SortOrder", table: "Tasks");
        migrationBuilder.DropColumn(name: "SortOrder", table: "Tasks");
    }
}
