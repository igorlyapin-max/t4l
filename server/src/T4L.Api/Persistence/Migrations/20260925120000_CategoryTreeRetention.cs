using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260925120000_CategoryTreeRetention")]
public sealed class CategoryTreeRetention : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.AddColumn<DateTimeOffset>("TrashedAt", "CategoryTrees", type: "timestamp with time zone", nullable: true);
        migrationBuilder.AddColumn<DateTimeOffset>("PurgedAt", "CategoryTrees", type: "timestamp with time zone", nullable: true);
        migrationBuilder.Sql("UPDATE \"CategoryTrees\" SET \"TrashedAt\" = now() WHERE \"Archived\" AND \"DeletedAt\" IS NULL");
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropColumn("TrashedAt", "CategoryTrees");
        migrationBuilder.DropColumn("PurgedAt", "CategoryTrees");
    }
}
