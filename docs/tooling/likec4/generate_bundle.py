#!/usr/bin/env python3
"""Generate or verify the public LikeC4 web-component bundle."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
TOOLING = ROOT / "docs/tooling/likec4"
ARCHITECTURE = ROOT / "docs/public/architecture"
DEFAULT_OUTPUT = ARCHITECTURE / "likec4-views.mjs"
LOCKFILE = TOOLING / "package-lock.json"
CANONICALIZER = TOOLING / "canonicalize_bundle_model.mjs"
WRAPPER_START = b"var LikeC4Views=(function("
WRAPPER_END = b"})({});"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def lock_digest() -> str:
    require(LOCKFILE.is_file(), f"LikeC4 lockfile is missing: {LOCKFILE}")
    return hashlib.sha256(LOCKFILE.read_bytes()).hexdigest()


def provenance(lock_sha256: str, model_sha256: str) -> bytes:
    return (
        f"// kast-likec4-lock-sha256:{lock_sha256}\n"
        f"// kast-likec4-model-sha256:{model_sha256}\n"
    ).encode()


def provenance_parts(bundle: bytes, lock_sha256: str) -> tuple[bytes, bytes]:
    first_end = bundle.find(b"\n") + 1
    second_end = bundle.find(b"\n", first_end) + 1
    require(first_end > 0 and second_end > first_end, "committed LikeC4 provenance is incomplete")
    require(
        bundle[:first_end] == f"// kast-likec4-lock-sha256:{lock_sha256}\n".encode(),
        "committed LikeC4 bundle has stale or missing lockfile provenance",
    )
    return bundle[first_end:second_end], bundle[second_end:]


def install_tooling() -> Path:
    subprocess.run(
        [
            "npm",
            "ci",
            "--prefix",
            str(TOOLING),
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        ],
        cwd=ROOT,
        check=True,
    )
    executable = TOOLING / "node_modules/.bin/likec4"
    require(executable.is_file(), "locked LikeC4 executable is missing")
    return executable


def generate_raw(executable: Path, output: Path) -> bytes:
    subprocess.run(
        [
            str(executable),
            "gen",
            "webcomponent",
            "--outfile",
            str(output),
            "--webcomponent-prefix",
            "kast",
            str(ARCHITECTURE),
        ],
        cwd=ROOT,
        check=True,
    )
    require(output.is_file(), "LikeC4 did not produce the requested bundle")
    return output.read_bytes()


def verify_wrapper(bundle: bytes, label: str) -> None:
    require(bundle.startswith(WRAPPER_START), f"{label} has an invalid module wrapper")
    require(bundle.rstrip().endswith(WRAPPER_END), f"{label} has an invalid module ending")
    require(bundle.count(b"customElements.define(") == 1, f"{label} has an invalid custom element registration")
    require(b"LikeC4View" in bundle, f"{label} does not export LikeC4View")


def export_computed_model(executable: Path, output: Path) -> object:
    subprocess.run(
        [
            str(executable),
            "export",
            "json",
            "--skip-layout",
            "--project",
            "kast-public-architecture",
            "--outfile",
            str(output),
            str(ARCHITECTURE),
        ],
        cwd=ROOT,
        check=True,
    )
    model = json.loads(output.read_text())
    require(isinstance(model, dict), "LikeC4 compute-only export is not a project model")
    require(model.get("_stage") == "computed", "LikeC4 compute-only export has the wrong stage")
    require(
        model.get("projectId") == "kast-public-architecture",
        "LikeC4 compute-only export has the wrong project identity",
    )
    return model


def canonical_bundle_model(bundle: Path) -> object:
    require(CANONICALIZER.is_file(), f"LikeC4 canonicalizer is missing: {CANONICALIZER}")
    result = subprocess.run(
        ["node", str(CANONICALIZER), str(bundle)],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def canonical_bytes(model: object) -> bytes:
    return json.dumps(model, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()


def semantic_model(model: object) -> object:
    semantic = copy.deepcopy(model)
    require(isinstance(semantic, dict), "LikeC4 semantic model is not an object")
    relations = semantic.get("relations")
    require(isinstance(relations, dict), "LikeC4 semantic model relations are missing")
    relation_ids: dict[str, str] = {}
    normalized_relations: dict[str, object] = {}
    for relation_id, relation in relations.items():
        require(isinstance(relation, dict), f"LikeC4 relation {relation_id} is not an object")
        embedded_id = relation.pop("id", None)
        require(embedded_id == relation_id, f"LikeC4 relation {relation_id} has a mismatched identity")
        stable_id = "semantic-" + hashlib.sha256(canonical_bytes(relation)).hexdigest()
        require(stable_id not in normalized_relations, f"duplicate LikeC4 relation semantics: {relation_id}")
        relation_ids[relation_id] = stable_id
        relation["id"] = stable_id
        normalized_relations[stable_id] = relation
    semantic["relations"] = normalized_relations

    views = semantic.get("views")
    require(isinstance(views, dict), "LikeC4 semantic model views are missing")
    for view_id, view in views.items():
        require(isinstance(view, dict), f"LikeC4 semantic view {view_id} is not an object")
        view_hash = view.pop("hash", None)
        require(
            isinstance(view_hash, str) and bool(view_hash),
            f"LikeC4 semantic view {view_id} has no generated hash",
        )
        edges = view.get("edges")
        require(isinstance(edges, list), f"LikeC4 semantic view {view_id} edges are missing")
        for edge in edges:
            require(isinstance(edge, dict), f"LikeC4 semantic view {view_id} edge is not an object")
            references = edge.get("relations")
            require(isinstance(references, list), f"LikeC4 semantic view {view_id} edge relations are missing")
            require(
                all(isinstance(reference, str) and reference in relation_ids for reference in references),
                f"LikeC4 semantic view {view_id} edge has an unknown relation identity",
            )
            edge["relations"] = [relation_ids[reference] for reference in references]
    return semantic


def value_summary(value: object) -> str:
    rendered = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return rendered if len(rendered) <= 240 else rendered[:237] + "..."


def model_differences(committed: object, generated: object, path: str = "$") -> list[str]:
    if type(committed) is not type(generated):
        return [f"{path}: committed {type(committed).__name__}, generated {type(generated).__name__}"]
    if isinstance(committed, dict):
        differences: list[str] = []
        committed_keys = set(committed)
        generated_keys = set(generated)
        for key in sorted(committed_keys - generated_keys):
            differences.append(f"{path}.{key}: committed-only field")
        for key in sorted(generated_keys - committed_keys):
            differences.append(f"{path}.{key}: generated-only field")
        for key in sorted(committed_keys & generated_keys):
            differences.extend(model_differences(committed[key], generated[key], f"{path}.{key}"))
        return differences
    if isinstance(committed, list):
        differences = []
        if len(committed) != len(generated):
            differences.append(f"{path}: committed length {len(committed)}, generated length {len(generated)}")
        for index, (committed_item, generated_item) in enumerate(zip(committed, generated)):
            differences.extend(model_differences(committed_item, generated_item, f"{path}[{index}]"))
        return differences
    if committed != generated:
        return [
            f"{path}: committed {value_summary(committed)}, generated {value_summary(generated)}"
        ]
    return []


def require_same_semantics(committed: object, generated: object) -> None:
    differences = model_differences(committed, generated)
    if not differences:
        return
    print("LikeC4 architecture semantic mismatch:", file=sys.stderr)
    for difference in differences[:50]:
        print(difference, file=sys.stderr)
    if len(differences) > 50:
        print(f"... {len(differences) - 50} additional differences", file=sys.stderr)
    raise SystemExit("committed LikeC4 bundle does not encode the current architecture semantics")


def write_atomically(target: Path, content: bytes) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def report(lock_sha256: str, semantic: bytes, checked: bool) -> None:
    print(
        json.dumps(
            {
                "checked": checked,
                "lockSha256": lock_sha256,
                "semanticModelBytes": len(semantic),
                "semanticModelSha256": hashlib.sha256(semantic).hexdigest(),
                "status": "passed",
            },
            sort_keys=True,
        )
    )


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify the committed bundle")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def main() -> None:
    arguments = parse_arguments()
    lock_sha256 = lock_digest()
    executable = install_tooling()

    with tempfile.TemporaryDirectory(prefix="kast-likec4-generate.") as temporary_directory:
        generated_path = Path(temporary_directory) / "likec4-views.mjs"
        computed_path = Path(temporary_directory) / "likec4-computed.json"
        generated = generate_raw(executable, generated_path)
        computed_model = export_computed_model(executable, computed_path)
        generated_model = canonical_bundle_model(generated_path)

    verify_wrapper(generated, "generated LikeC4 bundle")
    require(
        generated_model == computed_model,
        "generated LikeC4 bundle does not encode the compute-only model",
    )
    semantic = semantic_model(computed_model)
    semantic_bytes = canonical_bytes(semantic)
    model_sha256 = hashlib.sha256(semantic_bytes).hexdigest()
    expected_provenance = provenance(lock_sha256, model_sha256)

    output = arguments.output.resolve()
    if arguments.check:
        require(output.is_file(), f"committed LikeC4 bundle is missing: {output}")
        committed = output.read_bytes()
        model_provenance, committed_wrapper = provenance_parts(committed, lock_sha256)
        verify_wrapper(committed_wrapper, "committed LikeC4 bundle")
        committed_semantic = semantic_model(canonical_bundle_model(output))
        committed_semantic_sha256 = hashlib.sha256(canonical_bytes(committed_semantic)).hexdigest()
        require(
            model_provenance
            == f"// kast-likec4-model-sha256:{committed_semantic_sha256}\n".encode(),
            "committed LikeC4 bundle has stale or missing self-bound model provenance",
        )
        require_same_semantics(committed_semantic, semantic)
    else:
        write_atomically(output, expected_provenance + generated)

    report(lock_sha256, semantic_bytes, arguments.check)


if __name__ == "__main__":
    main()
