#!/usr/bin/env python3
"""Own and stop only processes created by the disposable macOS smoke."""

from __future__ import annotations

import ctypes
import json
import os
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

SCHEMA_VERSION = 3
MARKER = ".kast-foreground-worktree-smoke"
PROC_PIDTBSDINFO = 3
FIXTURE_ROLE_TOKENS = (
    "--indexer-storage-root=",
    "-Didea.config.path=",
    "GradleDaemon",
    "KotlinCompileDaemon",
    "KastIndexer",
    "com.intellij.",
    "org.gradle.",
)


class ProcBsdInfo(ctypes.Structure):
    """Darwin's proc_bsdinfo, including its microsecond process birth time."""
    _fields_ = [
        ("flags", ctypes.c_uint32), ("status", ctypes.c_uint32),
        ("xstatus", ctypes.c_uint32), ("pid", ctypes.c_uint32),
        ("ppid", ctypes.c_uint32), ("uid", ctypes.c_uint32),
        ("gid", ctypes.c_uint32), ("ruid", ctypes.c_uint32),
        ("rgid", ctypes.c_uint32), ("svuid", ctypes.c_uint32),
        ("svgid", ctypes.c_uint32), ("reserved", ctypes.c_uint32),
        ("command", ctypes.c_char * 16), ("name", ctypes.c_char * 32),
        ("files", ctypes.c_uint32), ("process_group", ctypes.c_uint32),
        ("job_control", ctypes.c_uint32), ("terminal", ctypes.c_uint32),
        ("terminal_group", ctypes.c_uint32), ("nice", ctypes.c_int32),
        ("start_seconds", ctypes.c_uint64),
        ("start_microseconds", ctypes.c_uint64),
    ]


LIBPROC = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
LIBPROC.proc_pidinfo.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64,
                                 ctypes.c_void_p, ctypes.c_int]
LIBPROC.proc_pidinfo.restype = ctypes.c_int


@dataclass(frozen=True)
class KernelIdentity:
    pid: int
    parent_pid: int
    uid: int
    birth: str


def kernel_identity(pid: int) -> KernelIdentity | None:
    info = ProcBsdInfo()
    size = ctypes.sizeof(info)
    read = LIBPROC.proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, ctypes.byref(info), size)
    if read != size or info.pid != pid:
        return None
    birth = f"{info.start_seconds}:{info.start_microseconds:06d}"
    return KernelIdentity(info.pid, info.ppid, info.uid, birth)


@dataclass(frozen=True)
class Process:
    pid: int
    parent_pid: int
    uid: int
    state: str
    started: str
    command: str

    @property
    def running(self) -> bool:
        return not self.state.startswith("Z")


def list_process_ids() -> list[int]:
    output = subprocess.check_output(["ps", "-ax", "-o", "pid="], text=True)
    return [int(value) for value in output.split()]


def kernel_identities() -> dict[int, KernelIdentity]:
    return {pid: identity for pid in list_process_ids() if (identity := kernel_identity(pid)) is not None}


def processes() -> dict[int, Process]:
    before = kernel_identities()
    output = subprocess.check_output(
        [
            "ps",
            "-axww",
            "-o",
            "pid=",
            "-o",
            "ppid=",
            "-o",
            "uid=",
            "-o",
            "stat=",
            "-o",
            "command=",
        ],
        text=True,
    )
    result: dict[int, Process] = {}
    for line in output.splitlines():
        fields = line.strip().split(None, 4)
        if len(fields) != 5:
            continue
        pid, parent_pid, uid = (int(value) for value in fields[:3])
        identity = before.get(pid)
        if (
            not identity
            or identity != kernel_identity(pid)
            or (parent_pid, uid) != (identity.parent_pid, identity.uid)
        ):
            continue
        result[pid] = Process(
            pid=pid,
            parent_pid=parent_pid,
            uid=uid,
            state=fields[3],
            started=identity.birth,
            command=fields[4],
        )
    return result


def state_path(argument: str) -> Path:
    return Path(argument).resolve()


def fixture_root(document: dict, path: Path) -> Path:
    root = Path(document["scratchRoot"]).resolve()
    if path.parent != root or not (root / MARKER).is_file():
        raise SystemExit("fixture process state is outside its marked scratch root")
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise SystemExit("unsupported fixture process-state schema")
    if document.get("ownerUid") != os.getuid():
        raise SystemExit("fixture process state belongs to another user")
    return root


def load(path: Path) -> tuple[dict, Path]:
    with path.open(encoding="utf-8") as source:
        document = json.load(source)
    return document, fixture_root(document, path)


def save(path: Path, document: dict) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temporary.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def initialize(root_argument: str, state_argument: str) -> None:
    root = Path(root_argument).resolve()
    path = state_path(state_argument)
    if path.parent != root or not (root / MARKER).is_file():
        raise SystemExit("fixture process state requires an exact marked scratch root")
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "scratchRoot": str(root),
        "ownerUid": os.getuid(),
        "preexistingIdentities": {
            str(pid): identity.birth for pid, identity in kernel_identities().items()},
        "owned": {},
    }
    save(path, document)


def eligible(process: Process, document: dict) -> bool:
    return (
        process.pid > 1
        and process.uid == document["ownerUid"]
        and document["preexistingIdentities"].get(str(process.pid)) != process.started
        and process.running
    )


def marker_record(process: Process) -> dict:
    return {
        "started": process.started,
        "capturedCommand": process.command,
        "evidence": "marker",
        "parentPid": None,
        "parentStarted": None,
        "depth": 0,
    }


def has_fixture_role_marker(process: Process, root: Path) -> bool:
    return str(root) in process.command and any(
        token in process.command for token in FIXTURE_ROLE_TOKENS
    )


def descendant_record(process: Process, parent: dict) -> dict:
    return {
        "started": process.started,
        "capturedCommand": process.command,
        "evidence": "descendant",
        "parentPid": process.parent_pid,
        "parentStarted": parent["started"],
        "depth": parent["depth"] + 1,
    }


def chain_is_valid(pid: int, owned: dict, root: Path, seen: set[int] | None = None) -> bool:
    seen = set() if seen is None else seen
    if pid in seen:
        return False
    seen.add(pid)
    record = owned.get(str(pid))
    if not record:
        return False
    if record["evidence"] == "marker":
        return str(root) in record["capturedCommand"]
    parent_pid = record.get("parentPid")
    parent = owned.get(str(parent_pid))
    return bool(
        parent
        and parent["started"] == record.get("parentStarted")
        and chain_is_valid(parent_pid, owned, root, seen)
    )


def capture(document: dict, root: Path) -> dict[int, Process]:
    live = processes()
    owned = document["owned"]
    for process in live.values():
        if process.pid != os.getpid() and eligible(process, document) and has_fixture_role_marker(process, root):
            current = owned.get(str(process.pid))
            if not current or current["started"] != process.started:
                owned[str(process.pid)] = marker_record(process)
    changed = True
    while changed:
        changed = False
        for process in live.values():
            if process.pid == os.getpid() or not eligible(process, document):
                continue
            current = owned.get(str(process.pid))
            if current and current["started"] == process.started:
                parent = owned.get(str(process.parent_pid))
                parent_live = live.get(process.parent_pid)
                marker_still_exact = current["evidence"] == "marker" and str(root) in process.command
                ancestry_still_exact = bool(
                    current["evidence"] == "descendant"
                    and parent
                    and parent_live
                    and parent["started"] == parent_live.started
                    and chain_is_valid(process.parent_pid, owned, root)
                )
                if marker_still_exact or ancestry_still_exact:
                    current["capturedCommand"] = process.command
                continue
            parent = owned.get(str(process.parent_pid))
            parent_live = live.get(process.parent_pid)
            if not parent or not parent_live or parent["started"] != parent_live.started:
                continue
            if not chain_is_valid(process.parent_pid, owned, root):
                continue
            owned[str(process.pid)] = descendant_record(process, parent)
            changed = True
    return live


def register(path: Path, pid: int) -> None:
    document, root = load(path)
    live = capture(document, root)
    process = live.get(pid)
    if not process or not eligible(process, document):
        raise SystemExit(f"fixture cannot own ineligible PID {pid}")
    if str(root) not in process.command:
        raise SystemExit(f"fixture root PID {pid} lacks its exact scratch marker")
    document["owned"][str(pid)] = marker_record(process)
    save(path, document)


def current_owned(document: dict, root: Path, live: dict[int, Process]) -> list[Process]:
    result = []
    for pid_text, record in document["owned"].items():
        process = live.get(int(pid_text))
        if (
            process
            and eligible(process, document)
            and process.started == record["started"]
            and chain_is_valid(process.pid, document["owned"], root)
        ):
            result.append(process)
    return result


def capture_state(path: Path) -> None:
    document, root = load(path)
    capture(document, root)
    save(path, document)


def owns(path: Path, pid: int) -> bool:
    document, root = load(path)
    live = capture(document, root)
    save(path, document)
    return any(process.pid == pid for process in current_owned(document, root, live))


def find_foreground(path: Path, config: str) -> None:
    document, root = load(path)
    live = capture(document, root)
    expected = f"-Didea.config.path={config}"
    matches = [
        process.pid
        for process in live.values()
        if eligible(process, document)
        and expected in process.command
        and "-Djava.awt.headless=false" in process.command
    ]
    save(path, document)
    if len(matches) != 1:
        raise SystemExit(f"expected one fixture foreground IDEA process, found {matches}")
    print(matches[0])


def send_owned_signal(document: dict, root: Path, process: Process, signal_number: int) -> None:
    refreshed = processes().get(process.pid)
    record = document["owned"].get(str(process.pid))
    if not refreshed or not record:
        return
    if (
        eligible(refreshed, document)
        and refreshed.started == record["started"]
        and chain_is_valid(refreshed.pid, document["owned"], root)
    ):
        try:
            os.kill(refreshed.pid, signal_number)
        except ProcessLookupError:
            pass


def stop(path: Path) -> None:
    document, root = load(path)
    term_deadline = time.monotonic() + 10
    while True:
        live = capture(document, root)
        active = current_owned(document, root, live)
        if not active or time.monotonic() >= term_deadline:
            break
        for process in sorted(active, key=lambda item: document["owned"][str(item.pid)]["depth"], reverse=True):
            send_owned_signal(document, root, process, signal.SIGTERM)
        time.sleep(0.1)
    live = capture(document, root)
    for process in current_owned(document, root, live):
        send_owned_signal(document, root, process, signal.SIGKILL)
    kill_deadline = time.monotonic() + 2
    while time.monotonic() < kill_deadline:
        live = capture(document, root)
        if not current_owned(document, root, live):
            save(path, document)
            return
        time.sleep(0.05)
    survivors = current_owned(document, root, capture(document, root))
    save(path, document)
    if survivors:
        raise SystemExit(
            "fixture cleanup left owned processes running: "
            + ", ".join(str(process.pid) for process in survivors)
        )


def main(arguments: list[str]) -> None:
    command, *values = arguments
    if command == "init":
        initialize(*values)
        return
    path = state_path(values.pop(0))
    if command == "register":
        register(path, int(values[0]))
    elif command == "capture":
        capture_state(path)
    elif command == "owns":
        raise SystemExit(0 if owns(path, int(values[0])) else 1)
    elif command == "find-foreground":
        find_foreground(path, values[0])
    elif command == "stop":
        stop(path)
    else:
        raise SystemExit(f"unknown fixture process operation: {command}")


if __name__ == "__main__":
    main(sys.argv[1:])
