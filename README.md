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

Settings are split into `Network`, `Backup`, `Security`, `Diagnostics`, and `Language`. Language is an app-local English/Russian choice; before the first explicit choice Android follows the system locale.

Backup export/import reports processing, completion, invalid-password, invalid-file, storage, network, and authorization outcomes without exposing raw server errors. Conflict resolution under `Settings > Diagnostics > Sync issues` compares the local and server values before either version can be confirmed; raw mutation JSON remains an advanced diagnostic action.

`Home` is the Android start screen. It contains a device-local Tomato timer with simple and classic four-round modes, plus a statistical life countdown. The user-level profile (`/api/v1/me/profile`) and cropped avatar are cached offline and synchronized independently from the selected workspace. Disjoint profile-field edits are rebased automatically; overlapping profile edits and concurrent avatar edits require an explicit choice in `Settings > Diagnostics > Sync issues`. The active timer is deliberately not synchronized between devices and does not create `Track` events.

Background timer completion uses a foreground service and exact alarm when Android grants that access. If exact alarms are disabled, the UI warns that the completion signal can be delayed. Android 13+ also requests notification permission; the running state remains based on the persisted deadline rather than notification delivery.

If notification permission is denied, the timer still starts in degraded mode. The Home screen keeps a warning visible while the timer is active and links to the application notification settings because a background completion signal may be missed.

Release builds accept HTTPS only. Debug builds additionally accept literal loopback or RFC1918 HTTP addresses; arbitrary hostnames and public HTTP addresses are rejected by `ServerUrlPolicy`.

## Security boundary

`DevelopmentInsecure` creates a fixed development user/workspace. Binding it to the LAN is acceptable only on the current trusted development network and requires the explicit unsafe-LAN gate; it must never be exposed to an untrusted network. Production startup requires OIDC and an external OTLP sink. Workspace routes and SignalR subscriptions enforce membership roles.

## Schema compatibility

- Server schema v1 is intentionally unsupported. If startup reports `legacy_schema_not_supported`, recreate the development database instead of applying a lossy conversion.
- Android database v1 is reset destructively. Database v2 migrates to v3 while preserving current entities and sync conflicts; v3 migrates to v4 with the user profile cache and offline profile/avatar queues; v4 migrates to v5 with profile base snapshots and explicit personal conflicts.
- Backup import accepts only `formatVersion: 2` and validates the complete graph before a single transactional import.

## Product model

- `CategoryTree` is a classification tree such as Activity or Location. Categories are recursive and can be archived without deleting historical events.
- Actual `TimeEvent` and `PlannedEvent` records are instantaneous switches; intervals end at the next event in the same tree.
- A `Task` is independent from categories, belongs to either a category or a parent task, and supports active, paused, completed, and cancelled states plus append-only comments.
- A `Plan` covers an arbitrary `[startsAt, endsAt)` period and is either `budget` or `timeline`. Plans may overlap. Budget totals are calculated independently for each category tree.
- Backup export/import uses `formatVersion: 2`; v1 import and legacy Timeline/DailyPlan API contracts are intentionally unsupported.

## Current MVP boundaries

- Stale-revision conflicts are retained locally and resolved explicitly with server, local, or edited payload choices; last-write-wins is not used. Natural-key budget allocations use a deterministic cross-client identity and conflicting revisions expose the canonical entity ID.
- Uploaded avatars are decoded, dimension-limited, metadata-stripped, resized to at most 1024 px, and stored as canonical JPEG. Client diagnostics are disabled by default; `Basic` emits the fixed operational event/attribute allowlist, while temporary `Verbose` additionally emits bounded phase/status context through logcat, rotating local NDJSON, and the authenticated server sink.
- The Android HTTP DTOs currently mirror the OpenAPI contract manually; generated-client drift enforcement is still pending.
- Recurring plans, task/deadline notifications, drag-and-drop ordering, automatic scheduling, task budgeting, and archive restoration UI are outside the current iteration.
