"""Bind the private launchers to the installation that created them."""

from __future__ import annotations

import json
import os
import platform
import re
import shlex
import shutil
import stat
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

from composition_platform import HostPlatform, UnsupportedHost, host_platform
from managed_headless import Failure, Rejected, object_fields, selected_private_profile

ZIPAPP = "bin/setup-kast.pyz"
INSTALLED_APP = "libexec/setup-kast.pyz"
IDENTITY = "isolated-runtime.json"
LINUX_SUPPORT = "lib/kast-linux-support"


@dataclass(frozen=True, slots=True)
class RootIdentity:
    root: str
    platform: HostPlatform
    version: str
    schemaVersion: Literal[1] = 1


def write_launchers(root: Path, target: HostPlatform, version: str, zipapp: Path) -> None:
    app = root / INSTALLED_APP
    _ = shutil.copyfile(zipapp, app)
    app.chmod(0o400)
    record = root / IDENTITY
    with record.open("x") as output:
        _ = output.write(json.dumps(asdict(RootIdentity(str(root), target, version))) + "\n")
    record.chmod(0o400)
    for name in ("kast", "kast-task"):
        command = root / "bin" / name
        script = (
            "#!/bin/sh\n"
            "set -eu\n"
            'case "$0" in */*) directory=${0%/*} ;; *) exit 2 ;; esac\n'
            f'exec {shlex.quote(sys.executable)} -I "$directory/../{INSTALLED_APP}" '
            f'--installed-launcher {name} "$0" "$@"\n'
        )
        with command.open("x") as output:
            _ = output.write(script)
        command.chmod(0o700)


@dataclass(frozen=True, slots=True)
class PrivateRuntime:
    root: Path
    target: HostPlatform

    @property
    def state(self) -> Path:
        return self.root / "state"

    @property
    def home(self) -> Path:
        return self.state / "home"

    @property
    def control(self) -> Path:
        return self.home / ".local/share/kast/installation"

    @property
    def java(self) -> Path:
        relative = "jbr" if self.target.is_linux else "jbr/Contents/Home"
        return self.root / self.target.layout.home / relative


def read_record(path: Path) -> object:
    if not path.is_file() or path.resolve() != path:
        return None
    with path.open("rb") as source:
        raw = source.read(65_537)
    return json.loads(raw) if len(raw) <= 65_536 else None


def admit_runtime(root: Path) -> PrivateRuntime | Rejected:
    target = host_platform(platform.system(), platform.machine())
    if isinstance(target, UnsupportedHost):
        return Rejected(Failure.UNSUPPORTED_PLATFORM)
    if root.resolve() != root or not root.is_dir():
        return Rejected(Failure.INVALID_INPUT)
    status = root.stat()
    if status.st_uid != os.getuid() or stat.S_IMODE(status.st_mode) != 0o700:
        return Rejected(Failure.SHARED_STATE)
    record = read_record(root / IDENTITY)
    if not object_fields(record):
        return Rejected(Failure.INVALID_COMPONENT)
    version = record.get("version")
    if (
        not isinstance(version, str)
        or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
        or type(record.get("schemaVersion")) is not int
        or record != asdict(RootIdentity(str(root), target, version))
    ):
        return Rejected(Failure.INVALID_COMPONENT)
    runtime = PrivateRuntime(root, target)
    host = root / target.layout.home
    assembly = read_record(host / "kast-headless.json")
    if not object_fields(assembly) or assembly.get("installation") != str(host):
        return Rejected(Failure.INVALID_COMPONENT)
    selection = read_record(runtime.control / "config/selected-ide.json")
    if not object_fields(selection) or selection.get("home") != str(host):
        return Rejected(Failure.INVALID_COMPONENT)
    profile = selected_private_profile(runtime.state)
    if isinstance(profile, Rejected):
        return profile
    directories = (
        runtime.home,
        runtime.home / ".gradle",
        runtime.state / "tmp",
        runtime.state / "system",
        runtime.state / "log",
        runtime.control,
        profile.directory / "plugins",
        runtime.java,
    )
    if any(not path.is_dir() or path.resolve() != path for path in directories):
        return Rejected(Failure.SHARED_STATE)
    return runtime


def child_environment(runtime: PrivateRuntime) -> dict[str, str]:
    # Workspace Gradle inputs remain explicit; installation and process selectors are private.
    excluded = {
        "BASH_ENV",
        "ENV",
        "CDPATH",
        "JAVA_HOME",
        "JAVA",
        "JDK_HOME",
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "GRADLE_OPTS",
        "CODEX_HOME",
        "CODEX_EXECUTABLE",
        "LD_LIBRARY_PATH",
        "LD_PRELOAD",
    }
    workspace_inputs = {
        "KAST_GRADLE_JAVA_HOME",
        "KAST_GRADLE_IMPORT_PATH",
        "KAST_GRADLE_IMPORT_VARIABLES",
        "KAST_OUTPUT_TOON",
        "KAST_DEBUG",
        "KAST_ASCII",
        "KAST_WORKSPACE_PREPARATION_WAIT",
    }
    env = {
        key: value
        for key, value in os.environ.items()
        if key not in excluded
        and (
            key in workspace_inputs
            or not key.startswith(("KAST_", "IDEA_", "XDG_", "DYLD_", "PYTHON"))
        )
    }
    caller_path = (
        path
        for path in os.environ.get("PATH", os.defpath).split(os.pathsep)
        if Path(path).is_absolute() and not any(character in path for character in "\r\n")
    )
    path = (
        str(runtime.root / "bin"),
        str(runtime.root / "libexec"),
        str(runtime.java / "bin"),
        *caller_path,
        *os.defpath.split(":"),
    )
    env.update(
        HOME=str(runtime.home),
        JAVA_HOME=str(runtime.java),
        TMPDIR=str(runtime.state / "tmp"),
        XDG_CONFIG_HOME=str(runtime.home / ".config"),
        XDG_DATA_HOME=str(runtime.home / ".local/share"),
        XDG_CACHE_HOME=str(runtime.home / ".cache"),
        XDG_STATE_HOME=str(runtime.home / ".local/state"),
        XDG_RUNTIME_DIR=str(runtime.state / "tmp"),
        CODEX_HOME=str(runtime.home / ".codex"),
        GRADLE_USER_HOME=str(runtime.home / ".gradle"),
        KAST_MANAGED_HEADLESS_STATE=str(runtime.state),
        KAST_INSTALL_ROOT=str(runtime.root),
        PATH=os.pathsep.join(dict.fromkeys(path)),
    )
    return env


def launch(name: str, invocation: str, arguments: list[str]) -> int:
    from composed_bundle import BundleRejected, admit_trust_workspace, configure_trusted_workspace

    script = Path(os.path.abspath(invocation))
    root = script.parent.parent
    try:
        if (
            name not in {"kast", "kast-task"}
            or script != root / "bin" / name
            or script.resolve() != script
            or Path(sys.argv[0]).resolve() != root / INSTALLED_APP
        ):
            raise ValueError("invalid launcher")
        runtime = admit_runtime(root)
        if isinstance(runtime, Rejected):
            print(f"{name}: {runtime.reason}", file=sys.stderr)
            return 2
        env = child_environment(runtime)
        if name == "kast":
            command = runtime.home / ".local/bin/kast"
            if not command.is_file() or command.resolve() != command:
                raise ValueError("invalid Control")
            configuration = runtime.control / "config/environment"
            if not configuration.is_file() or configuration.resolve() != configuration:
                raise ValueError("invalid Control configuration")
            env["KAST_CONFIGURATION_FILE"] = str(configuration)
            env["KAST_INSTALL_ROOT"] = str(runtime.control.parent)
            arguments = [str(command), *arguments]
        else:
            if len(arguments) < 3 or arguments[1] != "--" or not arguments[2]:
                print("Usage: kast-task WORKSPACE -- COMMAND [ARGS...]", file=sys.stderr)
                return 2
            workspace = admit_trust_workspace(Path(arguments[0]), root)
            if isinstance(workspace, BundleRejected):
                print(f"{name}: {workspace.reason}", file=sys.stderr)
                return 2
            grant = configure_trusted_workspace(runtime.state, workspace)
            if isinstance(grant, Rejected):
                print(f"{name}: {grant.reason}", file=sys.stderr)
                return 2
            fence = runtime.control.parent / "shutdown.json"
            if fence.exists() or fence.is_symlink():
                result = subprocess.run(
                    [str(root / "bin/kast"), "--internal-install", "resume-isolated"],
                    env=env,
                    check=False,
                )
                if result.returncode != 0:
                    return result.returncode if result.returncode > 0 else 128 - result.returncode
            os.chdir(grant.workspace)
            arguments = arguments[2:]
    except (OSError, ValueError, RuntimeError):
        print(f"{name}: INVALID_INSTALLATION_OR_WORKSPACE", file=sys.stderr)
        return 2
    try:
        os.execvpe(arguments[0], arguments, env)
    except FileNotFoundError:
        print(f"{name}: COMMAND_NOT_FOUND", file=sys.stderr)
        return 127
    except OSError:
        print(f"{name}: COMMAND_START_FAILED", file=sys.stderr)
        return 126
