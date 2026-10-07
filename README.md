# T4L

API-first time accounting and planning system. The server is the canonical data source; Android keeps an offline Room replica and a transactional outbox.

Release version: `VERSION` is the single source of truth for the API image and Android `versionName`. Tagged releases use annotated tags `vXX.YY.ZZ.NN`.

## Components

- `contracts/openapi.yaml` — canonical HTTP/sync contract.
- `server/` — .NET 10 API and PostgreSQL persistence.
- `android-app/` — Kotlin/Compose offline client (`minSdk 29`).
- `deploy/` — image-only production Compose, development and observability overlays.

## Development runtime

The unauthenticated profile is deliberately development-only. It must be explicitly enabled and a non-loopback bind additionally requires `T4L__AllowInsecureLan=true`.

```bash
docker compose -f deploy/compose.yaml -f deploy/compose.dev.yaml up -d postgres
ASPNETCORE_ENVIRONMENT=Development \
T4L__DevelopmentInsecure=true \
T4L__AllowInsecureLan=true \
ASPNETCORE_URLS='http://192.168.202.35:5099' \
ConnectionStrings__T4L='Host=127.0.0.1;Port=55432;Database=t4l;Username=t4l;Password=t4l_dev_only' \
dotnet run --project server/src/T4L.Api
```

API for devices on the current trusted LAN: `http://192.168.202.35:5099`; liveness: `/health/live`; readiness: `/health/ready`; safe build identity: `/about`. The host address is deliberately explicit: update `ASPNETCORE_URLS`, `T4L_API_BIND_ADDRESS`, and `T4L_DEBUG_API_BASE_URL` if DHCP changes it. The development Compose overlay uses the same defaults and can be overridden with `T4L_API_BIND_ADDRESS` and `T4L_API_PORT`.

Diagnostic logging is off by default. Enable safe operational events with `DebugLogging__Enabled=true` and `DebugLogging__Level=Basic`. `Verbose` is temporary and adds only allowlisted metadata; route parameters, query values, headers, tokens, and payloads are never logged. Android diagnostics are batched through `/api/v1/diagnostics/events`; the API emits them through the same stdout/OTLP pipeline. `deploy/compose.observability.yaml` enables `Observability__RequireExternalSink=true`, so the operational profile fails startup without a valid OTLP endpoint.

Production uses OIDC JWT Bearer authentication. Configure `Oidc__Authority` and `Oidc__Audience`; the authority must use HTTPS. Android release builds require Gradle properties `T4L_OIDC_ISSUER`, `T4L_OIDC_CLIENT_ID`, and `T4L_RELEASE_API_BASE_URL`, and use Authorization Code with PKCE and redirect URI `app.t4l://oauth2redirect`.

## Validation

```bash
dotnet test T4L.slnx
T4L_TEST_POSTGRES='Host=127.0.0.1;Port=55432;Database=t4l;Username=t4l;Password=t4l_dev_only' \
  dotnet test server/tests/T4L.Api.Tests --filter FullyQualifiedName~OverlappingPlanSyncTests
dotnet build T4L.slnx
cd android-app && ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./scripts/smoke-api.sh
docker compose --env-file deploy/.env.example -f deploy/compose.yaml -f deploy/compose.observability.yaml config --quiet
```

Docker is required for PostgreSQL/runtime acceptance and container delivery. Android needs JDK 17 and Android SDK 36. Production deployment uses the prebuilt GHCR image and is documented in [`docs/CONTAINER_DEPLOYMENT_ADMIN_GUIDE.md`](docs/CONTAINER_DEPLOYMENT_ADMIN_GUIDE.md). A local source build is always `unverified-local`; tagged CI releases use image-derived artifact verification and publish immutable version/revision tags.

The Android server URL and sync delay are editable in `Settings > Network`. A cold app start always requests a sync. Local changes are debounced using the selected delay (`0`, `5`, `15`, `30`, `60`, or `300` seconds; default `30`), while `Sync now` remains explicit. There is no periodic or every-foreground sync.

An active-to-inactive task transition is a sync atomic group: the task upsert and, when the task owns the latest interval in its category tree, a taskless closing event share `atomicGroupId`. The server applies both or neither. A single task transition from older clients is rejected with `atomic_group_required`; legacy transition writes are not supported. Conflicted groups retain the closure event locally until the user resolves the task conflict.

Category-subtree archive and ancestor-path restore are atomic category groups. Archive must include every descendant; standalone parent archive is rejected. Android keeps a conflicted category group and its dependent task pauses in the outbox until the user chooses the local or server version. Independent mutations continue syncing. `Sync__MaxMutationsPerPush` (`T4L_SYNC_MAX_MUTATIONS_PER_PUSH` in Compose) accepts `1..1000`, defaults to `100`, and is advertised by `/bootstrap`; Android rejects a larger subtree before changing local state. When the server limit decreases after a group was queued, the entire group is rejected without partial server writes. Older clients that send non-atomic parent archives are not supported.

`Categorization > Deleted` retains archived trees for 30 days from the server-confirmed deletion time. Trees can be restored before expiry or permanently removed from the restorable list after their pending changes sync; the server purges expired entries hourly. Historical tree/category labels remain as read-only reference metadata so factual events and tasks keep their labels; this is an irreversible logical purge, not physical erasure of referenced rows. `Planner > Archived` shows read-only plan details and permits restore or deletion; deleting a plan also removes its budgets and planned events, never factual events. Time distribution reports include only active category trees. Android rejects malformed plan dates from sync rather than writing epoch-zero values, and repairs already-invalid local plan periods from a valid canonical snapshot where available.

`Categorization > Palettes` stores synced flat category selections, per-category display color overrides, and a mixed category/task order. Category-tree border colors use a separate synced `treeAppearance` record; palette colors never change timeline event borders. Deleted palettes share the 30-day restore/purge policy. `Planner` remembers one active plan per workspace and shows only its details; opening the plan picker clears that selection. Tracking and Planner share device-local tree visibility and tree-name settings, while panel height is remembered separately. A shared palette selection has an optional per-plan override; palette dates stay local to tracking or the current plan. Hidden trees remain available for palette quick entry and produce a warning after successful event creation.

Restoring a tree does not alter the archived state of its categories. The tree editor can reveal archived categories and restore each one separately; restoring a descendant also restores its required ancestor path. Original individual archive choices from older clients cannot be reconstructed automatically.

Settings are split into `Network`, `Backup`, `Security`, `Diagnostics`, and `Language`. Language is an app-local English/Russian choice; before the first explicit choice Android follows the system locale.

Backup export/import reports processing, completion, invalid-password, invalid-file, storage, network, and authorization outcomes without exposing raw server errors. Conflict resolution under `Settings > Diagnostics > Sync issues` compares the local and server values before either version can be confirmed; raw mutation JSON remains an advanced diagnostic action.

`Home` is the Android start screen. It contains a device-local Tomato timer with simple and classic four-round modes, plus a statistical life countdown. The user-level profile (`/api/v1/me/profile`) and cropped avatar are cached offline and synchronized independently from the selected workspace. Disjoint profile-field edits are rebased automatically; overlapping profile edits and concurrent avatar edits require an explicit choice in `Settings > Diagnostics > Sync issues`. The active timer is deliberately not synchronized between devices and does not create `Track` events.

`Tasks` has device-local remembered filters for status, flat/top-level/tree views, priority/next-step/deadline sorting, and four detail levels. A title tap opens the full editor; a left swipe or accessibility action starts inline rename. The `=` handle offers earlier/later moves in Priority sort mode; in another sort mode it asks before switching to Priority. Drag reorder is available only in Priority sort mode. Subtask lists use the same controls and presentation settings.

Background timer completion uses a foreground service and exact alarm when Android grants that access. If exact alarms are disabled, the UI warns that the completion signal can be delayed. Android 13+ also requests notification permission; the running state remains based on the persisted deadline rather than notification delivery.

If notification permission is denied, the timer still starts in degraded mode. The Home screen keeps a warning visible while the timer is active and links to the application notification settings because a background completion signal may be missed.

Release builds accept HTTPS only. Debug builds additionally accept literal loopback or RFC1918 HTTP addresses; arbitrary hostnames and public HTTP addresses are rejected by `ServerUrlPolicy`.

## Security boundary

`DevelopmentInsecure` creates a fixed development user/workspace. Binding it to the LAN is acceptable only on the current trusted development network and requires the explicit unsafe-LAN gate; it must never be exposed to an untrusted network. Production startup requires OIDC and an external OTLP sink. Workspace routes and SignalR subscriptions enforce membership roles.

## Schema compatibility

- Server schema v1 is intentionally unsupported. If startup reports `legacy_schema_not_supported`, recreate the development database instead of applying a lossy conversion.
- Android database v1 is reset destructively. Versions v2–v10 retain the documented incremental migrations; v10→v11 grants previously archived categories a fresh 48-hour window, v11→v12 converts palette order and pending mutations to stable rows with nullable slots, and v12→v13 records outbox dependencies for atomic category archives.
- Backup export uses `formatVersion: 5`; import accepts v4 (upgraded to rows) and v5, validating the complete graph before a single transactional import.

## Product model

- `CategoryTree` is a classification tree such as Activity or Location. Categories are recursive; archived categories can be restored individually for 48 hours, after which they are hidden permanently while historical names/references remain. Restoring a descendant restores only its required ancestor path.
- Actual `TimeEvent` and `PlannedEvent` records are instantaneous switches; intervals end at the next event in the same tree.
- A `Task` is independent from categories, belongs to either a category or a parent task, and supports active, paused, completed, and cancelled states plus append-only comments.
- A `Plan` covers an arbitrary `[startsAt, endsAt)` period and contains independent budget allocations and timeline events; either part can be empty. Plans may overlap. Own and descendant-inclusive timeline durations are calculated independently for each category tree in milliseconds; budget totals are separate user-entered values.
- Task time is the sum of factual intervals directly tagged with that task, without subtasks. Android appends a taskless event in the same category tree when pausing, completing, or cancelling a currently tracked task; other sync clients must do the same in the same logical operation. The task-time projection reports signed `spentMillis - estimateMinutes * 60000`.
- Palette layout is server-canonical `rowsJson`: each row has a stable ID and nullable cells, including empty rows. `itemOrderJson` is the derived non-null order. Existing palettes migrate once; deploy the server migration before installing the new Android client. Older sync clients are not supported for palette editing; v3 and earlier backup formats remain unsupported.

## Current MVP boundaries

- Stale-revision conflicts are retained locally and resolved explicitly with server, local, or edited payload choices; last-write-wins is not used. Natural-key budget allocations use a deterministic cross-client identity and conflicting revisions expose the canonical entity ID.
- Uploaded avatars are decoded, dimension-limited, metadata-stripped, resized to at most 1024 px, and stored as canonical JPEG. Client diagnostics are disabled by default; `Basic` emits the fixed operational event/attribute allowlist, while temporary `Verbose` additionally emits bounded phase/status context through logcat, rotating local NDJSON, and the authenticated server sink.
- The Android HTTP DTOs currently mirror the OpenAPI contract manually; generated-client drift enforcement is still pending.
- Recurring plans, task/deadline notifications, automatic scheduling, task budgeting, and archive restoration UI are outside the current iteration.
