using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260921210000_UserProfile")]
public sealed class UserProfile : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.CreateTable(
            name: "UserProfiles",
            columns: table => new
            {
                UserId = table.Column<Guid>(type: "uuid", nullable: false),
                BirthDate = table.Column<DateOnly>(type: "date", nullable: true),
                LifeExpectancyYears = table.Column<decimal>(type: "numeric(5,2)", precision: 5, scale: 2, nullable: true),
                Revision = table.Column<long>(type: "bigint", nullable: false),
                AvatarRevision = table.Column<long>(type: "bigint", nullable: false),
                AvatarContent = table.Column<byte[]>(type: "bytea", nullable: true),
                AvatarContentType = table.Column<string>(type: "text", nullable: true),
                UpdatedAt = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("PK_UserProfiles", x => x.UserId);
                table.ForeignKey("FK_UserProfiles_Users_UserId", x => x.UserId, "Users", "Id", onDelete: ReferentialAction.Cascade);
            });

        migrationBuilder.CreateTable(
            name: "ProcessedProfileMutations",
            columns: table => new
            {
                UserId = table.Column<Guid>(type: "uuid", nullable: false),
                ClientMutationId = table.Column<Guid>(type: "uuid", nullable: false),
                Kind = table.Column<string>(type: "text", nullable: false),
                ResultRevision = table.Column<long>(type: "bigint", nullable: false),
                ProcessedAt = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
            },
            constraints: table =>
            {
                table.PrimaryKey("PK_ProcessedProfileMutations", x => new { x.UserId, x.ClientMutationId, x.Kind });
                table.ForeignKey("FK_ProcessedProfileMutations_Users_UserId", x => x.UserId, "Users", "Id", onDelete: ReferentialAction.Cascade);
            });
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropTable("ProcessedProfileMutations");
        migrationBuilder.DropTable("UserProfiles");
    }
}
