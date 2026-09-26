using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260923100000_UnifiedPlansAndTaskTime")]
public sealed class UnifiedPlansAndTaskTime : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropColumn(name: "Kind", table: "Plans");
        migrationBuilder.DropColumn(name: "RemainingEstimateMinutes", table: "Tasks");
        migrationBuilder.Sql("""
            UPDATE "ChangeFeed" SET "PayloadJson" =
                CASE WHEN lower("EntityType") = 'plan'
                    THEN ("PayloadJson"::jsonb - 'kind')::text
                    ELSE ("PayloadJson"::jsonb - 'remainingEstimateMinutes')::text END
            WHERE NOT "Deleted" AND lower("EntityType") IN ('plan', 'task');
            """);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.AddColumn<int>(name: "Kind", table: "Plans", type: "integer", nullable: false, defaultValue: 0);
        migrationBuilder.AddColumn<int>(name: "RemainingEstimateMinutes", table: "Tasks", type: "integer", nullable: false, defaultValue: 0);
    }
}
