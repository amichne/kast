"""Read-only navigation over declared surfaces; not an authorization sandbox."""

from __future__ import annotations

import argparse
import errno
import json
import os
import shutil
import stat
import subprocess
import sys
from collections.abc import Iterator
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path, PurePosixPath
from typing import Literal, TypeGuard

MANIFEST = ".agents/repository-surfaces.json"
MAX_READ = 1024 * 1024
MAX_RESULTS = 1000
BLOCKED_NAMES = frozenset(
    {
        "node_modules",
        "__pycache__",
        "build",
        "dist",
        "target",
        "cache",
        "caches",
        "venv",
        "site-packages",
        "secrets",
        "credentials",
        "tokens",
    }
)
BLOCKED_SUFFIXES = frozenset(
    {
        ".pem",
        ".key",
        ".p12",
        ".pfx",
        ".sqlite",
        ".db",
        ".log",
        ".pyc",
    }
)


class Scope(StrEnum):
    PUBLIC = "public"
    PACKAGING = "packaging"
    DOCUMENTATION = "documentation"
    KNOWLEDGE = "knowledge"


class Kind(StrEnum):
    FILE = "file"
    TREE = "tree"


class Failure(StrEnum):
    INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    INVALID_MANIFEST = "INVALID_MANIFEST"
    UNKNOWN_SCOPE = "UNKNOWN_SCOPE"
    UNSAFE_PATH = "UNSAFE_PATH"
    ACCESS_DENIED = "ACCESS_DENIED"
    SYMLINK = "SYMLINK"
    NOT_FOUND = "NOT_FOUND"
    NOT_FILE = "NOT_FILE"
    READ_LIMIT = "READ_LIMIT"
    NOT_TEXT = "NOT_TEXT"
    IGNORE_CHECK_FAILED = "IGNORE_CHECK_FAILED"
    IO = "IO"


@dataclass(frozen=True)
class Surface:
    scope: Scope
    path: PurePosixPath
    kind: Kind


@dataclass(frozen=True)
class Listing:
    paths: tuple[str, ...]
    truncated: bool
    outcome: Literal["listed"] = "listed"


@dataclass(frozen=True)
class Document:
    path: str
    scope: Scope
    content: str
    bytes: int
    outcome: Literal["read"] = "read"


@dataclass(frozen=True)
class Rejected:
    reason: Failure
    outcome: Literal["rejected"] = "rejected"


type Result = Listing | Document | Rejected


class Options(argparse.Namespace):
    action: str
    path: str | None
    internal: bool
    scope: str | None
    limit: int
    max_bytes: int


def parser() -> argparse.ArgumentParser:
    command = argparse.ArgumentParser(
        prog="kast-repo",
        exit_on_error=False,
        allow_abbrev=False,
        description="Read declared repository surfaces without modifying them.",
        epilog="OpenWiki stays at openwiki/. --internal includes visible native bundle files; "
        "--scope knowledge narrows to them. Hidden native state is never read. "
        "This is discovery policy, not access control for other tools. Requires Git.",
    )
    command.add_argument("action", nargs="?", default="list", help="list (alias discover) or read")
    command.add_argument(
        "path", nargs="?", help="canonical repository-relative path or list prefix"
    )
    command.add_argument("--internal", action="store_true", help="include declared private scopes")
    command.add_argument("--scope", help="public, packaging, documentation, or knowledge")
    command.add_argument("--limit", type=int, default=100, help="list result limit (1–1000)")
    command.add_argument(
        "--max-bytes", type=int, default=65536, help="UTF-8 read limit (1–1048576)"
    )
    return command


def relative(value: str) -> PurePosixPath | Failure:
    parts = value.split("/")
    if (
        not value
        or "\\" in value
        or not value.isprintable()
        or any(part in {"", ".", ".."} for part in parts)
        or PurePosixPath(value).is_absolute()
    ):
        return Failure.UNSAFE_PATH
    return PurePosixPath(value)


def permitted(path: PurePosixPath) -> bool:
    return all(
        part.isprintable()
        and not part.startswith(".")
        and part not in BLOCKED_NAMES
        and Path(part).suffix.lower() not in BLOCKED_SUFFIXES
        and part.lower().split(".", 1)[0] not in {"secrets", "secret", "credentials", "tokens"}
        for part in path.parts
    )


def related(left: PurePosixPath, right: PurePosixPath) -> bool:
    return left == right or left in right.parents or right in left.parents


def open_source(root: Path, path: PurePosixPath) -> int | Failure:
    """Anchor every component to an open directory; never follow a symlink."""
    descriptor: int | None = None
    try:
        descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for index, part in enumerate(path.parts):
            entry = os.stat(part, dir_fd=descriptor, follow_symlinks=False)
            if stat.S_ISLNK(entry.st_mode):
                return Failure.SYMLINK
            flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            if index < len(path.parts) - 1:
                flags |= os.O_DIRECTORY
            child = os.open(part, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        admitted = descriptor
        descriptor = None
        return admitted
    except OSError as error:
        if error.errno == errno.ENOENT:
            return Failure.NOT_FOUND
        if error.errno == errno.ELOOP:
            return Failure.SYMLINK
        if error.errno == errno.ENOTDIR:
            return Failure.NOT_FILE
        return Failure.IO
    finally:
        if descriptor is not None:
            os.close(descriptor)


def read_bytes(root: Path, path: PurePosixPath, limit: int) -> bytes | Failure:
    descriptor = open_source(root, path)
    if isinstance(descriptor, Failure):
        return descriptor
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            return Failure.NOT_FILE
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            content = source.read(limit + 1)
        return Failure.READ_LIMIT if len(content) > limit else content
    except OSError:
        return Failure.IO
    finally:
        os.close(descriptor)


def mapping(value: object) -> TypeGuard[dict[str, object]]:
    return isinstance(value, dict)


def sequence(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate manifest key")
        value[key] = item
    return value


def surfaces(root: Path) -> tuple[Surface, ...] | Failure:
    content = read_bytes(root, PurePosixPath(MANIFEST), 65536)
    if isinstance(content, Failure):
        return content if content is Failure.SYMLINK else Failure.INVALID_MANIFEST
    try:
        value: object = json.loads(content, object_pairs_hook=unique_object)
        if (
            not mapping(value)
            or set(value) != {"schemaVersion", "surfaces"}
            or type(value["schemaVersion"]) is not int
            or value["schemaVersion"] != 1
        ):
            return Failure.INVALID_MANIFEST
        entries = value["surfaces"]
        if not sequence(entries) or not 1 <= len(entries) <= 64:
            return Failure.INVALID_MANIFEST
        admitted: list[Surface] = []
        for entry in entries:
            if not mapping(entry) or set(entry) != {"scope", "path", "kind"}:
                return Failure.INVALID_MANIFEST
            scope, raw_path, kind = entry["scope"], entry["path"], entry["kind"]
            if (
                not isinstance(scope, str)
                or not isinstance(raw_path, str)
                or not isinstance(kind, str)
            ):
                return Failure.INVALID_MANIFEST
            path = relative(raw_path)
            if isinstance(path, Failure) or not permitted(path):
                return Failure.INVALID_MANIFEST
            if any(related(path, prior.path) for prior in admitted):
                return Failure.INVALID_MANIFEST
            admitted.append(Surface(Scope(scope), path, Kind(kind)))
        return tuple(sorted(admitted, key=lambda surface: str(surface.path)))
    except (ValueError, UnicodeDecodeError, RecursionError):
        return Failure.INVALID_MANIFEST


def ignored(root: Path, path: PurePosixPath) -> bool | Failure:
    git = shutil.which("git")
    if git is None:
        return Failure.IGNORE_CHECK_FAILED
    try:
        result = subprocess.run(
            [git, "check-ignore", "--no-index", "--quiet", "--", str(path)],
            cwd=root,
            env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        return Failure.IGNORE_CHECK_FAILED
    if result.returncode not in (0, 1):
        # Git rejects paths beyond a symlink. Preserve that distinction without
        # reading bytes or treating an unknown ignore outcome as permission.
        descriptor = open_source(root, path)
        if descriptor is Failure.SYMLINK:
            return descriptor
        if isinstance(descriptor, int):
            os.close(descriptor)
        return Failure.IGNORE_CHECK_FAILED
    return result.returncode == 0


def walk(
    root: Path,
    path: PurePosixPath,
    prefix: PurePosixPath | None,
    kind: Kind | None = None,
) -> Iterator[str | Failure]:
    if not permitted(path) or (prefix is not None and not related(path, prefix)):
        return
    excluded = ignored(root, path)
    if isinstance(excluded, Failure):
        yield excluded
        return
    if excluded:
        return
    descriptor = open_source(root, path)
    if isinstance(descriptor, Failure):
        yield descriptor
        return
    try:
        mode = os.fstat(descriptor).st_mode
        if (kind is Kind.FILE and not stat.S_ISREG(mode)) or (
            kind is Kind.TREE and not stat.S_ISDIR(mode)
        ):
            yield Failure.NOT_FILE
        elif stat.S_ISREG(mode):
            yield str(path)
        elif stat.S_ISDIR(mode):
            with os.scandir(descriptor) as directory:
                names = sorted(
                    entry.name
                    for entry in directory
                    if permitted(path / entry.name)
                    and (prefix is None or related(path / entry.name, prefix))
                )
            for name in names:
                yield from walk(root, path / name, prefix)
        else:
            yield Failure.UNSAFE_PATH
    except OSError:
        yield Failure.IO
    finally:
        os.close(descriptor)


def navigate(root: Path, arguments: list[str]) -> Result:
    try:
        options, unknown = parser().parse_known_args(arguments, namespace=Options())
    except argparse.ArgumentError:
        return Rejected(Failure.INVALID_ARGUMENTS)
    if (
        unknown
        or options.action not in {"list", "discover", "read"}
        or not 1 <= options.limit <= MAX_RESULTS
        or not 1 <= options.max_bytes <= MAX_READ
        or (options.action == "read" and options.path is None)
    ):
        return Rejected(Failure.INVALID_ARGUMENTS)
    try:
        scope = Scope(options.scope) if options.scope is not None else None
    except ValueError:
        return Rejected(Failure.UNKNOWN_SCOPE)
    if scope is not None and scope is not Scope.PUBLIC and not options.internal:
        return Rejected(Failure.ACCESS_DENIED)
    path = relative(options.path) if options.path is not None else None
    if isinstance(path, Failure):
        return Rejected(path)
    if path is not None and not permitted(path):
        return Rejected(Failure.ACCESS_DENIED)
    inventory = surfaces(root)
    if isinstance(inventory, Failure):
        return Rejected(inventory)
    selected = tuple(
        surface
        for surface in inventory
        if (options.internal or surface.scope is Scope.PUBLIC)
        and (scope is None or surface.scope is scope)
        and (path is None or related(path, surface.path))
    )
    if path is not None and not selected:
        return Rejected(Failure.ACCESS_DENIED)
    if options.action == "read" and path is not None:
        owner = next(
            (
                surface
                for surface in selected
                if (
                    path == surface.path
                    or (surface.kind is Kind.TREE and surface.path in path.parents)
                )
            ),
            None,
        )
        if owner is None:
            return Rejected(Failure.ACCESS_DENIED)
        excluded = ignored(root, path)
        if isinstance(excluded, Failure):
            return Rejected(excluded)
        if excluded:
            return Rejected(Failure.ACCESS_DENIED)
        content = read_bytes(root, path, options.max_bytes)
        if isinstance(content, Failure):
            return Rejected(content)
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError:
            return Rejected(Failure.NOT_TEXT)
        if "\x00" in text:
            return Rejected(Failure.NOT_TEXT)
        return Document(str(path), owner.scope, text, len(content))
    paths: list[str] = []
    for surface in selected:
        for item in walk(root, surface.path, path, surface.kind):
            if isinstance(item, Failure):
                return Rejected(item)
            paths.append(item)
            if len(paths) > options.limit:
                return Listing(tuple(sorted(paths[:-1])), True)
    return Listing(tuple(sorted(paths)), False)


def main(arguments: list[str], root: Path | None = None) -> int:
    if "--help" in arguments or "-h" in arguments:
        parser().print_help()
        return 0
    result = navigate(root if root is not None else Path(__file__).resolve().parents[2], arguments)
    print(json.dumps(asdict(result), ensure_ascii=False))
    return 1 if isinstance(result, Rejected) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
