#!/usr/bin/env python3
"""Generate or verify the public LikeC4 web-component bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
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


def report(lock_sha256: str, model: bytes, checked: bool) -> None:
    print(
        json.dumps(
            {
                "checked": checked,
                "lockSha256": lock_sha256,
                "modelBytes": len(model),
                "modelSha256": hashlib.sha256(model).hexdigest(),
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
    model = canonical_bytes(computed_model)
    model_sha256 = hashlib.sha256(model).hexdigest()
    expected_provenance = provenance(lock_sha256, model_sha256)

    output = arguments.output.resolve()
    if arguments.check:
        require(output.is_file(), f"committed LikeC4 bundle is missing: {output}")
        committed = output.read_bytes()
        require(
            committed.startswith(expected_provenance),
            "committed LikeC4 bundle has stale or missing lockfile or model provenance",
        )
        committed_wrapper = committed[len(expected_provenance) :]
        verify_wrapper(committed_wrapper, "committed LikeC4 bundle")
        require(
            canonical_bundle_model(output) == computed_model,
            "committed LikeC4 bundle does not encode the current compute-only model",
        )
    else:
        write_atomically(output, expected_provenance + generated)

    report(lock_sha256, model, arguments.check)


if __name__ == "__main__":
    main()
