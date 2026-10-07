using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20261007120000_CategoryRetention")]
public sealed class CategoryRetention : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("ALTER TABLE \"Categories\" ADD COLUMN \"TrashedAt\" timestamp with time zone NULL; ALTER TABLE \"Categories\" ADD COLUMN \"PurgedAt\" timestamp with time zone NULL;");
        migrationBuilder.Sql("UPDATE \"Categories\" SET \"TrashedAt\" = now() WHERE \"Archived\" = true AND \"DeletedAt\" IS NULL;");
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("ALTER TABLE \"Categories\" DROP COLUMN \"PurgedAt\"; ALTER TABLE \"Categories\" DROP COLUMN \"TrashedAt\";");
    }
}
