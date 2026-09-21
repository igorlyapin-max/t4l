# Container deployment

## Delivery contract

Production runtime uses the prebuilt image from `T4L_API_IMAGE`. `deploy/compose.yaml` never builds application source on the target host. Images accepted for delivery expose `/about` with the same version, Git revision, clean-source state, provenance and `T4L.Api.dll` SHA-256 stored in their OCI labels. `unverified-local` images are development artifacts and are not customer delivery evidence.

Copy `deploy/.env.example` to an untracked `deploy/.env` and replace every `REPLACE_*` value. PostgreSQL credentials are injected by the deployment platform or `.env`; no secret manager is required by the application itself. Never commit the populated file.

```bash
cp deploy/.env.example deploy/.env
docker login ghcr.io
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.observability.yaml pull
docker compose --env-file deploy/.env -f deploy/compose.yaml -f deploy/compose.observability.yaml up -d
curl --fail http://127.0.0.1:5099/health/live
curl --fail http://127.0.0.1:5099/health/ready
curl --fail http://127.0.0.1:5099/about
docker compose --env-file deploy/.env -f deploy/compose.yaml logs api
```

Database migrations run during API startup. Server schema v1 is unsupported and must be recreated before deployment; later migrations are automatic. Back up the PostgreSQL volume before upgrades. Roll back by restoring that backup and selecting the previous immutable image tag in `T4L_API_IMAGE`.

## Network and trust prerequisites

- Permit the Docker host to pull `ghcr.io/igorlyapin-max/t4l-api`; configure Docker daemon registry CA trust separately if the registry is mirrored through private TLS.
- Permit API outbound HTTPS to the configured OIDC authority and outbound OTLP/gRPC to the collector.
- The container uses the public CA bundle from its Ubuntu base. A private OIDC or collector CA requires a separately reviewed image trust profile; registry daemon trust does not configure application TLS trust.
- Keep the API bound to loopback and publish it through an organization-approved HTTPS reverse proxy. Android release clients reject plaintext and require a trusted certificate and DNS name.
- Default host ports are API `5099`, PostgreSQL loopback `55432`, collector loopback `4317`, and collector health `13133`. Adjust firewall rules only for the intended contour.

`DebugLogging__Level` accepts `Basic` or `Verbose`. Debug logging is off by default; use `Verbose` temporarily. Logs go to structured stdout and to the configured OTLP collector. Stop with `docker compose ... down`; do not add `-v` unless permanent database removal is intended.

## Development source build

Development may build an explicitly unverified image:

```bash
docker compose -f deploy/compose.yaml -f deploy/compose.dev.yaml up -d --build
```

This path is not a substitute for the tagged image produced by CI.
