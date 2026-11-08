"""Full OAS 3.1 document validation; no HTTP calls or business execution."""
import copy
import hashlib
import importlib.metadata
import json
from pathlib import Path
import sys

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import OpenAPIV31SpecValidator

ROOT = Path(__file__).resolve().parents[3]
files = sys.argv[1:] or [
    "docs/contracts/request-flow.openapi.json",
    "docs/contracts/account-access.openapi.json",
]
reports = []
examples = json.loads((ROOT / "docs/contracts/account-extension-examples.json").read_text(encoding="utf-8"))
for name in files:
    path = ROOT / name
    raw = path.read_bytes()
    spec = json.loads(raw)
    if spec.get("openapi") != "3.1.1":
        raise ValueError(f"Unexpected OAS dialect in {name}")

    def check_refs(value):
        if isinstance(value, dict):
            if "$ref" in value and not value["$ref"].startswith("#/"):
                raise ValueError("External references are not permitted in this check")
            for child in value.values():
                check_refs(child)
        elif isinstance(value, list):
            for child in value:
                check_refs(child)

    check_refs(spec)
    errors = list(OpenAPIV31SpecValidator(spec).iter_errors())
    if errors:
        for error in errors:
            print(f"{name}: {list(error.path)}: {error.message}", file=sys.stderr)
        raise SystemExit(1)
    for schema in spec["components"]["schemas"].values():
        Draft202012Validator.check_schema(schema)
    invalid = copy.deepcopy(spec)
    del invalid["info"]
    if not list(OpenAPIV31SpecValidator(invalid).iter_errors()):
        raise AssertionError("Negative control did not reject a missing info object")
    example_count = 0
    for example in examples:
        if path.stem != example["spec"] + ".openapi":
            continue
        schema_bundle = {
            "$ref": "#/components/schemas/" + example["schema"],
            "components": spec["components"],
        }
        failures = list(Draft202012Validator(schema_bundle, format_checker=FormatChecker()).iter_errors(example["value"]))
        if (not failures) != example["expectedValid"]:
            raise AssertionError(f"Unexpected schema result: {example['id']}: {[e.message for e in failures]}")
        example_count += 1
    reports.append({
        "file": name,
        "openapi": spec["openapi"],
        "version": spec["info"]["version"],
        "schemasChecked": len(spec["components"]["schemas"]),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "status": "passed",
        "negativeControl": "missing info rejected",
        "fullSchemaExamples": example_count,
    })

print(json.dumps({
    "status": "passed",
    "validator": "OpenAPIV31SpecValidator",
    "packageVersion": importlib.metadata.version("openapi-spec-validator"),
    "jsonschemaVersion": importlib.metadata.version("jsonschema"),
    "reports": reports,
    "limits": "OAS document and schema meta-validation only; no API, authorization, mail, concurrency or retention behavior executed.",
}, ensure_ascii=False, indent=2))
