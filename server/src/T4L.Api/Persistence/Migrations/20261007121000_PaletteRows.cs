using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20261007121000_PaletteRows")]
public sealed class PaletteRows : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("ALTER TABLE \"Palettes\" ADD COLUMN \"RowsJson\" text NOT NULL DEFAULT '[]';");
        migrationBuilder.Sql("""
            UPDATE "Palettes" SET "RowsJson" = COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                    'id', md5("Palettes"."Id"::text || ':' || (item.ordinality - 1)::text),
                    'slots', jsonb_build_array(item.value)) ORDER BY item.ordinality)::text
                FROM jsonb_array_elements_text("Palettes"."ItemOrderJson"::jsonb) WITH ORDINALITY AS item(value, ordinality)
            ), '[]');
            """);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("ALTER TABLE \"Palettes\" DROP COLUMN \"RowsJson\";");
    }
}
