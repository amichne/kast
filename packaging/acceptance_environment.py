"""Private installed-acceptance state and explicitly admitted executable inputs.

This is process-environment isolation, not an OS security sandbox. Tool binaries
and read-only product inputs remain external; no credential or user cache is
copied. Network-dependent acceptance must provision its own dependencies.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from types import MappingProxyType
from typing import Mapping


class EnvironmentFailure(Enum):
    INVALID_TOOL = "invalid-tool"
    INVALID_ROOT = "invalid-root"
    INVALID_INPUT = "invalid-input"
    CLEANUP_FAILED = "cleanup-failed"


class EnvironmentRejected(ValueError):
    def __init__(self, condition: EnvironmentFailure):
        self.condition = condition
        super().__init__(condition.value)


class NetworkPolicy(Enum):
    CLOSED_PROXY = "closed-proxy"
    DEPENDENCY_DOWNLOADS = "dependency-downloads"


class CleanupStage(Enum):
    PROCESSES = "owned-processes"
    ALIAS = "endpoint-alias"
    TREE = "fixture-tree"


class CleanupOutcome(Enum):
    PENDING = "pending"
    REMOVED = "removed"
    RETAINED_FAILURE = "retained-failure"
    RETAINED_CLEANUP_FAILURE = "retained-cleanup-failure"


class GradleRetirement(Enum):
    RETIRED = "retired"
    REJECTED = "rejected"
    TIMED_OUT = "timed-out"


@dataclass(frozen=True)
class GradleDaemonIdentity:
    pid: int
    started: str
    command: str


class AliasCleanup(Enum):
    ABSENT = "absent"
    RETIRED = "retired"
    REJECTED = "rejected"


def admitted_tools() -> dict[str, Path]:
    """Resolve the small tool boundary once; children never inherit ambient PATH.

    Optional explicit Java/Node inputs support Gradle and installed Codex shims.
    Every selected executable is subsequently validated by the fixture.
    """
    tools = {"python3": Path(sys.executable)}
    for name in ("ps", "git", "bash", "zsh", "sh", "uname", "dirname", "basename", "readlink", "sed",
                 "awk", "grep", "find", "sort", "unzip", "shasum", "pgrep", "mkdir",
                 "cat", "head", "tail", "tr", "cut", "xargs", "expr", "sleep", "date", "ls", "which", "env",
                 "tar", "curl", "wc", "mktemp", "mv", "cp", "chmod", "ln", "rm", "touch"):
        for directory in (Path("/usr/bin"), Path("/bin")):
            candidate = directory / name
            if candidate.is_file():
                tools[name] = candidate
                break
    for name in ("java", "node"):
        selected = os.environ.get("KAST_ACCEPTANCE_" + name.upper() + "_EXECUTABLE")
        if selected is None and name == "java" and "JAVA_HOME" in os.environ:
            selected = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
        if selected is None:
            selected = shutil.which(name)
        if selected is not None:
            tools[name] = Path(selected)
    return tools


class AcceptanceEnvironment:
    """One exclusively created root; only handles launched here may be terminated."""

    def __init__(self, tools: Mapping[str, Path], *, parent: Path = Path("/tmp"),
                 network: NetworkPolicy = NetworkPolicy.CLOSED_PROXY):
        if not isinstance(network, NetworkPolicy):
            raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
        validated = {}
        for name, candidate in tools.items():
            path = Path(candidate)
            if (re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", name) is None
                    or not path.is_absolute() or not path.is_file()
                    or not os.access(path, os.X_OK)):
                raise EnvironmentRejected(EnvironmentFailure.INVALID_TOOL)
            validated[name] = path
        if not validated:
            raise EnvironmentRejected(EnvironmentFailure.INVALID_TOOL)
        if not parent.is_absolute() or not parent.is_dir() or parent.is_symlink():
            # /tmp is an OS-owned symlink on macOS; its canonical target is admitted.
            if parent != Path("/tmp") or not parent.is_dir():
                raise EnvironmentRejected(EnvironmentFailure.INVALID_ROOT)
        self.root = Path(tempfile.mkdtemp(prefix="kast-a-", dir=parent.resolve())).resolve()
        self.root.chmod(0o700)
        self._identity = self.root.stat().st_dev, self.root.stat().st_ino
        self._processes: list[subprocess.Popen] = []
        self._passed = False
        self.cleanup_outcome = CleanupOutcome.PENDING
        self._cleanup_report = None
        self._product = self.root / "product"
        for name in ("home", "home/.codex", "config", "data", "state", "cache", "gradle",
                     "run", "store", "tmp", "tools", "workspace"):
            (self.root / name).mkdir(mode=0o700, parents=True, exist_ok=True)
        for name, path in validated.items():
            (self.root / "tools" / name).symlink_to(path)
        self.tools = MappingProxyType(validated)
        home = self.root / "home"
        java_options = f'-Duser.home="{home}" -Djava.io.tmpdir="{self.root / "tmp"}"'
        environment = {
            "PATH": str(self.root / "tools"), "HOME": str(home),
            "CODEX_HOME": str(home / ".codex"), "GRADLE_USER_HOME": str(self.root / "gradle"),
            "XDG_CONFIG_HOME": str(self.root / "config"), "XDG_DATA_HOME": str(self.root / "data"),
            "XDG_STATE_HOME": str(self.root / "state"), "XDG_CACHE_HOME": str(self.root / "cache"),
            "XDG_RUNTIME_DIR": str(self.root / "run"), "TMPDIR": str(self.root / "tmp"),
            "TMP": str(self.root / "tmp"), "TEMP": str(self.root / "tmp"),
            "_JAVA_OPTIONS": java_options, "JAVA_TOOL_OPTIONS": java_options,
            "LANG": "C.UTF-8", "LC_ALL": "C", "TZ": "UTC",
            "KAST_RUNTIME_STORE": str(self.root / "store"),
            "KAST_RUNTIME_DIRECTORY": str(self.root / "run"),
            "KAST_CACHE_ROOT": str(self.root / "cache"),
            "KAST_ENABLE_LAUNCHD": "0", "KAST_ENABLE_APP_SERVER": "0",
        }
        if network is NetworkPolicy.CLOSED_PROXY:
            # Deliberately mirror the pinned native Codex proof's child-only policy.
            # This is a proxy policy for cooperative tools, not kernel network isolation.
            for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
                environment[name] = "http://127.0.0.1:9"
            environment["NO_PROXY"] = environment["no_proxy"] = "127.0.0.1,localhost,::1"
        self.environment = MappingProxyType(environment)

    def stage_product(self, source: Path) -> Path:
        if (not source.is_absolute() or not source.is_dir()
                or self.root.is_relative_to(source.resolve())):
            raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
        destination = self.root / "product"
        shutil.copytree(source, destination)
        self.adopt_product(destination)
        return destination

    def adopt_product(self, product: Path) -> None:
        if (product.resolve() != product or not product.is_dir() or not product.is_relative_to(self.root)):
            raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
        self._product = product
        environment = dict(self.environment)
        environment.update(KAST_RUNTIME_STORE=str(product / "runtime-payloads"),
                           KAST_RUNTIME_DIRECTORY=str(product / "state/run"),
                           KAST_CACHE_ROOT=str(product / "state/cache"))
        self.environment = MappingProxyType(environment)

    def spawn(self, command, **kwargs):
        kwargs.setdefault("env", dict(self.environment))
        kwargs.setdefault("cwd", self.root / "workspace")
        process = subprocess.Popen(command, **kwargs)
        self._processes.append(process)
        return process

    def mark_passed(self):
        self._passed = True

    def report_after_cleanup(self, path: Path, document: dict) -> None:
        if not path.is_absolute() or path.is_relative_to(self.root):
            raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
        self._cleanup_report = (path, document)
        document['passed'] = False
        document['fixtureCleanup'] = {'outcome': CleanupOutcome.PENDING.value, 'retained': True}
        path.write_text(json.dumps(document, indent=2) + '\n')

    def _gradle_daemons(self) -> tuple[GradleDaemonIdentity, ...]:
        result = subprocess.run([str(self.tools['ps']), '-axo', 'pid=,lstart=,command='],
            env=self.environment, capture_output=True, text=True, timeout=5, check=True)
        if len(result.stdout) > 4 * 1024 * 1024:
            raise EnvironmentRejected(EnvironmentFailure.CLEANUP_FAILED)
        identities = []
        private_classpath = str(self.root / 'gradle/wrapper/dists') + '/'
        for line in result.stdout.splitlines():
            fields = line.strip().split(None, 6)
            if len(fields) != 7:
                continue
            command = fields[6]
            tokens = command.split()
            if ('org.gradle.launcher.daemon.bootstrap.GradleDaemon' not in tokens
                    or not any(token.startswith(private_classpath) and '/lib/gradle-daemon-main-' in token for token in tokens)):
                continue
            identities.append(GradleDaemonIdentity(int(fields[0]), ' '.join(fields[1:6]), command))
        return tuple(identities)

    def capture_gradle_daemons(self) -> tuple[GradleDaemonIdentity, ...]:
        """Capture only JVMs whose actual daemon classpath is inside this exclusive Gradle home."""
        return self._gradle_daemons()

    def await_gradle_retirement(self, captured: tuple[GradleDaemonIdentity, ...], timeout: float = 15) -> GradleRetirement:
        """The stop acknowledgement is not process exit. Observe exact identities; never signal a PID."""
        deadline = time.monotonic() + timeout
        expected = set(captured)
        try:
            while True:
                current = set(self._gradle_daemons())
                if not current:
                    return GradleRetirement.RETIRED
                if not current.issubset(expected):
                    return GradleRetirement.REJECTED
                if time.monotonic() >= deadline:
                    return GradleRetirement.TIMED_OUT
                time.sleep(0.1)
        except (OSError, ValueError, subprocess.SubprocessError):
            return GradleRetirement.REJECTED

    def _retire_endpoint_alias(self) -> AliasCleanup:
        target = self._product / "state/run"
        receipt = target / "endpoint-alias.json"
        if not receipt.exists() and not receipt.is_symlink():
            return AliasCleanup.ABSENT
        try:
            if (receipt.is_symlink() or not receipt.is_file() or receipt.stat().st_size > 4096
                    or receipt.stat().st_uid != os.getuid() or receipt.stat().st_mode & 0o777 != 0o600
                    or target.resolve() != target or target.stat().st_mode & 0o777 != 0o700):
                return AliasCleanup.REJECTED
            with receipt.open("rb") as stream:
                encoded = stream.read(4097)
            if len(encoded) > 4096:
                return AliasCleanup.REJECTED
            document = json.loads(encoded)
            alias = Path("/tmp") / ("kast-uds-" + hashlib.sha256(str(target).encode()).hexdigest()[:32])

            def identity(path):
                observed = path.lstat()
                return {"device": observed.st_dev, "inode": observed.st_ino, "owner": observed.st_uid}

            if (set(document) != {"schemaVersion", "alias", "target", "aliasIdentity", "targetIdentity"}
                    or document["schemaVersion"] != 1 or document["alias"] != str(alias)
                    or document["target"] != str(target) or document["targetIdentity"] != identity(target)):
                return AliasCleanup.REJECTED
            if not alias.exists() and not alias.is_symlink():
                return AliasCleanup.ABSENT
            if (not alias.is_symlink() or os.readlink(alias) != str(target)
                    or alias.lstat().st_uid != os.getuid() or document["aliasIdentity"] != identity(alias)):
                return AliasCleanup.REJECTED
            alias.unlink()
            return AliasCleanup.RETIRED
        except (OSError, ValueError, KeyError, TypeError):
            return AliasCleanup.REJECTED

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception, traceback):
        failed_stage = None
        root_owned = False
        try:
            for process in reversed(self._processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
        except (OSError, subprocess.SubprocessError):
            failed_stage = CleanupStage.PROCESSES
        try:
            root_owned = (not self.root.is_symlink() and self.root.is_dir()
                and (self.root.stat().st_dev, self.root.stat().st_ino) == self._identity)
        except OSError:
            root_owned = False
        if not root_owned:
            failed_stage = CleanupStage.TREE
        checks_passed = self._passed and exception_type is None
        if checks_passed and failed_stage is None:
            if self._retire_endpoint_alias() is AliasCleanup.REJECTED:
                failed_stage = CleanupStage.ALIAS
            else:
                try:
                    shutil.rmtree(self.root)
                except OSError:
                    failed_stage = CleanupStage.TREE
        self.cleanup_outcome = (CleanupOutcome.RETAINED_CLEANUP_FAILURE if failed_stage is not None
            else CleanupOutcome.REMOVED if checks_passed else CleanupOutcome.RETAINED_FAILURE)
        self._passed = self.cleanup_outcome is CleanupOutcome.REMOVED
        terminal = {'outcome': self.cleanup_outcome.value, 'retained': not self._passed,
                    'stage': failed_stage.value if failed_stage else CleanupStage.TREE.value}
        if not self._passed:
            # Partial deletion may have occurred. Preserve survivors; never retry recursively.
            if root_owned and self.root.is_dir() and not self.root.is_symlink():
                try:
                    if (self.root.stat().st_dev, self.root.stat().st_ino) == self._identity:
                        (self.root / 'acceptance-outcome.json').write_text(json.dumps({
                            'schemaVersion': 1, 'outcome': 'cleanup-failed' if failed_stage else 'failed',
                            'retained': True, 'ownedProcessCount': len(self._processes),
                            'cleanup': terminal,
                        }) + '\n')
                except OSError:
                    pass  # The external terminal report still records the finite failure.
            print(f'acceptance fixture retained: {self.root}', file=sys.stderr)
        if self._cleanup_report is not None:
            path, document = self._cleanup_report
            document['fixtureCleanup'] = terminal
            document['passed'] = self._passed
            path.write_text(json.dumps(document, indent=2) + '\n')
        if failed_stage is not None:
            raise EnvironmentRejected(EnvironmentFailure.CLEANUP_FAILED if root_owned else EnvironmentFailure.INVALID_ROOT)
