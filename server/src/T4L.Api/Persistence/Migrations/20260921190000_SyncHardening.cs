using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260921190000_SyncHardening")]
public sealed class SyncHardening : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropPrimaryKey(name: "PK_ProcessedMutations", table: "ProcessedMutations");
        migrationBuilder.DropIndex(name: "IX_ProcessedMutations_ClientId_WorkspaceId", table: "ProcessedMutations");
        migrationBuilder.AddPrimaryKey(
            name: "PK_ProcessedMutations",
            table: "ProcessedMutations",
            columns: ["ClientId", "WorkspaceId", "ClientMutationId"]);

        migrationBuilder.DropIndex(name: "IX_BudgetAllocations_PlanId_CategoryId", table: "BudgetAllocations");
        migrationBuilder.CreateIndex(
            name: "IX_BudgetAllocations_WorkspaceId_PlanId_CategoryId",
            table: "BudgetAllocations",
            columns: ["WorkspaceId", "PlanId", "CategoryId"],
            unique: true);
        migrationBuilder.CreateIndex(name: "IX_Users_ExternalSubject", table: "Users", column: "ExternalSubject", unique: true);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropIndex(name: "IX_Users_ExternalSubject", table: "Users");
        migrationBuilder.DropPrimaryKey(name: "PK_ProcessedMutations", table: "ProcessedMutations");
        migrationBuilder.AddPrimaryKey(name: "PK_ProcessedMutations", table: "ProcessedMutations", column: "ClientMutationId");
        migrationBuilder.CreateIndex(
            name: "IX_ProcessedMutations_ClientId_WorkspaceId",
            table: "ProcessedMutations",
            columns: ["ClientId", "WorkspaceId"]);

        migrationBuilder.DropIndex(name: "IX_BudgetAllocations_WorkspaceId_PlanId_CategoryId", table: "BudgetAllocations");
        migrationBuilder.CreateIndex(
            name: "IX_BudgetAllocations_PlanId_CategoryId",
            table: "BudgetAllocations",
            columns: ["PlanId", "CategoryId"],
            unique: true);
    }
}
