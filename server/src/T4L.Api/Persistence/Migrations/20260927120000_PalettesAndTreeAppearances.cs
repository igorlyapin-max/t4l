using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260927120000_PalettesAndTreeAppearances")]
public sealed class PalettesAndTreeAppearances : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("""
            CREATE TABLE "TreeAppearances" (
                "Id" uuid NOT NULL PRIMARY KEY,
                "WorkspaceId" uuid NOT NULL,
                "Revision" bigint NOT NULL,
                "CreatedAt" timestamp with time zone NOT NULL,
                "UpdatedAt" timestamp with time zone NOT NULL,
                "DeletedAt" timestamp with time zone NULL,
                "CategoryTreeId" uuid NOT NULL,
                "ColorHex" text NOT NULL
            );
            CREATE INDEX "IX_TreeAppearances_WorkspaceId_UpdatedAt" ON "TreeAppearances" ("WorkspaceId", "UpdatedAt");
            CREATE TABLE "Palettes" (
                "Id" uuid NOT NULL PRIMARY KEY,
                "WorkspaceId" uuid NOT NULL,
                "Revision" bigint NOT NULL,
                "CreatedAt" timestamp with time zone NOT NULL,
                "UpdatedAt" timestamp with time zone NOT NULL,
                "DeletedAt" timestamp with time zone NULL,
                "Name" text NOT NULL,
                "CategoryColorsJson" text NOT NULL,
                "ItemOrderJson" text NOT NULL,
                "Archived" boolean NOT NULL,
                "TrashedAt" timestamp with time zone NULL,
                "PurgedAt" timestamp with time zone NULL
            );
            CREATE INDEX "IX_Palettes_WorkspaceId_UpdatedAt" ON "Palettes" ("WorkspaceId", "UpdatedAt");
            """);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("DROP TABLE \"Palettes\"; DROP TABLE \"TreeAppearances\";");
    }
}
