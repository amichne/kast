"""Install-only MCP wheel provisioning and offline, inventory-admitted serving.

Invoke with the retained private Python using -I -S -B. --install-root names the
payload directory containing installation.json, not its managed parent. Failed
preparations retain .mcp-preparing for explicit recovery; they are never reused.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from collections.abc import Mapping
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, Protocol, TypeGuard

METADATA = Path("share/kast")
PYTHON = METADATA / "private-python/bin/python3.12"
HELPER = METADATA / "mcp_environment.py"
REQUIREMENTS = METADATA / "mcp-requirements.txt"
EXPORT_LOCK = METADATA / "mcp-requirements.txt.lock"
SCRIPTS = METADATA / "agent-tools/compositions/mcp/plugins/kast/skills/kast/scripts"
SERVER = SCRIPTS / "server.py"
SERVER_LOCK = SCRIPTS / "server.py.lock"
MAX_ENTRIES = 50_000
MAX_BYTES = 1_073_741_824
MAX_RECORD = 67_108_864


class Failure(StrEnum):
    INSTALLATION = "MCP_INSTALLATION_REJECTED"
    STATE = "MCP_STATE_REJECTED"
    MISSING = "MCP_ENVIRONMENT_MISSING"
    PARTIAL = "MCP_PREPARATION_INCOMPLETE"
    INSTALL = "MCP_WHEEL_INSTALL_FAILED"
    IMPORT = "MCP_IMPORT_CHECK_FAILED"
    TIMEOUT = "MCP_PROCESS_TIMEOUT"
    FILESYSTEM = "MCP_FILESYSTEM_REJECTED"
    PROCESS = "MCP_PROCESS_UNAVAILABLE"
    CONCURRENT = "MCP_CONCURRENT_CHANGE"


@dataclass(frozen=True)
class Rejected:
    failure: Failure
    status: Literal["MCP_REJECTED"] = "MCP_REJECTED"


@dataclass(frozen=True)
class Prepared:
    installationRoot: str
    environmentRoot: str
    disposition: Literal["CREATED", "REUSED"]
    status: Literal["MCP_PREPARED"] = "MCP_PREPARED"


@dataclass(frozen=True)
class File:
    path: str
    sha256: str
    mode: int


@dataclass(frozen=True)
class Inventory:
    files: tuple[File, ...]
    directories: tuple[str, ...]


@dataclass(frozen=True)
class RequirementsLock:
    scriptSha256: str
    lockSha256: str
    requirementsSha256: str
    schemaVersion: int = 1


@dataclass(frozen=True)
class Identity:
    device: int
    inode: int

    @staticmethod
    def observe(path: Path) -> Identity:
        observed = path.lstat()
        return Identity(observed.st_dev, observed.st_ino)


@dataclass(frozen=True)
class Installation:
    root: Path
    identity: Identity
    manifestSha256: str
    export: RequirementsLock


@dataclass(frozen=True)
class Receipt:
    installationRoot: str
    manifestSha256: str
    export: RequirementsLock
    inventory: Inventory
    schemaVersion: int = 1


@dataclass(frozen=True)
class Environment:
    installation: Installation
    root: Path


@dataclass(frozen=True)
class Command:
    arguments: tuple[str, ...]
    environment: Mapping[str, str]


@dataclass(frozen=True)
class Exited:
    code: int


@dataclass(frozen=True)
class Published:
    root: Path


class Runner(Protocol):
    def __call__(self, command: Command, /) -> Exited | Rejected: ...


def run(command: Command) -> Exited | Rejected:
    try:
        # No captured/unbounded output, credentials, URLs, or source payloads in
        # diagnostics. The finite stage failure is the public evidence boundary.
        child = subprocess.run(
            command.arguments,
            env=command.environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=300,
            check=False,
        )
        return Exited(child.returncode)
    except subprocess.TimeoutExpired:
        return Rejected(Failure.TIMEOUT)
    except OSError:
        return Rejected(Failure.PROCESS)


def mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def sequence(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def encoded(value: Receipt | Prepared | Rejected | RequirementsLock) -> bytes:
    return (json.dumps(asdict(value), sort_keys=True, separators=(",", ":")) + "\n").encode()


def owned(path: Path, *, directory: bool = False) -> bool:
    observed = path.lstat()
    return (
        path.is_absolute()
        and path.resolve(strict=True) == path
        and observed.st_uid == os.getuid()
        and not observed.st_mode & 0o022
        and (
            stat.S_ISDIR(observed.st_mode)
            if directory
            else stat.S_ISREG(observed.st_mode) and observed.st_nlink == 1
        )
    )


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()


def record_bytes(path: Path) -> bytes | Rejected:
    if not owned(path):
        return Rejected(Failure.STATE)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        observed = os.fstat(stream.fileno())
        if not stat.S_ISREG(observed.st_mode) or observed.st_size > MAX_RECORD:
            return Rejected(Failure.STATE)
        raw = stream.read(MAX_RECORD + 1)
    return raw if len(raw) <= MAX_RECORD else Rejected(Failure.STATE)


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    document = dict(pairs)
    if len(document) != len(pairs):
        raise ValueError("duplicate JSON field")
    return document


def read(path: Path) -> object:
    raw = record_bytes(path)
    return raw if isinstance(raw, Rejected) else json.loads(raw, object_pairs_hook=unique_object)


def file_record(value: object) -> File | Rejected:
    if not mapping(value) or set(value) != {"path", "sha256", "mode"}:
        return Rejected(Failure.INSTALLATION)
    name, sha, mode = value["path"], value["sha256"], value["mode"]
    if (
        not isinstance(name, str)
        or not name
        or Path(name).is_absolute()
        or ".." in Path(name).parts
        or Path(name).as_posix() != name
        or not isinstance(sha, str)
        or re.fullmatch(r"sha256:[a-f0-9]{64}", sha) is None
        or type(mode) is not int
        or not 0 <= mode <= 0o777
    ):
        return Rejected(Failure.INSTALLATION)
    return File(name, sha, mode)


def inventory(root: Path) -> Inventory | Rejected:
    files: list[File] = []
    directories: list[str] = []
    pending = [root]
    count = total = 0
    while pending:
        parent = pending.pop()
        if not owned(parent, directory=True):
            return Rejected(Failure.STATE)
        for child in parent.iterdir():
            count += 1
            if count > MAX_ENTRIES:
                return Rejected(Failure.STATE)
            name = child.relative_to(root).as_posix()
            if child.is_dir() and not child.is_symlink():
                pending.append(child)
                directories.append(name)
            elif owned(child):
                observed = child.stat()
                total += observed.st_size
                if total > MAX_BYTES:
                    return Rejected(Failure.STATE)
                files.append(File(name, digest(child), stat.S_IMODE(observed.st_mode)))
            else:
                return Rejected(Failure.STATE)
    return Inventory(tuple(sorted(files, key=lambda item: item.path)), tuple(sorted(directories)))


def admit_installation(root: Path) -> Installation | Rejected:
    """Admit the original inventory before running Python/pip or mutating state.

    The inventory is the local installation authority, not a signed trust root.
    The caller must admit the helper/interpreter before launching this helper.
    """
    try:
        if not owned(root, directory=True):
            return Rejected(Failure.INSTALLATION)
        document = read(root / "installation.json")
        if (
            not mapping(document)
            or document.get("schemaVersion") != 3
            or document.get("installationRoot") != str(root)
            or document.get("stateRoot") != str(root / "state")
            or not sequence(entries := document.get("payloadFiles"))
            or not 0 < len(entries) <= MAX_ENTRIES
        ):
            return Rejected(Failure.INSTALLATION)
        expected: dict[str, File] = {}
        for value in entries:
            entry = file_record(value)
            if isinstance(entry, Rejected) or entry.path in expected:
                return Rejected(Failure.INSTALLATION)
            expected[entry.path] = entry
        # Verify every interpreter and canonical server import, including absent
        # and extra files. Unrelated native payloads remain their owner's concern.
        selected: set[str] = set()
        for subtree in (METADATA / "private-python", SCRIPTS):
            observed = inventory(root / subtree)
            if isinstance(observed, Rejected):
                return Rejected(Failure.INSTALLATION)
            for entry in observed.files:
                name = (subtree / entry.path).as_posix()
                if expected.get(name) != File(name, entry.sha256, entry.mode):
                    return Rejected(Failure.INSTALLATION)
                selected.add(name)
            required = {name for name in expected if Path(name).is_relative_to(subtree)}
            if required != {name for name in selected if Path(name).is_relative_to(subtree)}:
                return Rejected(Failure.INSTALLATION)
        for relative in (HELPER, REQUIREMENTS, EXPORT_LOCK):
            path = root / relative
            if (
                not owned(path)
                or path.stat().st_size > MAX_RECORD
                or expected.get(relative.as_posix())
                != File(relative.as_posix(), digest(path), stat.S_IMODE(path.stat().st_mode))
            ):
                return Rejected(Failure.INSTALLATION)
        if any(path.as_posix() not in selected for path in (PYTHON, SERVER, SERVER_LOCK)):
            return Rejected(Failure.INSTALLATION)
        export = RequirementsLock(
            digest(root / SERVER), digest(root / SERVER_LOCK), digest(root / REQUIREMENTS)
        )
        if record_bytes(root / EXPORT_LOCK) != encoded(export):
            return Rejected(Failure.INSTALLATION)
        return Installation(
            root, Identity.observe(root), digest(root / "installation.json"), export
        )
    except (OSError, ValueError):
        return Rejected(Failure.INSTALLATION)


def admit_environment(installation: Installation) -> Environment | Rejected:
    state = installation.root / "state"
    destination = state / "mcp"
    try:
        if os.path.lexists(state) and not owned(state, directory=True):
            return Rejected(Failure.STATE)
        if os.path.lexists(state / ".mcp-preparing"):
            return Rejected(Failure.PARTIAL)
        if not os.path.lexists(destination):
            return Rejected(Failure.MISSING)
        if not owned(destination, directory=True) or {p.name for p in destination.iterdir()} != {
            "site-packages",
            "receipt.json",
        }:
            return Rejected(Failure.STATE)
        observed = inventory(destination / "site-packages")
        if isinstance(observed, Rejected) or not observed.files:
            return Rejected(Failure.STATE)
        receipt = Receipt(
            str(installation.root), installation.manifestSha256, installation.export, observed
        )
        # Compare the complete independent observation, not a same-code decode
        # round trip. These helper-owned records retain their canonical encoding;
        # unknown fields, incomplete receipts, and JSON type changes reject.
        if record_bytes(destination / "receipt.json") != encoded(receipt):
            return Rejected(Failure.STATE)
        return Environment(installation, destination)
    except (OSError, ValueError):
        return Rejected(Failure.STATE)


def publish(stage: Path, destination: Path) -> Published | Rejected:
    """Native no-replace rename; even a foreign empty directory must survive."""
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == "darwin":
        rename = library.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(stage), os.fsencode(destination), 4)
    elif sys.platform == "linux":
        rename = library.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        result = rename(-100, os.fsencode(stage), -100, os.fsencode(destination), 1)
    else:
        return Rejected(Failure.PROCESS)
    return Rejected(Failure.CONCURRENT) if result != 0 else Published(destination)


def prepare(root: Path, runner: Runner = run) -> Prepared | Rejected:
    installation = admit_installation(root)
    if isinstance(installation, Rejected):
        return installation
    current = admit_environment(installation)
    if isinstance(current, Environment):
        return Prepared(str(root), str(current.root), "REUSED")
    if current.failure != Failure.MISSING:
        return current
    state, stage, destination = root / "state", root / "state/.mcp-preparing", root / "state/mcp"
    try:
        state.mkdir(mode=0o700, exist_ok=True)
        if not owned(state, directory=True):
            return Rejected(Failure.STATE)
        stage.mkdir(mode=0o700)
        state_identity, stage_identity = Identity.observe(state), Identity.observe(stage)
        site = stage / "site-packages"
        environment = {
            "PATH": "",
            "HOME": str(stage),
            "TMPDIR": str(stage),
            "PIP_CONFIG_FILE": os.devnull,
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        installed = runner(
            Command(
                (
                    str(root / PYTHON),
                    "-I",
                    "-B",
                    "-m",
                    "pip",
                    "--isolated",
                    "--disable-pip-version-check",
                    "install",
                    "--require-hashes",
                    "--no-deps",
                    "--only-binary=:all:",
                    "--no-compile",
                    "--no-cache-dir",
                    "--ignore-installed",
                    "--no-input",
                    "--quiet",
                    "--retries",
                    "0",
                    "--timeout",
                    "30",
                    "--index-url",
                    "https://pypi.org/simple",
                    "--target",
                    str(site),
                    "--requirement",
                    str(root / REQUIREMENTS),
                ),
                environment,
            )
        )
        if isinstance(installed, Rejected):
            return installed
        if installed.code != 0:
            return Rejected(Failure.INSTALL)
        if Identity.observe(state) != state_identity or Identity.observe(stage) != stage_identity:
            return Rejected(Failure.CONCURRENT)
        observed = inventory(site)
        if isinstance(observed, Rejected) or not observed.files:
            return Rejected(Failure.STATE)
        checked = runner(
            Command(
                (
                    str(root / PYTHON),
                    "-I",
                    "-S",
                    "-B",
                    "-c",
                    "import sys; sys.path.insert(0, sys.argv[1]); "
                    "import anyio, jsonschema, mcp, pydantic",
                    str(site),
                ),
                environment,
            )
        )
        if isinstance(checked, Rejected):
            return checked
        if checked.code != 0:
            return Rejected(Failure.IMPORT)
        if (
            Identity.observe(state) != state_identity
            or Identity.observe(stage) != stage_identity
            or admit_installation(root) != installation
            or inventory(site) != observed
        ):
            return Rejected(Failure.CONCURRENT)
        if {path.name for path in stage.iterdir()} != {"site-packages"}:
            return Rejected(Failure.STATE)
        receipt = Receipt(str(root), installation.manifestSha256, installation.export, observed)
        with (stage / "receipt.json").open("xb") as stream:
            _ = stream.write(encoded(receipt))
            stream.flush()
            os.fsync(stream.fileno())
        rejected = publish(stage, destination)
        if isinstance(rejected, Rejected):
            return rejected
        verified = admit_environment(installation)
        if isinstance(verified, Rejected):
            return verified
        return Prepared(str(root), str(destination), "CREATED")
    except FileExistsError:
        return Rejected(Failure.PARTIAL)
    except OSError:
        return Rejected(Failure.FILESYSTEM)


def serve_command(root: Path, environment: Mapping[str, str]) -> Command | Rejected:
    installation = admit_installation(root)
    if isinstance(installation, Rejected):
        return installation
    admitted = admit_environment(installation)
    if isinstance(admitted, Rejected):
        return admitted
    return Command(
        (
            str(root / PYTHON),
            "-I",
            "-S",
            "-B",
            "-c",
            "import runpy, sys; server, site = sys.argv[1:]; "
            "sys.path[:0] = [str(__import__('pathlib').Path(server).parent), site]; "
            "sys.argv = [server]; runpy.run_path(server, run_name='__main__')",
            str(root / SERVER),
            str(admitted.root / "site-packages"),
        ),
        dict(environment),
    )


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("mode", choices=("prepare", "serve"))
    _ = parser.add_argument("--install-root", required=True, type=Path)
    args = parser.parse_args(arguments)
    root: Path = args.install_root
    if args.mode == "prepare":
        result = prepare(root)
        print(encoded(result).decode(), end="")
        return 1 if isinstance(result, Rejected) else 0
    command = serve_command(root, os.environ)
    if isinstance(command, Rejected):
        print(encoded(command).decode(), end="", file=sys.stderr)
        return 1
    try:
        os.execve(command.arguments[0], command.arguments, command.environment)
    except OSError:
        print(encoded(Rejected(Failure.PROCESS)).decode(), end="", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
