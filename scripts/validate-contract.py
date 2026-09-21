#!/usr/bin/env python3
from pathlib import Path
import sys
import yaml

contract_path = Path(__file__).resolve().parents[1] / "contracts" / "openapi.yaml"
document = yaml.safe_load(contract_path.read_text(encoding="utf-8"))
errors: list[str] = []

if document.get("openapi") != "3.1.0":
    errors.append("openapi must be 3.1.0")

operation_ids: set[str] = set()
for path, path_item in document.get("paths", {}).items():
    for method, operation in path_item.items():
        if method.lower() not in {"get", "post", "put", "patch", "delete"}:
            continue
        operation_id = operation.get("operationId")
        if not operation_id:
            errors.append(f"{method.upper()} {path}: operationId is required")
        elif operation_id in operation_ids:
            errors.append(f"duplicate operationId: {operation_id}")
        else:
            operation_ids.add(operation_id)

schemas = document.get("components", {}).get("schemas", {})
parameters = document.get("components", {}).get("parameters", {})

def walk(value: object, location: str) -> None:
    if isinstance(value, dict):
        reference = value.get("$ref")
        if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
            name = reference.rsplit("/", 1)[-1]
            if name not in schemas:
                errors.append(f"{location}: unknown schema reference {reference}")
        if isinstance(reference, str) and reference.startswith("#/components/parameters/"):
            name = reference.rsplit("/", 1)[-1]
            if name not in parameters:
                errors.append(f"{location}: unknown parameter reference {reference}")
        for key, child in value.items():
            walk(child, f"{location}/{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            walk(child, f"{location}/{index}")

walk(document, "#")
if errors:
    print("OpenAPI contract invalid:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print(f"OpenAPI contract OK: {len(operation_ids)} operations, {len(schemas)} schemas")
