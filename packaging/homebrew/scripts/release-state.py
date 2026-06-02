#!/usr/bin/env python3
import json
import os
import re
import sys
from pathlib import Path


RELEASE_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


def repo_root() -> Path:
    return Path(os.environ.get("KAST_TAP_ROOT", Path(__file__).resolve().parents[1]))


def normalize_release(raw: str) -> str:
    match = RELEASE_RE.fullmatch(raw.strip())
    if match is None:
        fail(f"release must be a stable semver tag like v1.2.3; got {raw!r}")
    return f"v{match.group(1)}.{match.group(2)}.{match.group(3)}"


def release_tuple(release: str) -> tuple[int, int, int]:
    match = RELEASE_RE.fullmatch(release)
    if match is None:
        fail(f"release must be a stable semver tag like v1.2.3; got {release!r}")
    return tuple(int(part) for part in match.groups())


def state_path() -> Path:
    return repo_root() / "release-state.json"


def load_state() -> dict[str, object]:
    path = state_path()
    if not path.is_file():
        fail(f"{path} is missing")

    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"{path} is invalid JSON: {error}")

    if state.get("schema_version") != 1:
        fail("release-state.json schema_version must be 1")
    source_index_schema = state.get("source_index_schema_version")
    if not isinstance(source_index_schema, int) or source_index_schema <= 0:
        fail("release-state.json source_index_schema_version must be a positive integer")
    current = state.get("current_release")
    if not isinstance(current, str):
        fail("release-state.json current_release must be a string")
    state["current_release"] = normalize_release(current)
    return state


def write_state(release: str) -> None:
    state = load_state()
    state["current_release"] = normalize_release(release)
    state_path().write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")


def next_patch(current: str) -> str:
    major, minor, patch = release_tuple(current)
    return f"v{major}.{minor}.{patch + 1}"


def usage() -> None:
    print(
        "usage: release-state.py current|current-version|next-patch [release]|set <release>|require-after-current <release>",
        file=sys.stderr,
    )


def main() -> None:
    if len(sys.argv) < 2:
        usage()
        raise SystemExit(2)

    command = sys.argv[1]
    state = load_state()
    current = str(state["current_release"])

    if command == "current":
        print(current)
    elif command == "current-version":
        print(current.removeprefix("v"))
    elif command == "next-patch":
        if len(sys.argv) > 3:
            usage()
            raise SystemExit(2)
        base = normalize_release(sys.argv[2]) if len(sys.argv) == 3 else current
        print(next_patch(base))
    elif command == "set":
        if len(sys.argv) != 3:
            usage()
            raise SystemExit(2)
        write_state(sys.argv[2])
    elif command == "require-after-current":
        if len(sys.argv) != 3:
            usage()
            raise SystemExit(2)
        requested = normalize_release(sys.argv[2])
        if release_tuple(requested) <= release_tuple(current):
            fail(f"requested release {requested} must be after current release {current}")
        print(requested)
    else:
        usage()
        raise SystemExit(2)


if __name__ == "__main__":
    main()
