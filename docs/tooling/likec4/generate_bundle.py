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
MODEL_START = b"{_stage:`layouted`,projectId:`kast-public-architecture`"
MODEL_END = b"manualLayouts:{}}"
WRAPPER_START = b"var LikeC4Views=(function("
WRAPPER_END = b"})({});"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def lock_digest() -> str:
    require(LOCKFILE.is_file(), f"LikeC4 lockfile is missing: {LOCKFILE}")
    return hashlib.sha256(LOCKFILE.read_bytes()).hexdigest()


def provenance(digest: str) -> bytes:
    return f"// kast-likec4-lock-sha256:{digest}\n".encode()


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


def model_payload(bundle: bytes, label: str) -> bytes:
    require(bundle.count(MODEL_START) == 1, f"{label} has an invalid model start boundary")
    start = bundle.index(MODEL_START)
    require(bundle.count(MODEL_END, start) == 1, f"{label} has an invalid model end boundary")
    end = bundle.index(MODEL_END, start) + len(MODEL_END)
    return bundle[start:end]


def verify_wrapper(bundle: bytes, label: str) -> None:
    require(bundle.startswith(WRAPPER_START), f"{label} has an invalid module wrapper")
    require(bundle.rstrip().endswith(WRAPPER_END), f"{label} has an invalid module ending")
    require(bundle.count(b"customElements.define(") == 1, f"{label} has an invalid custom element registration")
    require(b"LikeC4View" in bundle, f"{label} does not export LikeC4View")
    model_payload(bundle, label)


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


def report(digest: str, payload: bytes, checked: bool) -> None:
    print(
        json.dumps(
            {
                "checked": checked,
                "lockSha256": digest,
                "payloadBytes": len(payload),
                "payloadSha256": hashlib.sha256(payload).hexdigest(),
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
    digest = lock_digest()
    expected_provenance = provenance(digest)
    executable = install_tooling()

    with tempfile.TemporaryDirectory(prefix="kast-likec4-generate.") as temporary_directory:
        generated_path = Path(temporary_directory) / "likec4-views.mjs"
        generated = generate_raw(executable, generated_path)

    verify_wrapper(generated, "generated LikeC4 bundle")
    generated_payload = model_payload(generated, "generated LikeC4 bundle")

    output = arguments.output.resolve()
    if arguments.check:
        require(output.is_file(), f"committed LikeC4 bundle is missing: {output}")
        committed = output.read_bytes()
        require(
            committed.startswith(expected_provenance),
            "committed LikeC4 bundle has stale or missing lockfile provenance",
        )
        committed_wrapper = committed[len(expected_provenance) :]
        verify_wrapper(committed_wrapper, "committed LikeC4 bundle")
        require(
            model_payload(committed_wrapper, "committed LikeC4 bundle") == generated_payload,
            "committed LikeC4 model payload is stale",
        )
    else:
        write_atomically(output, expected_provenance + generated)

    report(digest, generated_payload, arguments.check)


if __name__ == "__main__":
    main()
