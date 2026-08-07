#!/usr/bin/env python3
"""Capture and replay Kast CLI incident evidence through a real tmux PTY."""

from __future__ import annotations

import argparse
import dataclasses
import enum
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
DEFAULT_OUTPUT_BUDGET_BYTES = 30_000
EXIT_SENTINEL = "::kast-repro-exit="


class ReproError(Exception):
    """A closed setup, capture, or evidence-validation failure."""


class FindingCode(str, enum.Enum):
    READY_DURING_PENDING_UP = "READY_DURING_PENDING_UP"
    GENERIC_CONFLICT_DURING_REFRESH = "GENERIC_CONFLICT_DURING_REFRESH"
    REFRESH_DID_NOT_CONVERGE = "REFRESH_DID_NOT_CONVERGE"
    MISSING_TRAILING_NEWLINE = "MISSING_TRAILING_NEWLINE"
    DEFAULT_OUTPUT_EXCEEDS_BUDGET = "DEFAULT_OUTPUT_EXCEEDS_BUDGET"
    TRACE_CORRELATION_INCOMPLETE = "TRACE_CORRELATION_INCOMPLETE"
    CAPSULE_CONFINEMENT_VIOLATED = "CAPSULE_CONFINEMENT_VIOLATED"
    CAPSULE_PROCESS_LEAKED = "CAPSULE_PROCESS_LEAKED"
    CAPSULE_RUNTIME_STOP_FAILED = "CAPSULE_RUNTIME_STOP_FAILED"


@dataclasses.dataclass(frozen=True)
class CommandSpec:
    name: str
    argv: tuple[str, ...]
    stdin: str | None = None
    environment: tuple[tuple[str, str], ...] = ()
    timeout_seconds: float = 180.0

    def public(self) -> dict[str, Any]:
        value: dict[str, Any] = {"name": self.name, "argv": list(self.argv)}
        if self.stdin is not None:
            value["stdinBytes"] = len(self.stdin.encode("utf-8"))
        if self.environment:
            value["environment"] = {key: value for key, value in self.environment}
        return value


@dataclasses.dataclass(frozen=True)
class ActiveCommand:
    spec: CommandSpec
    window_id: str
    started_at_epoch_millis: int


@dataclasses.dataclass(frozen=True)
class CommandEvidence:
    name: str
    argv: list[str]
    startedAtEpochMillis: int
    finishedAtEpochMillis: int
    exitCode: int
    timedOut: bool
    transcript: str
    outputBytes: int


@dataclasses.dataclass(frozen=True)
class Finding:
    code: str
    message: str
    evidence: list[str]


@dataclasses.dataclass(frozen=True)
class CapsuleContext:
    mode: str
    root: Path
    bundle_source: Path
    environment: dict[str, str]
    bootstrap_kastctl: Path
    idea_host: Path

    @property
    def cleanup(self) -> str:
        return "STOP_VERIFY_DELETE" if self.mode == "EPHEMERAL" else "STOP_VERIFY_KEEP"

    @property
    def kast(self) -> Path:
        return self.root / "kast-home" / "current" / "bin" / "kast"

    @property
    def kastctl(self) -> Path:
        return self.root / "kast-home" / "current" / "libexec" / "kastctl"

    def public(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "root": str(self.root),
            "cleanup": self.cleanup,
            "bundleSource": str(self.bundle_source),
            "ideaHost": str(self.idea_host),
            "environment": self.environment,
            "install": {"kast": str(self.kast), "kastctl": str(self.kastctl)},
        }


def epoch_millis() -> int:
    return time.time_ns() // 1_000_000


def is_within(path: Path, root: Path) -> bool:
    resolved_path = path.resolve()
    resolved_root = root.resolve()
    return resolved_path == resolved_root or resolved_root in resolved_path.parents


def paths_overlap(first: Path, second: Path) -> bool:
    return is_within(first, second) or is_within(second, first)


def capsule_environment(root: Path, workspace_id: str) -> dict[str, str]:
    home = root / "home"
    return {
        "HOME": str(home),
        "KAST_HOME": str(root / "kast-home"),
        "KAST_CONFIG_HOME": str(root / "config"),
        "KAST_CACHE_HOME": str(root / "cache"),
        "GRADLE_USER_HOME": str(root / "gradle"),
        "TMPDIR": str(root / "tmp"),
        "XDG_CACHE_HOME": str(root / "xdg" / "cache"),
        "XDG_CONFIG_HOME": str(root / "xdg" / "config"),
        "XDG_DATA_HOME": str(root / "xdg" / "data"),
        "KAST_WORKSPACE_ID": workspace_id,
        "KAST_IDEA_TRACE": "true",
        "PATH": f"{home / '.local' / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}",
    }


def merged_environment(overrides: dict[str, str] | None = None) -> dict[str, str]:
    environment = os.environ.copy()
    if overrides:
        environment.update(overrides)
    return environment


def capsule_lifecycle_plan(
    capsule: CapsuleContext,
    workspace: Path,
    commands: list[CommandSpec],
) -> list[CommandSpec]:
    return [
        CommandSpec(
            "capsule-setup",
            (
                str(capsule.bootstrap_kastctl),
                "--output",
                "json",
                "setup",
                "--source",
                str(capsule.bundle_source),
                "--profile",
                "development",
            ),
            timeout_seconds=420.0,
        ),
        CommandSpec(
            "capsule-idea-host",
            (
                str(capsule.kastctl),
                "--output",
                "json",
                "config",
                "set",
                "indexer.hostCommand",
                str(capsule.idea_host),
                "--workspace-root",
                str(workspace),
            ),
        ),
        *commands,
        CommandSpec(
            "capsule-runtime-stop",
            (
                str(capsule.kastctl),
                "--output",
                "json",
                "developer",
                "runtime",
                "stop",
            ),
        ),
        CommandSpec(
            "capsule-teardown-verify",
            ("internal:verify-no-capsule-processes", str(capsule.root)),
        ),
    ]


def command_plan(
    kast: str,
    file_path: str,
    symbol: str,
    *,
    restart_runtime: bool,
    exercise_plans: bool,
    samples: int,
    sample_interval: float,
    transition_timeout: float,
) -> list[CommandSpec]:
    short_name = symbol.rsplit(".", 1)[-1]
    package_name = symbol.rsplit(".", 1)[0]
    probe_path = (Path(file_path).parent / "KastCliReproProbe.kt").as_posix()
    observer = observer_script(kast, symbol, samples, sample_interval)
    commands = [
        CommandSpec("home", (kast,)),
        CommandSpec("help", (kast, "--help")),
        CommandSpec("files", (kast, "files", file_path)),
        CommandSpec("symbol-find", (kast, "symbol", "find", short_name)),
        CommandSpec("symbol-show", (kast, "symbol", "show", symbol)),
        CommandSpec("symbol-refs", (kast, "symbol", "refs", symbol)),
        CommandSpec("symbol-callers", (kast, "symbol", "callers", symbol)),
        CommandSpec("symbol-callees", (kast, "symbol", "callees", symbol)),
        CommandSpec("symbol-implementations", (kast, "symbol", "implementations", symbol)),
        CommandSpec("symbol-supertypes", (kast, "symbol", "supertypes", symbol)),
        CommandSpec("symbol-subtypes", (kast, "symbol", "subtypes", symbol)),
        CommandSpec("graph-summary", (kast, "graph", "summary")),
        CommandSpec("graph-nodes", (kast, "graph", "nodes")),
        CommandSpec("graph-neighbors", (kast, "graph", "neighbors", symbol)),
        CommandSpec("graph-topology", (kast, "graph", "topology")),
        CommandSpec("graph-communities", (kast, "graph", "communities")),
        CommandSpec("graph-impact", (kast, "graph", "impact", symbol)),
        CommandSpec("check", (kast, "check", file_path)),
    ]
    if exercise_plans:
        commands.extend(
            [
                CommandSpec("change-rename", (kast, "change", "rename", symbol, f"{short_name}ReproProbe")),
                CommandSpec(
                    "change-add-file",
                    (kast, "change", "add-file", probe_path),
                    stdin=f"package {package_name}\n\ninternal object KastCliReproProbe",
                ),
                CommandSpec(
                    "change-add-declaration",
                    (kast, "change", "add-declaration", file_path),
                    stdin="private const val KAST_CLI_REPRO_SENTINEL = 1",
                ),
                CommandSpec(
                    "change-replace",
                    (kast, "change", "replace", symbol),
                    stdin=f"class {short_name}ReproProbe",
                ),
                CommandSpec("apply-invalid", (kast, "apply", "repro-invalid-plan")),
                CommandSpec("recover-invalid", (kast, "recover", "repro-invalid-recovery")),
            ]
        )
    commands.extend(
        [
            CommandSpec("refresh", (kast, "refresh", file_path), timeout_seconds=transition_timeout),
            CommandSpec("refresh-observer", ("/bin/bash", "-lc", observer), timeout_seconds=transition_timeout),
        ]
    )
    if restart_runtime:
        commands.extend(
            [
                CommandSpec(
                    "cold-up",
                    (kast, "up"),
                    environment=(("KAST_IDEA_TRACE", "true"),),
                    timeout_seconds=transition_timeout,
                ),
                CommandSpec("cold-observer", ("/bin/bash", "-lc", observer), timeout_seconds=transition_timeout),
            ]
        )
    return commands


def observer_script(kast: str, symbol: str, samples: int, interval: float) -> str:
    kast_command = shlex.quote(kast)
    symbol_argument = shlex.quote(symbol)
    return (
        f"for i in $(seq 1 {samples}); do "
        'printf "__KAST_SAMPLE__=%s\\n" "$i"; '
        f"{kast_command}; "
        f"{kast_command} symbol show {symbol_argument}; "
        f"sleep {interval}; "
        "done"
    )


class TmuxCapture:
    def __init__(
        self,
        session: str,
        workspace: Path,
        evidence: Path,
        keep_session: bool,
        base_environment: dict[str, str] | None = None,
    ) -> None:
        self.session = session
        self.workspace = workspace
        self.evidence = evidence
        self.keep_session = keep_session
        self.base_environment = base_environment or {}
        self.commands: list[CommandEvidence] = []

    def start(self) -> None:
        existing = subprocess.run(
            ["tmux", "has-session", "-t", self.session],
            capture_output=True,
            check=False,
        )
        if existing.returncode == 0:
            raise ReproError(f"tmux session already exists: {self.session}")
        run_checked("tmux", "new-session", "-d", "-s", self.session, "-c", str(self.workspace))
        run_checked("tmux", "set-option", "-t", self.session, "history-limit", "1000000")

    def close(self) -> None:
        if not self.keep_session:
            subprocess.run(["tmux", "kill-session", "-t", self.session], check=False, capture_output=True)

    def begin(self, spec: CommandSpec) -> ActiveCommand:
        command = shlex.join(spec.argv)
        environment = dict(self.base_environment)
        environment.update(dict(spec.environment))
        if environment:
            environment = " ".join(
                f"{shlex.quote(key)}={shlex.quote(value)}" for key, value in environment.items()
            )
            command = f"env {environment} {command}"
        if spec.stdin is not None:
            command = f"printf %s {shlex.quote(spec.stdin)} | {command}"
        shell = (
            "set +e; "
            f"{command}; "
            "status=$?; "
            f"printf '{EXIT_SENTINEL}%s\\n' \"$status\"; "
            "exec sleep 2147483647"
        )
        window_name = re.sub(r"[^A-Za-z0-9_-]", "-", spec.name)[:40]
        result = run_checked(
            "tmux",
            "new-window",
            "-d",
            "-P",
            "-F",
            "#{window_id}",
            "-t",
            self.session,
            "-n",
            window_name,
            "-c",
            str(self.workspace),
            f"/bin/bash -lc {shlex.quote(shell)}",
        )
        return ActiveCommand(spec, result.stdout.strip(), epoch_millis())

    def finish(self, active: ActiveCommand) -> CommandEvidence:
        deadline = time.monotonic() + active.spec.timeout_seconds
        timed_out = False
        transcript = ""
        exit_code: int | None = None
        while True:
            capture = subprocess.run(
                ["tmux", "capture-pane", "-p", "-J", "-S", "-", "-t", active.window_id],
                text=True,
                capture_output=True,
                check=False,
            )
            if capture.returncode != 0:
                raise ReproError(f"tmux lost capture window for {active.spec.name}")
            transcript = capture.stdout
            match = re.search(rf"{re.escape(EXIT_SENTINEL)}([0-9]+)", transcript)
            if match is not None:
                exit_code = int(match.group(1))
                break
            if time.monotonic() >= deadline:
                timed_out = True
                subprocess.run(
                    ["tmux", "send-keys", "-t", active.window_id, "C-c"],
                    check=False,
                    capture_output=True,
                )
                time.sleep(0.25)
                capture = subprocess.run(
                    ["tmux", "capture-pane", "-p", "-J", "-S", "-", "-t", active.window_id],
                    text=True,
                    capture_output=True,
                    check=False,
                )
                transcript = capture.stdout
                break
            time.sleep(0.1)
        transcript_path = self.evidence / "transcripts" / f"{active.spec.name}.txt"
        transcript_path.write_text(transcript, encoding="utf-8")
        resolved_exit_code = exit_code if exit_code is not None else 124 if timed_out else 125
        evidence = CommandEvidence(
            name=active.spec.name,
            argv=list(active.spec.argv),
            startedAtEpochMillis=active.started_at_epoch_millis,
            finishedAtEpochMillis=epoch_millis(),
            exitCode=resolved_exit_code,
            timedOut=timed_out,
            transcript=str(transcript_path.relative_to(self.evidence)),
            outputBytes=len(transcript.encode("utf-8")),
        )
        self.commands.append(evidence)
        if not self.keep_session or timed_out:
            subprocess.run(
                ["tmux", "kill-window", "-t", active.window_id],
                check=False,
                capture_output=True,
            )
        return evidence

    def run(self, spec: CommandSpec) -> CommandEvidence:
        return self.finish(self.begin(spec))

    def record(self, name: str, argv: list[str], payload: dict[str, Any], exit_code: int) -> None:
        started = epoch_millis()
        rendered = json.dumps(payload, indent=2) + "\n"
        transcript_path = self.evidence / "transcripts" / f"{name}.txt"
        transcript_path.write_text(rendered, encoding="utf-8")
        self.commands.append(
            CommandEvidence(
                name=name,
                argv=argv,
                startedAtEpochMillis=started,
                finishedAtEpochMillis=epoch_millis(),
                exitCode=exit_code,
                timedOut=False,
                transcript=str(transcript_path.relative_to(self.evidence)),
                outputBytes=len(rendered.encode("utf-8")),
            )
        )


def run_checked(*argv: str) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(argv, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise ReproError(f"{shlex.join(argv)} failed: {detail}")
    return result


def require_success(command: CommandEvidence) -> None:
    if command.exitCode != 0 or command.timedOut:
        raise ReproError(
            f"capture infrastructure command {command.name} failed with exit {command.exitCode}"
        )


def discover_developer_cli(
    kast: str,
    workspace: Path,
    environment: dict[str, str] | None = None,
) -> Path:
    result = subprocess.run(
        [kast],
        cwd=workspace,
        env=merged_environment(environment),
        text=True,
        capture_output=True,
        check=False,
    )
    match = re.search(r"(?m)^\s+cli: (.+)$", result.stdout)
    if match is None:
        raise ReproError("public Kast home did not expose developerOperations.cli")
    rendered_path = match.group(1).strip()
    if rendered_path.startswith('"'):
        try:
            decoded_path = json.loads(rendered_path)
        except json.JSONDecodeError as error:
            raise ReproError("public Kast home exposed an invalid quoted developer CLI path") from error
        if not isinstance(decoded_path, str):
            raise ReproError("public Kast home exposed a non-string developer CLI path")
        rendered_path = decoded_path
    path = Path(rendered_path).expanduser()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise ReproError(f"discovered developer CLI is not executable: {path}")
    return path


def read_config(
    kastctl: Path,
    workspace: Path,
    environment: dict[str, str] | None = None,
) -> dict[str, Any]:
    result = subprocess.run(
        [str(kastctl), "--output", "json", "config", "list", "--workspace-root", str(workspace)],
        env=merged_environment(environment),
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ReproError(f"config discovery failed: {result.stderr.strip() or result.stdout.strip()}")
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ReproError(f"config discovery returned invalid JSON: {error}") from error
    if payload.get("ok") is not True:
        raise ReproError("config discovery did not return ok=true")
    return payload


def discover_idea_host(
    requested: Path | None,
    bootstrap: Path,
    workspace: Path,
    *,
    dry_run: bool,
) -> Path:
    if requested is not None:
        host = requested.expanduser().resolve(strict=not dry_run)
        if not dry_run and (not host.is_dir() or host.suffix != ".app"):
            raise ReproError("--idea-host must name an installed IntelliJ IDEA or Android Studio .app")
        return host
    if dry_run:
        return Path("<supported-idea-host>")

    host_config = read_config(bootstrap, workspace)
    configured = host_config.get("effective", {}).get("indexer", {}).get("hostCommand")
    if isinstance(configured, str) and configured and configured != "idea":
        configured_path = Path(configured).expanduser().resolve(strict=True)
        if configured_path.is_dir() and configured_path.suffix == ".app":
            return configured_path

    roots = [Path("/Applications")]
    host_home = os.environ.get("HOME")
    if host_home:
        home = Path(host_home)
        roots.extend(
            [
                home / "Applications",
                home / "Library" / "Application Support" / "JetBrains" / "Toolbox" / "apps",
            ]
        )
    candidates: set[Path] = set()
    for root in roots:
        if not root.is_dir():
            continue
        for candidate in root.rglob("*.app"):
            if candidate.name.startswith(("IntelliJ IDEA", "Android Studio")):
                candidates.add(candidate.resolve())
    installed = sorted(candidates)
    if not installed:
        raise ReproError(
            "no IntelliJ IDEA or Android Studio host is discoverable outside the capsule; "
            "pass --idea-host"
        )
    if len(installed) > 1:
        raise ReproError(
            "multiple IntelliJ IDEA or Android Studio hosts are installed; pass --idea-host: "
            + ", ".join(str(path) for path in installed)
        )
    return installed[0]


def build_capsule(
    args: argparse.Namespace,
    workspace: Path,
    host_kast: str,
    *,
    dry_run: bool,
) -> CapsuleContext | None:
    if args.capsule_root is None and not args.ephemeral_capsule:
        if args.bundle_source is not None or args.idea_host is not None:
            raise ReproError(
                "--bundle-source and --idea-host require --capsule-root or --ephemeral-capsule"
            )
        return None

    if dry_run:
        bootstrap = Path("<host-kastctl>")
    else:
        bootstrap = discover_developer_cli(host_kast, workspace)
    idea_host = discover_idea_host(
        args.idea_host,
        bootstrap,
        workspace,
        dry_run=dry_run,
    )

    if args.bundle_source is not None:
        bundle_source = args.bundle_source.expanduser().resolve(strict=True)
    elif dry_run:
        bundle_source = Path("<immutable-setup-bundle>")
    else:
        resolved_bootstrap = bootstrap.resolve(strict=True)
        if len(resolved_bootstrap.parents) < 2:
            raise ReproError("active developer CLI did not resolve inside an installation bundle")
        active_release = resolved_bootstrap.parents[1]
        version: str | None = None
        try:
            receipt = json.loads((active_release / "receipt.json").read_text(encoding="utf-8"))
            if isinstance(receipt.get("activeVersion"), str):
                version = receipt["activeVersion"]
        except (OSError, json.JSONDecodeError):
            pass
        setup_directory = workspace / "build" / "setup"
        candidates = sorted(
            (
                path
                for path in setup_directory.glob("kast-*.tar.gz")
                if version is None or version in path.name
            ),
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        )
        if not candidates:
            version_detail = f" matching active version {version}" if version else ""
            raise ReproError(
                "no immutable setup archive"
                f"{version_detail} exists under {setup_directory}; "
                "pass --bundle-source or run ./gradlew packageDevelopmentSetupBundle"
            )
        bundle_source = candidates[0].resolve(strict=True)
    if not dry_run and not (bundle_source.is_dir() or bundle_source.is_file()):
        raise ReproError(f"bundle source is unavailable: {bundle_source}")

    if args.ephemeral_capsule:
        root = (
            Path("<ephemeral>")
            if dry_run
            else Path(tempfile.mkdtemp(prefix="kast-capsule-", dir="/private/tmp"))
        )
        mode = "EPHEMERAL"
    else:
        root = args.capsule_root.expanduser().resolve()
        mode = "PERSISTENT"

    if not dry_run and paths_overlap(root, workspace):
        raise ReproError("capsule root must not overlap the observed workspace")
    socket_probe = root / "tmp" / "kast-indexer-000000000000.sock"
    if not dry_run and len(os.fsencode(str(socket_probe))) >= 104:
        raise ReproError(
            "capsule root is too long for a macOS Unix-domain socket; choose a shorter --capsule-root"
        )
    workspace_id = (
        "<capsule-workspace-id>"
        if dry_run
        else str(uuid.uuid5(uuid.NAMESPACE_URL, f"kast-cli-repro:{root}:{workspace}"))
    )
    environment = capsule_environment(root, workspace_id)
    capsule = CapsuleContext(mode, root, bundle_source, environment, bootstrap, idea_host)
    if not dry_run:
        for key in (
            "HOME",
            "KAST_HOME",
            "KAST_CONFIG_HOME",
            "KAST_CACHE_HOME",
            "GRADLE_USER_HOME",
            "TMPDIR",
            "XDG_CACHE_HOME",
            "XDG_CONFIG_HOME",
            "XDG_DATA_HOME",
        ):
            Path(environment[key]).mkdir(parents=True, exist_ok=True)
    return capsule


def verify_capsule_install(
    capsule: CapsuleContext,
    workspace: Path,
) -> tuple[Path, dict[str, Any], dict[str, Any]]:
    for executable in (capsule.kast, capsule.kastctl):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise ReproError(f"capsule setup did not install an executable: {executable}")
        if not is_within(executable, capsule.root):
            raise ReproError(f"capsule executable escaped its root: {executable.resolve()}")
    discovered = discover_developer_cli(str(capsule.kast), workspace, capsule.environment)
    if discovered.resolve() != capsule.kastctl.resolve():
        raise ReproError(
            "capsule public home exposed a developer CLI outside the capsule installation"
        )
    config = read_config(discovered, workspace, capsule.environment)
    candidate_paths = [capsule.kast.resolve(), capsule.kastctl.resolve()]
    config_path = config.get("configPath")
    if isinstance(config_path, str) and config_path:
        candidate_paths.append(Path(config_path))
    effective_paths = config.get("effective", {}).get("paths", {})
    if isinstance(effective_paths, dict):
        candidate_paths.extend(
            Path(value) for value in effective_paths.values() if isinstance(value, str) and value
        )
    escaped = sorted(str(path) for path in candidate_paths if not is_within(path, capsule.root))
    proof = {
        "installationContained": not escaped,
        "declaredPaths": sorted(str(path) for path in candidate_paths),
        "escapedPaths": escaped,
    }
    if escaped:
        raise ReproError(f"capsule configuration escaped its root: {', '.join(escaped)}")
    return discovered, config, proof


def process_table() -> dict[int, tuple[int, str]]:
    result = subprocess.run(
        ["/bin/ps", "-axo", "pid=,ppid=,command="],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ReproError(f"process inspection failed: {result.stderr.strip() or result.returncode}")
    table: dict[int, tuple[int, str]] = {}
    for line in result.stdout.splitlines():
        match = re.match(r"\s*(\d+)\s+(\d+)\s+(.*)$", line)
        if match is not None:
            table[int(match.group(1))] = (int(match.group(2)), match.group(3))
    return table


def process_ancestry(table: dict[int, tuple[int, str]]) -> set[int]:
    ancestry: set[int] = set()
    current = os.getpid()
    while current > 0 and current not in ancestry:
        ancestry.add(current)
        entry = table.get(current)
        if entry is None:
            break
        current = entry[0]
    return ancestry


def capsule_processes(root: Path, seeds: set[int] | None = None) -> set[int]:
    table = process_table()
    excluded = process_ancestry(table)
    root_spellings = {str(root), str(root.resolve())}
    owned = {
        pid
        for pid, (_, command) in table.items()
        if pid not in excluded and any(spelling in command for spelling in root_spellings)
    }
    owned.update(pid for pid in seeds or set() if pid in table and pid not in excluded)
    changed = True
    while changed:
        changed = False
        for pid, (parent, _) in table.items():
            if pid not in excluded and parent in owned and pid not in owned:
                owned.add(pid)
                changed = True
    return owned


def alive_processes(process_ids: set[int]) -> set[int]:
    alive: set[int] = set()
    for process_id in process_ids:
        try:
            os.kill(process_id, 0)
        except ProcessLookupError:
            continue
        except PermissionError as error:
            raise ReproError(f"cannot prove capsule process ownership for PID {process_id}") from error
        alive.add(process_id)
    return alive


def wait_for_process_exit(process_ids: set[int], seconds: float) -> set[int]:
    deadline = time.monotonic() + seconds
    remaining = alive_processes(process_ids)
    while remaining and time.monotonic() < deadline:
        time.sleep(0.1)
        remaining = alive_processes(remaining)
    return remaining


def terminate_capsule_processes(process_ids: set[int]) -> tuple[list[int], list[int]]:
    targeted = sorted(alive_processes(process_ids))
    for process_id in targeted:
        try:
            os.kill(process_id, signal.SIGTERM)
        except ProcessLookupError:
            pass
    remaining = wait_for_process_exit(set(targeted), 5.0)
    for process_id in remaining:
        try:
            os.kill(process_id, signal.SIGKILL)
        except ProcessLookupError:
            pass
    remaining = wait_for_process_exit(remaining, 2.0)
    return targeted, sorted(remaining)


def config_restore_specs(kastctl: Path, workspace: Path, config: dict[str, Any]) -> list[CommandSpec]:
    mutable = {
        item.get("key"): item
        for item in config.get("mutableFields", [])
        if isinstance(item, dict) and isinstance(item.get("key"), str)
    }
    telemetry = config.get("effective", {}).get("telemetry", {})
    specs: list[CommandSpec] = []
    for key, leaf in (("telemetry.enabled", "enabled"), ("telemetry.detail", "detail")):
        base = (str(kastctl), "--output", "json", "config")
        if mutable.get(key, {}).get("workspaceOverride") is True:
            value = telemetry.get(leaf)
            rendered = str(value).lower() if isinstance(value, bool) else str(value)
            argv = (*base, "set", key, rendered, "--workspace-root", str(workspace))
        else:
            argv = (*base, "unset", key, "--workspace-root", str(workspace))
        specs.append(CommandSpec(f"restore-{leaf}", argv))
    return specs


def log_paths(config: dict[str, Any]) -> tuple[Path, Path]:
    config_path = Path(str(config.get("configPath", "")))
    if len(config_path.parent.name) != 64:
        raise ReproError("config discovery did not expose a canonical workspace data directory")
    telemetry = config_path.parent / "telemetry" / "idea-spans.jsonl"
    cache_root = Path(str(config.get("effective", {}).get("paths", {}).get("cacheDir", "")))
    idea_log = cache_root / "idea-sidecars" / config_path.parent.name[:12] / "idea-log" / "idea.log"
    return telemetry, idea_log


def file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except FileNotFoundError:
        return 0


def copy_delta(source: Path, offset: int, destination: Path) -> None:
    if not source.is_file():
        destination.write_text("", encoding="utf-8")
        return
    with source.open("rb") as stream:
        if source.stat().st_size >= offset:
            stream.seek(offset)
        destination.write_bytes(stream.read())


def write_manifest(
    directory: Path,
    workspace: Path,
    session: str,
    commands: list[CommandEvidence],
    capsule: dict[str, Any] | None = None,
) -> None:
    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "workspaceRoot": str(workspace),
        "sessionName": session,
        "commands": [dataclasses.asdict(command) for command in commands],
        "telemetry": "telemetry.jsonl",
        "structuredTrace": "idea-trace.log",
    }
    if capsule is not None:
        payload["capsule"] = capsule
    (directory / "manifest.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def capture(args: argparse.Namespace) -> int:
    workspace = args.workspace_root.resolve(strict=True)
    file_path = Path(args.file)
    if file_path.is_absolute() or ".." in file_path.parts:
        raise ReproError("--file must be a workspace-relative path without parent traversal")
    source = (workspace / file_path).resolve(strict=True)
    if workspace not in source.parents or source.suffix != ".kt":
        raise ReproError("--file must resolve to a Kotlin file inside the workspace")
    host_kast = shutil.which(args.kast)
    if host_kast is None and not args.dry_run:
        raise ReproError(f"Kast executable is unavailable: {args.kast}")
    host_kast = host_kast or args.kast
    capsule = build_capsule(args, workspace, host_kast, dry_run=args.dry_run)
    kast = str(capsule.kast) if capsule is not None else host_kast
    plan = command_plan(
        kast,
        file_path.as_posix(),
        args.symbol,
        restart_runtime=args.restart_runtime or capsule is not None,
        exercise_plans=args.exercise_plans,
        samples=args.samples,
        sample_interval=args.sample_interval,
        transition_timeout=args.transition_timeout,
    )
    if args.dry_run:
        dry_plan = capsule_lifecycle_plan(capsule, workspace, plan) if capsule is not None else plan
        payload: dict[str, Any] = {
            "schemaVersion": SCHEMA_VERSION,
            "commands": [spec.public() for spec in dry_plan],
        }
        if capsule is not None:
            payload["capsule"] = capsule.public()
        print(json.dumps(payload, indent=2))
        return 0
    if shutil.which("tmux") is None:
        raise ReproError("tmux is required for live capture")
    output = args.output_dir or (
        Path(tempfile.gettempdir())
        / "kast-cli-repro"
        / f"{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-{os.getpid()}"
    )
    output = output.resolve()
    if output == workspace or workspace in output.parents:
        raise ReproError("--output-dir must be outside the observed workspace so capture cannot trigger indexing")
    if capsule is not None and paths_overlap(output, capsule.root):
        raise ReproError("--output-dir must not overlap the capsule root")
    if output.exists() and not output.is_dir():
        raise ReproError(f"evidence output is not a directory: {output}")
    if output.exists() and any(output.iterdir()):
        raise ReproError(f"evidence output is not empty: {output}")
    (output / "transcripts").mkdir(parents=True, exist_ok=True)
    session = args.session_name or f"kast-cli-repro-{os.getpid()}"
    if re.fullmatch(r"[A-Za-z0-9_-]+", session) is None:
        raise ReproError("--session-name must contain only letters, digits, underscores, or hyphens")
    runner = TmuxCapture(
        session,
        workspace,
        output,
        args.keep_session,
        capsule.environment if capsule is not None else None,
    )
    config: dict[str, Any] | None = None
    kastctl: Path | None = None
    telemetry_path: Path | None = None
    idea_log_path: Path | None = None
    telemetry_offset = 0
    trace_offset = 0
    installation_proof: dict[str, Any] = {}
    capture_failure: ReproError | None = None
    teardown_errors: list[str] = []
    runner_started = False
    try:
        runner.start()
        runner_started = True
        if capsule is not None:
            lifecycle = capsule_lifecycle_plan(capsule, workspace, [])
            setup = lifecycle[0]
            setup = dataclasses.replace(setup, timeout_seconds=args.transition_timeout)
            require_success(runner.run(setup))
            kastctl, config, installation_proof = verify_capsule_install(capsule, workspace)
            idea_host = dataclasses.replace(
                lifecycle[1],
                timeout_seconds=args.transition_timeout,
            )
            require_success(runner.run(idea_host))
            config = read_config(kastctl, workspace, capsule.environment)
            installation_proof["readOnlyAuthorities"] = {
                "bundleSource": str(capsule.bundle_source),
                "ideaHost": str(capsule.idea_host),
                "workspaceRoot": str(workspace),
            }
        else:
            kastctl = discover_developer_cli(kast, workspace)
            config = read_config(kastctl, workspace)
        (output / "config-before.json").write_text(
            json.dumps(config, indent=2) + "\n",
            encoding="utf-8",
        )
        telemetry_path, idea_log_path = log_paths(config)
        telemetry_offset = file_size(telemetry_path)
        trace_offset = file_size(idea_log_path)
        require_success(runner.run(
            CommandSpec(
                "telemetry-enable",
                (
                    str(kastctl), "--output", "json", "config", "set", "telemetry.enabled", "true",
                    "--workspace-root", str(workspace),
                ),
            )
        ))
        require_success(runner.run(
            CommandSpec(
                "telemetry-verbose",
                (
                    str(kastctl), "--output", "json", "config", "set", "telemetry.detail", "verbose",
                    "--workspace-root", str(workspace),
                ),
            )
        ))
        if args.restart_runtime or capsule is not None:
            runner.run(
                CommandSpec(
                    "runtime-stop",
                    (
                        str(kastctl), "--output", "json", "developer", "runtime", "stop",
                        "--workspace-root", str(workspace),
                    ),
                    timeout_seconds=args.transition_timeout,
                )
            )
            cold_up = next(spec for spec in plan if spec.name == "cold-up")
            cold_observer = next(spec for spec in plan if spec.name == "cold-observer")
            up_active = runner.begin(cold_up)
            observer_active = runner.begin(cold_observer)
            runner.finish(observer_active)
            runner.finish(up_active)
        else:
            runner.run(CommandSpec("warm-up", (kast, "up"), timeout_seconds=args.transition_timeout))
        for spec in plan:
            if spec.name in {"cold-up", "cold-observer", "refresh", "refresh-observer"}:
                continue
            runner.run(spec)
        refresh = next(spec for spec in plan if spec.name == "refresh")
        observer = next(spec for spec in plan if spec.name == "refresh-observer")
        refresh_active = runner.begin(refresh)
        observer_active = runner.begin(observer)
        runner.finish(observer_active)
        runner.finish(refresh_active)
    except ReproError as error:
        capture_failure = error
    except OSError as error:
        capture_failure = ReproError(str(error))
    except KeyboardInterrupt:
        capture_failure = ReproError("capture interrupted")
    finally:
        capsule_proof: dict[str, Any] | None = None
        if capsule is not None:
            observed_processes: set[int] = set()
            try:
                observed_processes = capsule_processes(capsule.root)
            except ReproError as error:
                teardown_errors.append(str(error))
            stop_exit_code = 125
            if runner_started and capsule.kastctl.is_file():
                try:
                    stopped = runner.run(
                        CommandSpec(
                            "capsule-runtime-stop",
                            (
                                str(capsule.kastctl),
                                "--output",
                                "json",
                                "developer",
                                "runtime",
                                "stop",
                                "--workspace-root",
                                str(workspace),
                            ),
                            timeout_seconds=args.transition_timeout,
                        )
                    )
                    stop_exit_code = stopped.exitCode
                except ReproError as error:
                    teardown_errors.append(str(error))
            if runner_started:
                runner.close()
            try:
                remaining_candidates = capsule_processes(capsule.root, observed_processes)
                terminated, remaining = terminate_capsule_processes(remaining_candidates)
            except ReproError as error:
                teardown_errors.append(str(error))
                terminated = []
                remaining = sorted(observed_processes)
            state_keys = (
                "HOME",
                "KAST_HOME",
                "KAST_CONFIG_HOME",
                "KAST_CACHE_HOME",
                "GRADLE_USER_HOME",
                "TMPDIR",
                "XDG_CACHE_HOME",
                "XDG_CONFIG_HOME",
                "XDG_DATA_HOME",
            )
            state_contained = all(
                is_within(Path(capsule.environment[key]), capsule.root) for key in state_keys
            )
            capsule_proof = {
                **capsule.public(),
                **installation_proof,
                "stateContained": state_contained,
                "runtimeStopExitCode": stop_exit_code,
                "observedProcessIds": sorted(observed_processes),
                "terminatedProcessIds": terminated,
                "processesRemaining": remaining,
                "runtimeStopSucceeded": stop_exit_code == 0,
                "runtimeStopped": not remaining,
                "rootDeleted": False,
            }
            runner.record(
                "capsule-teardown-verify",
                ["internal:verify-no-capsule-processes", str(capsule.root)],
                capsule_proof,
                0 if capsule_proof["runtimeStopped"] and state_contained else 1,
            )
        elif runner_started:
            if kastctl is not None and config is not None:
                for spec in config_restore_specs(kastctl, workspace, config):
                    try:
                        runner.run(spec)
                    except ReproError:
                        pass
            runner.close()

        if telemetry_path is None:
            (output / "telemetry.jsonl").write_text("", encoding="utf-8")
        else:
            copy_delta(telemetry_path, telemetry_offset, output / "telemetry.jsonl")
        if idea_log_path is None:
            (output / "idea-trace.log").write_text("", encoding="utf-8")
        else:
            copy_delta(idea_log_path, trace_offset, output / "idea-trace.log")
        write_manifest(output, workspace, session, runner.commands, capsule_proof)

        if (
            capsule is not None
            and capsule.mode == "EPHEMERAL"
            and capsule_proof is not None
            and capsule_proof["runtimeStopped"] is True
            and capsule_proof.get("installationContained") is True
            and capsule_proof["stateContained"] is True
            and capture_failure is None
            and not teardown_errors
        ):
            temp_root = Path("/private/tmp").resolve()
            if (
                capsule.root.parent.resolve() != temp_root
                or not capsule.root.name.startswith("kast-capsule-")
            ):
                teardown_errors.append("refused to delete an unrecognized ephemeral capsule root")
            else:
                try:
                    shutil.rmtree(capsule.root)
                    capsule_proof["rootDeleted"] = not capsule.root.exists()
                    if not capsule_proof["rootDeleted"]:
                        teardown_errors.append("ephemeral capsule root still exists after deletion")
                    write_manifest(output, workspace, session, runner.commands, capsule_proof)
                except OSError as error:
                    teardown_errors.append(f"ephemeral capsule cleanup failed: {error}")

    if capture_failure is not None or teardown_errors:
        details = [str(capture_failure)] if capture_failure is not None else []
        details.extend(teardown_errors)
        raise ReproError(f"capture retained evidence at {output}: {'; '.join(details)}")
    report, exit_code = analyze(output)
    report["evidenceDirectory"] = str(output)
    if args.keep_session:
        report["tmuxSession"] = session
    print(json.dumps(report, indent=2))
    return exit_code


def read_manifest(directory: Path) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    manifest_path = directory / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReproError(f"invalid evidence manifest: {error}") from error
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise ReproError(f"unsupported evidence schema: {manifest.get('schemaVersion')!r}")
    commands = manifest.get("commands")
    if not isinstance(commands, list):
        raise ReproError("evidence manifest commands must be an array")
    indexed: dict[str, dict[str, Any]] = {}
    for command in commands:
        if not isinstance(command, dict) or not isinstance(command.get("name"), str):
            raise ReproError("every evidence command must have a string name")
        indexed[command["name"]] = command
    return manifest, indexed


def transcript(directory: Path, command: dict[str, Any]) -> str:
    relative = command.get("transcript")
    if not isinstance(relative, str) or not relative:
        raise ReproError(f"command {command.get('name')} has no transcript")
    path = (directory / relative).resolve()
    if directory.resolve() not in path.parents:
        raise ReproError(f"command {command.get('name')} transcript escapes the evidence directory")
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        raise ReproError(f"cannot read transcript for {command.get('name')}: {error}") from error


def overlaps(first: dict[str, Any], second: dict[str, Any]) -> bool:
    values = (
        first.get("startedAtEpochMillis"), first.get("finishedAtEpochMillis"),
        second.get("startedAtEpochMillis"), second.get("finishedAtEpochMillis"),
    )
    if not all(isinstance(value, int) and not isinstance(value, bool) for value in values):
        raise ReproError("command timing fields must be integers")
    return values[0] < values[3] and values[2] < values[1]


def telemetry_missing_fields(directory: Path, manifest: dict[str, Any]) -> list[str]:
    relative = manifest.get("telemetry")
    if not isinstance(relative, str):
        return ["runtimeInstanceId", "semanticGenerationStart", "semanticGenerationEnd", "dumbModeState", "typedOutcome"]
    path = (directory / relative).resolve()
    if directory.resolve() not in path.parents or not path.is_file():
        return ["runtimeInstanceId", "semanticGenerationStart", "semanticGenerationEnd", "dumbModeState", "typedOutcome"]
    attributes: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and isinstance(value.get("attributes"), dict):
            attributes.append(value["attributes"])
    aliases = {
        "runtimeInstanceId": {"runtimeInstanceId", "kast.runtime.instance_id"},
        "semanticGenerationStart": {"semanticGenerationStart", "startGeneration", "kast.semantic.generation.start"},
        "semanticGenerationEnd": {"semanticGenerationEnd", "endGeneration", "kast.semantic.generation.end"},
        "dumbModeState": {"dumbModeState", "kast.idea.dumb_mode"},
        "typedOutcome": {"typedOutcome", "outcome", "errorCode", "kast.outcome.code"},
    }
    return [name for name, keys in aliases.items() if not any(keys & set(item) for item in attributes)]


def analyze(directory: Path) -> tuple[dict[str, Any], int]:
    directory = directory.resolve(strict=True)
    manifest, commands = read_manifest(directory)
    findings: list[Finding] = []
    cold_up = commands.get("cold-up")
    cold_observer = commands.get("cold-observer")
    if cold_up and cold_observer and overlaps(cold_up, cold_observer):
        observer_text = transcript(directory, cold_observer)
        if re.search(r"(?m)^ready: true$", observer_text):
            findings.append(
                Finding(
                    FindingCode.READY_DURING_PENDING_UP.value,
                    "The public home reported READY while `kast up` was still pending.",
                    ["transcripts/cold-up.txt", "transcripts/cold-observer.txt"],
                )
            )
    refresh = commands.get("refresh")
    refresh_observer = commands.get("refresh-observer")
    if refresh and refresh.get("exitCode") != 0:
        findings.append(
            Finding(
                FindingCode.REFRESH_DID_NOT_CONVERGE.value,
                "Focused refresh did not converge to a successful semantic result.",
                [str(refresh.get("transcript"))],
            )
        )
    if refresh_observer:
        observer_text = transcript(directory, refresh_observer)
        if "error: CONFLICT" in observer_text and "Run `kast --help`" in observer_text:
            findings.append(
                Finding(
                    FindingCode.GENERIC_CONFLICT_DURING_REFRESH.value,
                    "A semantic read during refresh returned generic CONFLICT guidance.",
                    [str(refresh_observer.get("transcript"))],
                )
            )
    framing_evidence: list[str] = []
    for command in commands.values():
        text = transcript(directory, command)
        marker = text.find(EXIT_SENTINEL)
        if marker > 0 and text[marker - 1] != "\n":
            framing_evidence.append(str(command.get("transcript")))
    if framing_evidence:
        findings.append(
            Finding(
                FindingCode.MISSING_TRAILING_NEWLINE.value,
                "One or more CLI results were not newline-terminated in the tmux PTY.",
                sorted(framing_evidence),
            )
        )
    oversized = [
        f"{name}:{command.get('outputBytes')}"
        for name, command in commands.items()
        if name in {"graph-nodes", "graph-topology", "graph-communities"}
        and isinstance(command.get("outputBytes"), int)
        and command["outputBytes"] > DEFAULT_OUTPUT_BUDGET_BYTES
    ]
    if oversized:
        findings.append(
            Finding(
                FindingCode.DEFAULT_OUTPUT_EXCEEDS_BUDGET.value,
                f"Default graph output exceeded the {DEFAULT_OUTPUT_BUDGET_BYTES}-byte agent budget.",
                sorted(oversized),
            )
        )
    missing = telemetry_missing_fields(directory, manifest)
    if missing:
        findings.append(
            Finding(
                FindingCode.TRACE_CORRELATION_INCOMPLETE.value,
                "Telemetry cannot correlate a request with runtime, generation movement, dumb mode, and typed outcome.",
                missing,
            )
        )
    capsule = manifest.get("capsule")
    if isinstance(capsule, dict):
        confinement_evidence: list[str] = []
        if capsule.get("installationContained") is not True:
            confinement_evidence.extend(str(value) for value in capsule.get("escapedPaths", []))
        if capsule.get("stateContained") is not True:
            confinement_evidence.append("stateContained=false")
        if capsule.get("mode") == "EPHEMERAL" and capsule.get("rootDeleted") is not True:
            confinement_evidence.append("rootDeleted=false")
        if confinement_evidence:
            findings.append(
                Finding(
                    FindingCode.CAPSULE_CONFINEMENT_VIOLATED.value,
                    "The capsule did not prove that installation and mutable state remained confined.",
                    confinement_evidence,
                )
            )
        remaining = capsule.get("processesRemaining")
        if capsule.get("runtimeStopped") is not True or not isinstance(remaining, list) or remaining:
            findings.append(
                Finding(
                    FindingCode.CAPSULE_PROCESS_LEAKED.value,
                    "The capsule did not prove that every observed runtime process terminated.",
                    [str(value) for value in remaining] if isinstance(remaining, list) else ["process proof missing"],
                )
            )
        if capsule.get("runtimeStopSucceeded") is not True:
            findings.append(
                Finding(
                    FindingCode.CAPSULE_RUNTIME_STOP_FAILED.value,
                    "The public runtime stop command failed before capsule process cleanup completed.",
                    ["transcripts/capsule-runtime-stop.txt"],
                )
            )
    findings.sort(key=lambda finding: finding.code)
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": "OBSERVATIONS_REPRODUCED" if findings else "PASS",
        "workspaceRoot": manifest.get("workspaceRoot"),
        "sessionName": manifest.get("sessionName"),
        "findingCount": len(findings),
        "findings": [dataclasses.asdict(finding) for finding in findings],
    }
    return report, 1 if findings else 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        description="Capture a real Kast CLI session in tmux and replay its incident evidence offline.",
    )
    commands = root.add_subparsers(dest="command", required=True)
    capture_parser = commands.add_parser("capture", help="run the live tmux scenario and write an evidence bundle")
    capture_parser.add_argument("--workspace-root", type=Path, default=Path.cwd())
    capture_parser.add_argument("--file", required=True, help="workspace-relative Kotlin file used by focused operations")
    capture_parser.add_argument("--symbol", required=True, help="compiler-resolvable fully qualified Kotlin symbol")
    capture_parser.add_argument("--output-dir", type=Path)
    capture_parser.add_argument("--session-name")
    capture_parser.add_argument("--kast", default="kast")
    capsule_group = capture_parser.add_mutually_exclusive_group()
    capsule_group.add_argument(
        "--capsule-root",
        type=Path,
        help="reuse an explicit directory that owns the Kast install, state, and runtime",
    )
    capsule_group.add_argument(
        "--ephemeral-capsule",
        action="store_true",
        help="create an isolated capsule, prove process teardown, then delete it",
    )
    capture_parser.add_argument(
        "--bundle-source",
        type=Path,
        help="setup bundle archive; defaults to an active-version archive under build/setup",
    )
    capture_parser.add_argument(
        "--idea-host",
        type=Path,
        help="read-only supported IntelliJ IDEA or Android Studio .app used by the isolated indexer",
    )
    capture_parser.add_argument("--samples", type=int, default=20)
    capture_parser.add_argument("--sample-interval", type=float, default=1.0)
    capture_parser.add_argument("--transition-timeout", type=float, default=420.0)
    capture_parser.add_argument("--restart-runtime", action="store_true", help="stop the exact-root runtime and cold-start it with KAST_IDEA_TRACE=true")
    capture_parser.add_argument("--exercise-plans", action="store_true", help="exercise mutation planning and invalid apply/recover without applying a valid plan")
    capture_parser.add_argument("--keep-session", action="store_true", help="leave the completed tmux session available for attachment")
    capture_parser.add_argument("--dry-run", action="store_true", help="print the command matrix without touching tmux or Kast state")
    analyze_parser = commands.add_parser("analyze", help="replay an existing evidence bundle without a live runtime")
    analyze_parser.add_argument("--evidence-dir", type=Path, required=True)
    analyze_parser.add_argument("--format", choices=("human", "json"), default="human")
    return root


def validate_numeric_arguments(args: argparse.Namespace) -> None:
    if getattr(args, "samples", 1) <= 0:
        raise ReproError("--samples must be positive")
    if getattr(args, "sample_interval", 1.0) < 0:
        raise ReproError("--sample-interval must be non-negative")
    if getattr(args, "transition_timeout", 1.0) <= 0:
        raise ReproError("--transition-timeout must be positive")


def main() -> int:
    args = parser().parse_args()
    try:
        validate_numeric_arguments(args)
        if args.command == "capture":
            return capture(args)
        report, exit_code = analyze(args.evidence_dir)
        if args.format == "json":
            print(json.dumps(report, indent=2))
        else:
            print(f"{report['status']}: {report['findingCount']} finding(s)")
            for finding in report["findings"]:
                print(f"- {finding['code']}: {finding['message']}")
        return exit_code
    except ReproError as error:
        print(json.dumps({"schemaVersion": SCHEMA_VERSION, "status": "INVALID_EVIDENCE", "error": str(error)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
