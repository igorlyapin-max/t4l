#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

image_ref="${1:?usage: build-verified-image.sh IMAGE_REF}"
version="$(tr -d '\r\n' < VERSION)"
revision="$(git rev-parse HEAD)"
build_network="${T4L_BUILD_NETWORK:-default}"
verify_port="${T4L_VERIFY_PORT:-18089}"
connection_string="${T4L_VERIFY_CONNECTION_STRING:-Host=127.0.0.1;Port=55432;Database=t4l;Username=t4l;Password=t4l_dev_only}"

if [[ ! "${version}" =~ ^[0-9]{2}(\.[0-9]{2}){3}$ ]]; then
  echo "invalid VERSION: ${version}" >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "verified image requires a clean Git worktree" >&2
  exit 1
fi

work_dir="$(mktemp -d "${repo_root}/.t4l-image-build.XXXXXX")"
container_name="t4l-verify-${revision:0:12}"
cleanup() {
  docker rm -f "${container_name}" >/dev/null 2>&1 || true
  rm -rf "${work_dir}"
}
trap cleanup EXIT
phase() { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1"; }

phase "export Docker-built runtime artifact"
mkdir -p "${work_dir}/artifact"
docker build --network="${build_network}" --file server/src/T4L.Api/Dockerfile \
  --target artifact-export --output "type=tar,dest=${work_dir}/artifact.tar" .
tar -xf "${work_dir}/artifact.tar" -C "${work_dir}/artifact"
artifact_digest="$(sha256sum "${work_dir}/artifact/T4L.Api.dll" | awk '{print $1}')"

phase "build verified image"
docker build --network="${build_network}" --file server/src/T4L.Api/Dockerfile \
  --build-arg "T4L_BUILD_VERSION=${version}" \
  --build-arg "T4L_GIT_REVISION=${revision}" \
  --build-arg T4L_SOURCE_CLEAN=true \
  --build-arg T4L_BUILD_PROVENANCE=verified \
  --build-arg "T4L_RUNTIME_ARTIFACT_SHA256=${artifact_digest}" \
  --tag "${image_ref}" .

phase "verify image labels and embedded artifact"
test "$(docker image inspect "${image_ref}" --format '{{index .Config.Labels "org.opencontainers.image.version"}}')" = "${version}"
test "$(docker image inspect "${image_ref}" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')" = "${revision}"
test "$(docker image inspect "${image_ref}" --format '{{index .Config.Labels "app.t4l.source-clean"}}')" = "true"
test "$(docker image inspect "${image_ref}" --format '{{index .Config.Labels "app.t4l.provenance"}}')" = "verified"
test "$(docker image inspect "${image_ref}" --format '{{index .Config.Labels "app.t4l.runtime-artifact-sha256"}}')" = "${artifact_digest}"

phase "runtime smoke"
docker run --detach --name "${container_name}" --network host \
  --env ASPNETCORE_ENVIRONMENT=Development \
  --env "ASPNETCORE_URLS=http://127.0.0.1:${verify_port}" \
  --env T4L__DevelopmentInsecure=true \
  --env "ConnectionStrings__T4L=${connection_string}" \
  "${image_ref}" >/dev/null
for _ in $(seq 1 60); do
  if curl --fail --silent "http://127.0.0.1:${verify_port}/health/ready" >/dev/null; then break; fi
  sleep 1
done
about="$(curl --fail --silent "http://127.0.0.1:${verify_port}/about")"
python3 -c 'import json,sys; data=json.loads(sys.argv[1]); expected=sys.argv[2:]; actual=[data["version"],data["gitRevision"],str(data["sourceClean"]).lower(),data["provenance"],data["runtimeArtifactSha256"]]; assert actual == expected, (actual, expected)' \
  "${about}" "${version}" "${revision}" true verified "${artifact_digest}"

phase "verified image ready: ${image_ref}"
