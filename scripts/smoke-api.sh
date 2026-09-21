#!/usr/bin/env bash
set -euo pipefail

api_base="${T4L_API_BASE_URL:-http://127.0.0.1:5080}"
workspace_id="018f0000-0000-7000-8000-000000000002"
client_id="01900000-0000-7000-8000-000000000010"
tree_id="01900000-0000-7000-8000-000000000011"
category_id="01900000-0000-7000-8000-000000000013"
plan_id="01900000-0000-7000-8000-000000000014"
mutation_id="01900000-0000-7000-8000-000000000112"

curl --fail --silent --show-error "${api_base}/health/live" >/dev/null
curl --fail --silent --show-error "${api_base}/health/ready" >/dev/null
curl --fail --silent --show-error "${api_base}/api/v1/bootstrap" >/dev/null

payload="{\"clientId\":\"${client_id}\",\"mutations\":[{\"clientMutationId\":\"${mutation_id}\",\"workspaceId\":\"${workspace_id}\",\"entityType\":\"categoryTree\",\"entityId\":\"${tree_id}\",\"operation\":\"upsert\",\"baseRevision\":0,\"payload\":{\"name\":\"Smoke\",\"role\":\"standard\",\"sortOrder\":0,\"archived\":false}},{\"clientMutationId\":\"01900000-0000-7000-8000-000000000015\",\"workspaceId\":\"${workspace_id}\",\"entityType\":\"category\",\"entityId\":\"${category_id}\",\"operation\":\"upsert\",\"baseRevision\":0,\"payload\":{\"categoryTreeId\":\"${tree_id}\",\"parentId\":null,\"name\":\"Smoke child\",\"loadType\":\"light\",\"sortOrder\":0,\"archived\":false}},{\"clientMutationId\":\"01900000-0000-7000-8000-000000000016\",\"workspaceId\":\"${workspace_id}\",\"entityType\":\"plan\",\"entityId\":\"${plan_id}\",\"operation\":\"upsert\",\"baseRevision\":0,\"payload\":{\"name\":\"Smoke budget\",\"kind\":\"budget\",\"startsAt\":\"2026-01-01T00:00:00Z\",\"endsAt\":\"2030-01-01T00:00:00Z\",\"zoneId\":\"UTC\",\"archived\":false}}]}"
push_result="$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d "${payload}" "${api_base}/api/v1/sync/push")"
if [[ "${push_result}" != *'"status":"applied"'* ]]; then
  echo "sync push did not apply: ${push_result}" >&2
  exit 1
fi

changes="$(curl --fail --silent --show-error "${api_base}/api/v1/sync/changes?workspaceId=${workspace_id}&cursor=0&limit=200")"
if [[ "${changes}" != *"${tree_id}"* || "${changes}" != *"${category_id}"* ]]; then
  echo "sync pull did not return smoke category tree" >&2
  exit 1
fi

duplicate_result="$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d "${payload}" "${api_base}/api/v1/sync/push")"
if [[ "${duplicate_result}" != *'"status":"applied"'* || "${duplicate_result}" != *'"duplicate":true'* ]]; then
  echo "idempotent retry did not preserve the original result: ${duplicate_result}" >&2
  exit 1
fi

allocation_1='01900000-0000-7000-8000-000000000021'
allocation_2='01900000-0000-7000-8000-000000000022'
allocation_payload_1="{\"clientId\":\"01900000-0000-7000-8000-000000000023\",\"mutations\":[{\"clientMutationId\":\"01900000-0000-7000-8000-000000000024\",\"workspaceId\":\"${workspace_id}\",\"entityType\":\"budgetAllocation\",\"entityId\":\"${allocation_1}\",\"operation\":\"upsert\",\"baseRevision\":0,\"payload\":{\"planId\":\"${plan_id}\",\"categoryId\":\"${category_id}\",\"ownMinutes\":30}}]}"
allocation_payload_2="{\"clientId\":\"01900000-0000-7000-8000-000000000025\",\"mutations\":[{\"clientMutationId\":\"01900000-0000-7000-8000-000000000026\",\"workspaceId\":\"${workspace_id}\",\"entityType\":\"budgetAllocation\",\"entityId\":\"${allocation_2}\",\"operation\":\"upsert\",\"baseRevision\":0,\"payload\":{\"planId\":\"${plan_id}\",\"categoryId\":\"${category_id}\",\"ownMinutes\":45}}]}"
curl --fail --silent --show-error -H 'Content-Type: application/json' -d "${allocation_payload_1}" "${api_base}/api/v1/sync/push" >/dev/null
allocation_conflict="$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d "${allocation_payload_2}" "${api_base}/api/v1/sync/push")"
if [[ "${allocation_conflict}" != *'"status":"conflict"'* || "${allocation_conflict}" != *'"canonicalEntityId"'* ]]; then
  echo "multi-client budget allocation did not converge to conflict: ${allocation_conflict}" >&2
  exit 1
fi

echo "API smoke passed: health, readiness, idempotent push, delta pull"
