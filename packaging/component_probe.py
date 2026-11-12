"""Test-native process boundary; never grants installed-product qualification."""

from __future__ import annotations

import json
import os
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

from managed_headless import object_fields


@dataclass(frozen=True)
class ComponentInvocation:
    command: tuple[str, ...]
    exitStatus: int


@dataclass(frozen=True)
class WorkspacePaths:
    workspaceState: Path
    runtimeHome: Path
    home: Path
    javaHome: Path
    workspaceDirectory: Path

    @staticmethod
    def read(raw: bytes, store: Path) -> WorkspacePaths:
        value: object = json.loads(raw)
        if not object_fields(value) or value.get("type") != "INSPECTED":
            raise ValueError("component inspection must be an object")
        paths: list[Path] = []
        for key in ("workspaceState", "runtimeHome", "home", "javaHome"):
            item: object = value.get(key)
            if not isinstance(item, str):
                raise ValueError("component inspection is missing an admitted path")
            path = Path(item)
            if not path.is_absolute() or path.resolve() != path or not path.is_relative_to(store):
                raise ValueError("component inspection escaped its owned store")
            paths.append(path)
        state, runtime, home, java = paths
        workspace_id = value.get("workspaceId")
        if (
            not isinstance(workspace_id, str)
            or re.fullmatch("[a-f0-9]{32}|[a-f0-9]{64}", workspace_id) is None
        ):
            raise ValueError("component inspection lost workspace identity")
        directory = store / "containers" / workspace_id
        if (
            home != state / "home"
            or not java.is_relative_to(runtime)
            or not state.is_relative_to(directory)
        ):
            raise ValueError("component inspection lost private runtime ownership")
        return WorkspacePaths(state, runtime, home, java, directory)


@dataclass(frozen=True)
class ComponentHarness:
    executable: Path
    root: Path
    archive: Path
    digest: str
    version: str
    control: Path
    control_digest: str
    revision: str
    sandbox: Path | None = None

    @property
    def store(self) -> Path:
        return self.root / "store"

    def run(
        self,
        case: str,
        workspace: Path,
        arguments: tuple[str, ...],
        evidence: Path,
    ) -> subprocess.CompletedProcess[bytes]:
        prefix = (
            ("/usr/bin/sandbox-exec", "-f", str(self.sandbox)) if self.sandbox is not None else ()
        )
        command = (
            *prefix,
            str(self.executable),
            "--store",
            str(self.store),
            "--runtime-archive",
            str(self.archive),
            "--runtime-sha256",
            self.digest,
            "--version",
            self.version,
            "--control",
            str(self.control),
            "--control-sha256",
            self.control_digest,
            "--source-revision",
            self.revision,
            "--workspace",
            str(workspace),
            *(
                arguments
                if arguments[0] in ("admit", "inspect", "stop")
                else ("exec", "--", *arguments)
            ),
        )
        result = subprocess.run(
            command,
            cwd=workspace,
            env={
                "PATH": os.defpath,
                "HOME": str(self.root / "home"),
                "TMPDIR": str(self.root / "tmp"),
            },
            capture_output=True,
            timeout=1080,
            start_new_session=True,
            check=False,
        )
        _ = (evidence / f"{case}.stdout").write_bytes(result.stdout)
        _ = (evidence / f"{case}.stderr").write_bytes(result.stderr)
        _ = (evidence / f"{case}.invocation.json").write_text(
            json.dumps(asdict(ComponentInvocation(command, result.returncode)), indent=2) + "\n"
        )
        return result

    def inspect(self, workspace: Path, evidence: Path, case: str) -> WorkspacePaths:
        result = self.run(case, workspace, ("inspect",), evidence)
        if result.returncode != 0:
            raise RuntimeError(f"component inspection rejected; see {evidence}")
        return WorkspacePaths.read(result.stdout, self.store)
