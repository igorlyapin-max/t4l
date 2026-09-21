using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace T4L.Api.Persistence.Migrations;

[DbContext(typeof(T4LDbContext))]
[Migration("20260921150000_GrowthModel")]
public sealed class GrowthModel : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("""
            ALTER TABLE "Timelines" RENAME TO "CategoryTrees";
            ALTER TABLE "Categories" RENAME COLUMN "TimelineId" TO "CategoryTreeId";
            ALTER TABLE "Events" RENAME COLUMN "TimelineId" TO "CategoryTreeId";
            ALTER TABLE "Events" ADD COLUMN "TaskId" uuid NULL;
            DROP INDEX IF EXISTS "IX_Events_WorkspaceId_TimelineId_OccurredAt";
            CREATE INDEX "IX_Events_WorkspaceId_CategoryTreeId_OccurredAt" ON "Events" ("WorkspaceId", "CategoryTreeId", "OccurredAt");
            DROP INDEX IF EXISTS "IX_Categories_WorkspaceId_TimelineId_ParentId";
            CREATE INDEX "IX_Categories_WorkspaceId_CategoryTreeId_ParentId" ON "Categories" ("WorkspaceId", "CategoryTreeId", "ParentId");

            ALTER TABLE "Tasks" ADD COLUMN "Title" text NOT NULL DEFAULT '';
            UPDATE "Tasks" t SET "Title" = COALESCE(c."Name", 'Task') FROM "Categories" c WHERE c."Id" = t."CategoryId";
            ALTER TABLE "Tasks" ALTER COLUMN "CategoryId" DROP NOT NULL;
            ALTER TABLE "Tasks" ADD COLUMN "ParentTaskId" uuid NULL;
            ALTER TABLE "Tasks" RENAME COLUMN "InitialEstimateMinutes" TO "EstimateMinutes";
            ALTER TABLE "Tasks" DROP COLUMN "BudgetMinutes";
            ALTER TABLE "Tasks" ADD COLUMN "NextActionDate" date NULL;
            ALTER TABLE "Tasks" ADD COLUMN "NextActionTime" time without time zone NULL;
            ALTER TABLE "Tasks" ADD COLUMN "ZoneId" text NOT NULL DEFAULT 'UTC';
            UPDATE "Tasks" SET "NextActionDate" = CURRENT_DATE WHERE "Status" IN (0, 1);
            UPDATE "Tasks" SET "Status" = 0 WHERE "Status" IN (0, 1);
            DROP INDEX IF EXISTS "IX_Tasks_CategoryId";
            CREATE INDEX "IX_Tasks_WorkspaceId_CategoryId" ON "Tasks" ("WorkspaceId", "CategoryId");
            CREATE INDEX "IX_Tasks_ParentTaskId" ON "Tasks" ("ParentTaskId");

            ALTER TABLE "DailyPlans" RENAME TO "Plans";
            ALTER TABLE "Plans" ADD COLUMN "Name" text NOT NULL DEFAULT 'Migrated plan';
            ALTER TABLE "Plans" ADD COLUMN "Kind" integer NOT NULL DEFAULT 1;
            ALTER TABLE "Plans" ADD COLUMN "StartsAt" timestamp with time zone NULL;
            ALTER TABLE "Plans" ADD COLUMN "EndsAt" timestamp with time zone NULL;
            ALTER TABLE "Plans" ADD COLUMN "Archived" boolean NOT NULL DEFAULT false;
            UPDATE "Plans" p SET
              "StartsAt" = COALESCE((SELECT MIN(w."StartsAt") FROM "AvailabilityWindows" w WHERE w."DailyPlanId" = p."Id"), p."Date"::timestamp AT TIME ZONE 'UTC'),
              "EndsAt" = COALESCE((SELECT MAX(w."EndsAt") FROM "AvailabilityWindows" w WHERE w."DailyPlanId" = p."Id"), (p."Date" + 1)::timestamp AT TIME ZONE 'UTC'),
              "Name" = 'Plan ' || p."Date"::text;
            ALTER TABLE "Plans" ALTER COLUMN "StartsAt" SET NOT NULL;
            ALTER TABLE "Plans" ALTER COLUMN "EndsAt" SET NOT NULL;
            ALTER TABLE "Plans" DROP COLUMN "Date";
            ALTER TABLE "Plans" DROP COLUMN "LoadFactor";
            DROP INDEX IF EXISTS "IX_DailyPlans_WorkspaceId_Date_ZoneId";
            DROP INDEX IF EXISTS "IX_DailyPlans_WorkspaceId_UpdatedAt";
            CREATE INDEX "IX_Plans_WorkspaceId_StartsAt_EndsAt" ON "Plans" ("WorkspaceId", "StartsAt", "EndsAt");
            CREATE INDEX "IX_Plans_WorkspaceId_UpdatedAt" ON "Plans" ("WorkspaceId", "UpdatedAt");

            ALTER TABLE "PlannedBlocks" RENAME TO "PlannedEvents";
            ALTER TABLE "PlannedEvents" RENAME COLUMN "DailyPlanId" TO "PlanId";
            ALTER TABLE "PlannedEvents" ADD COLUMN "CategoryTreeId" uuid NULL;
            ALTER TABLE "PlannedEvents" ADD COLUMN "OccurredAt" timestamp with time zone NULL;
            ALTER TABLE "PlannedEvents" ADD COLUMN "Note" text NULL;
            UPDATE "PlannedEvents" p SET "OccurredAt" = p."StartsAt", "CategoryTreeId" = c."CategoryTreeId" FROM "Categories" c WHERE c."Id" = p."CategoryId";
            DELETE FROM "PlannedEvents" WHERE "CategoryTreeId" IS NULL;
            ALTER TABLE "PlannedEvents" ALTER COLUMN "CategoryTreeId" SET NOT NULL;
            ALTER TABLE "PlannedEvents" ALTER COLUMN "OccurredAt" SET NOT NULL;
            ALTER TABLE "PlannedEvents" DROP COLUMN "StartsAt";
            ALTER TABLE "PlannedEvents" DROP COLUMN "EndsAt";
            ALTER TABLE "PlannedEvents" DROP COLUMN "Confirmed";
            DROP INDEX IF EXISTS "IX_PlannedBlocks_WorkspaceId_UpdatedAt";
            CREATE INDEX "IX_PlannedEvents_WorkspaceId_UpdatedAt" ON "PlannedEvents" ("WorkspaceId", "UpdatedAt");
            CREATE INDEX "IX_PlannedEvents_PlanId_CategoryTreeId_OccurredAt" ON "PlannedEvents" ("PlanId", "CategoryTreeId", "OccurredAt");
            DROP TABLE "AvailabilityWindows";

            CREATE TABLE "TaskComments" (
              "Id" uuid NOT NULL PRIMARY KEY, "TaskId" uuid NOT NULL, "AuthorId" uuid NOT NULL, "Text" text NOT NULL,
              "WorkspaceId" uuid NOT NULL, "Revision" bigint NOT NULL, "CreatedAt" timestamp with time zone NOT NULL,
              "UpdatedAt" timestamp with time zone NOT NULL, "DeletedAt" timestamp with time zone NULL);
            CREATE INDEX "IX_TaskComments_WorkspaceId_TaskId_CreatedAt" ON "TaskComments" ("WorkspaceId", "TaskId", "CreatedAt");
            CREATE INDEX "IX_TaskComments_WorkspaceId_UpdatedAt" ON "TaskComments" ("WorkspaceId", "UpdatedAt");

            CREATE TABLE "BudgetAllocations" (
              "Id" uuid NOT NULL PRIMARY KEY, "PlanId" uuid NOT NULL, "CategoryId" uuid NOT NULL, "OwnMinutes" integer NOT NULL,
              "WorkspaceId" uuid NOT NULL, "Revision" bigint NOT NULL, "CreatedAt" timestamp with time zone NOT NULL,
              "UpdatedAt" timestamp with time zone NOT NULL, "DeletedAt" timestamp with time zone NULL);
            CREATE UNIQUE INDEX "IX_BudgetAllocations_PlanId_CategoryId" ON "BudgetAllocations" ("PlanId", "CategoryId");
            CREATE INDEX "IX_BudgetAllocations_WorkspaceId_UpdatedAt" ON "BudgetAllocations" ("WorkspaceId", "UpdatedAt");

            UPDATE "ChangeFeed" SET "EntityType"='categoryTree', "PayloadJson"=replace("PayloadJson", 'timelineId', 'categoryTreeId') WHERE lower("EntityType")='timeline';
            UPDATE "ChangeFeed" SET "PayloadJson"=replace("PayloadJson", 'timelineId', 'categoryTreeId') WHERE lower("EntityType") IN ('category','event');
            INSERT INTO "ChangeFeed" ("WorkspaceId","EntityType","EntityId","Revision","Deleted","PayloadJson","CreatedAt")
            SELECT t."WorkspaceId",'task',t."Id",t."Revision",t."DeletedAt" IS NOT NULL,
              json_build_object('id',t."Id",'workspaceId',t."WorkspaceId",'revision',t."Revision",'createdAt',t."CreatedAt",'updatedAt',t."UpdatedAt",'deletedAt',t."DeletedAt",'title',t."Title",'categoryId',t."CategoryId",'parentTaskId',t."ParentTaskId",'estimateMinutes',t."EstimateMinutes",'remainingEstimateMinutes',t."RemainingEstimateMinutes",'deadline',t."Deadline",'nextActionDate',t."NextActionDate",'nextActionTime',t."NextActionTime",'zoneId',t."ZoneId",'value',t."Value",'energy',CASE t."Energy" WHEN 0 THEN 'low' WHEN 2 THEN 'high' ELSE 'medium' END,'progress',t."Progress",'status',CASE t."Status" WHEN 0 THEN 'active' WHEN 1 THEN 'paused' WHEN 2 THEN 'completed' ELSE 'cancelled' END,'splittable',t."Splittable")::text,now() FROM "Tasks" t;
            INSERT INTO "ChangeFeed" ("WorkspaceId","EntityType","EntityId","Revision","Deleted","PayloadJson","CreatedAt")
            SELECT p."WorkspaceId",'plan',p."Id",p."Revision",p."DeletedAt" IS NOT NULL,
              json_build_object('id',p."Id",'workspaceId',p."WorkspaceId",'revision',p."Revision",'createdAt',p."CreatedAt",'updatedAt',p."UpdatedAt",'deletedAt',p."DeletedAt",'name',p."Name",'kind',CASE p."Kind" WHEN 0 THEN 'budget' ELSE 'timeline' END,'startsAt',p."StartsAt",'endsAt',p."EndsAt",'zoneId',p."ZoneId",'archived',p."Archived")::text,now() FROM "Plans" p;
            INSERT INTO "ChangeFeed" ("WorkspaceId","EntityType","EntityId","Revision","Deleted","PayloadJson","CreatedAt")
            SELECT p."WorkspaceId",'plannedEvent',p."Id",p."Revision",p."DeletedAt" IS NOT NULL,
              json_build_object('id',p."Id",'workspaceId',p."WorkspaceId",'revision',p."Revision",'createdAt',p."CreatedAt",'updatedAt',p."UpdatedAt",'deletedAt',p."DeletedAt",'planId',p."PlanId",'categoryTreeId',p."CategoryTreeId",'categoryId',p."CategoryId",'taskId',p."TaskId",'occurredAt',p."OccurredAt",'note',p."Note")::text,now() FROM "PlannedEvents" p;
            """);
    }

    protected override void Down(MigrationBuilder migrationBuilder) =>
        throw new NotSupportedException("GrowthModel is a forward-only data migration.");
}
