"""Owned filesystem and environment for installer boundary tests."""
from __future__ import annotations

from enum import Enum
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from types import MappingProxyType
from typing import Mapping


class FixtureFailure(Enum):
    INVALID_TOOL = "invalid-tool"
    INVALID_ROOT = "invalid-root"
    INVALID_INPUT = "invalid-input"
    CLEANUP_FAILED = "cleanup-failed"


class FixtureRejected(ValueError):
    def __init__(self, condition: FixtureFailure):
        self.condition = condition
        super().__init__(condition.value)


def admitted_tools() -> dict[str, Path]:
    """Resolve tools explicitly so installer children do not inherit ambient PATH."""
    tools = {"python3": Path(sys.executable)}
    for name in ("ps", "git", "bash", "zsh", "sh", "uname", "dirname", "basename", "readlink", "sed",
                 "awk", "grep", "find", "sort", "unzip", "shasum", "pgrep", "mkdir",
                 "cat", "head", "tail", "tr", "cut", "xargs", "expr", "sleep", "date", "ls", "which", "env",
                 "tar", "curl", "wc", "mktemp", "mv", "cp", "chmod", "ln", "rm", "touch"):
        selected = shutil.which(name)
        if selected is not None:
            tools[name] = Path(selected).resolve()
    selected = os.environ.get("KAST_ACCEPTANCE_JAVA_EXECUTABLE")
    if selected is not None:
        tools["java"] = Path(selected)
    return tools


class InstallerFixture:
    """One private root; successful cases remove only their original root."""

    def __init__(self, tools: Mapping[str, Path], *, parent: Path | None = None):
        validated = {}
        for name, candidate in tools.items():
            path = Path(candidate)
            if (re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", name) is None
                    or not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK)):
                raise FixtureRejected(FixtureFailure.INVALID_TOOL)
            validated[name] = path
        if not validated:
            raise FixtureRejected(FixtureFailure.INVALID_TOOL)
        parent = Path(tempfile.gettempdir()).resolve() if parent is None else Path(parent)
        if not parent.is_absolute() or not parent.is_dir() or parent.is_symlink():
            raise FixtureRejected(FixtureFailure.INVALID_ROOT)
        self.root = Path(tempfile.mkdtemp(prefix="kast-install-", dir=parent.resolve())).resolve()
        self.root.chmod(0o700)
        self._identity = self.root.stat().st_dev, self.root.stat().st_ino
        self._passed = False
        for name in ("home", "home/.codex", "config", "data", "state", "cache", "gradle",
                     "run", "tmp", "tools", "workspace"):
            (self.root / name).mkdir(mode=0o700, parents=True, exist_ok=True)
        for name, path in validated.items():
            (self.root / "tools" / name).symlink_to(path)
        self.tools = MappingProxyType(validated)
        home = self.root / "home"
        java_options = f'-Duser.home="{home}" -Djava.io.tmpdir="{self.root / "tmp"}"'
        self.environment = MappingProxyType({
            "PATH": str(self.root / "tools"), "HOME": str(home),
            "CODEX_HOME": str(home / ".codex"), "GRADLE_USER_HOME": str(self.root / "gradle"),
            "XDG_CONFIG_HOME": str(self.root / "config"), "XDG_DATA_HOME": str(self.root / "data"),
            "XDG_STATE_HOME": str(self.root / "state"), "XDG_CACHE_HOME": str(self.root / "cache"),
            "XDG_RUNTIME_DIR": str(self.root / "run"), "TMPDIR": str(self.root / "tmp"),
            "TMP": str(self.root / "tmp"), "TEMP": str(self.root / "tmp"),
            "_JAVA_OPTIONS": java_options, "JAVA_TOOL_OPTIONS": java_options,
            "LANG": "C.UTF-8", "LC_ALL": "C", "TZ": "UTC",
            "KAST_RUNTIME_DIRECTORY": str(self.root / "run"),
            "HTTP_PROXY": "http://127.0.0.1:9", "HTTPS_PROXY": "http://127.0.0.1:9",
            "ALL_PROXY": "http://127.0.0.1:9", "NO_PROXY": "127.0.0.1,localhost,::1",
        })

    def stage_product(self, source: Path) -> Path:
        if (not source.is_absolute() or not source.is_dir()
                or self.root.is_relative_to(source.resolve())):
            raise FixtureRejected(FixtureFailure.INVALID_INPUT)
        destination = self.root / "product"
        shutil.copytree(source, destination)
        self.adopt_product(destination)
        return destination

    def adopt_product(self, product: Path) -> None:
        if product.resolve() != product or not product.is_dir() or not product.is_relative_to(self.root):
            raise FixtureRejected(FixtureFailure.INVALID_INPUT)
        self.environment = MappingProxyType(dict(self.environment,
            KAST_RUNTIME_DIRECTORY=str(product / "state/run")))

    def mark_passed(self):
        self._passed = True

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception, traceback):
        try:
            owned = (not self.root.is_symlink() and self.root.is_dir()
                     and (self.root.stat().st_dev, self.root.stat().st_ino) == self._identity)
        except OSError:
            owned = False
        if not owned:
            raise FixtureRejected(FixtureFailure.INVALID_ROOT)
        if self._passed and exception_type is None:
            try:
                shutil.rmtree(self.root)
            except OSError as error:
                raise FixtureRejected(FixtureFailure.CLEANUP_FAILED) from error
        else:
            print(f'installer fixture retained: {self.root}', file=sys.stderr)
