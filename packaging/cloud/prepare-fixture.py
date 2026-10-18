#!/usr/bin/env python3
"""Export a narrow public fixture from pinned Git bytes, excluding reports and oracles."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--repository", required=True, type=Path)
parser.add_argument("--revision", required=True)
parser.add_argument("--fixture", required=True, choices=("semantic-fixture", "static-callback-fixture", "immutable-callback-fixture"))
parser.add_argument("--destination", required=True, type=Path)
parser.add_argument("--receipt", required=True, type=Path)
args = parser.parse_args()
destination = args.destination.resolve()
if args.receipt.resolve().is_relative_to(destination):
    raise SystemExit("Keep the fixture receipt outside participant context")
if destination.exists() or args.receipt.exists():
    raise SystemExit("Use new fixture and receipt paths")
revision = subprocess.check_output(["git", "-C", str(args.repository), "rev-parse", args.revision + "^{commit}"], text=True).strip()
prefix = "experiments/host-observation/" + args.fixture + "/"
archive = subprocess.check_output(["git", "-C", str(args.repository), "archive", revision, prefix])
files = []
with tarfile.open(fileobj=io.BytesIO(archive)) as source:
    for member in source.getmembers():
        if member.isdir():
            continue
        if not member.isfile() or not member.name.startswith(prefix):
            raise SystemExit("Regular public fixture files required")
        relative = PurePosixPath(member.name.removeprefix(prefix))
        if relative.is_absolute() or ".." in relative.parts:
            raise SystemExit("Unsafe fixture path")
        if relative.suffix not in {".kt", ".kts", ".java", ".properties"}:
            raise SystemExit("Fixture contains a non-source file; review explicitly before export")
        stream = source.extractfile(member)
        if stream is None:
            raise SystemExit("Missing fixture bytes")
        files.append((str(relative), stream.read()))
destination.mkdir(parents=True, mode=0o700)
for name, content in files:
    target = destination / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)
args.receipt.parent.mkdir(parents=True, exist_ok=True)
args.receipt.write_text(json.dumps({"type": "SOURCE_ONLY_FIXTURE", "revision": revision,
    "fixture": args.fixture, "destination": str(destination),
    "files": [{"path": name, "sha256": hashlib.sha256(content).hexdigest()} for name, content in files]}, indent=2) + "\n")
print(json.dumps({"type": "SOURCE_ONLY_FIXTURE", "files": len(files), "receipt": str(args.receipt.resolve())}))
