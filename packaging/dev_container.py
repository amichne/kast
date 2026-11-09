"""Native Kast development containers with private, persistent repository copies."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import BinaryIO, TypeGuard

import yaml

ROOT = Path(__file__).resolve().parent.parent
PROFILE = "kast-dev"
VERSION = "0.0.0"


class Architecture(StrEnum):
    ARM64 = "arm64"
    AMD64 = "amd64"

    @property
    def machine(self) -> str:
        return "aarch64" if self is Architecture.ARM64 else "x86_64"

    @property
    def platform(self) -> str:
        return "linux-" + self.machine


class Failure(StrEnum):
    UNSUPPORTED_ARCHITECTURE = "UNSUPPORTED_ARCHITECTURE"
    MISSING_TOOL = "MISSING_TOOL"
    PROCESS_FAILED = "PROCESS_FAILED"
    INVALID_VM = "INVALID_VM"
    MISSING_IMAGE = "MISSING_IMAGE"
    INVALID_IMAGE = "INVALID_IMAGE"
    INVALID_WORKSPACE = "INVALID_WORKSPACE"
    POLICY_MISMATCH = "POLICY_MISMATCH"
    INVALID_OBSERVATION = "INVALID_OBSERVATION"
    IO_FAILED = "IO_FAILED"


@dataclass(frozen=True)
class Rejected:
    reason: Failure
    detail: str


@dataclass(frozen=True)
class Runtime:
    docker: tuple[str, ...]
    architecture: Architecture
    environment: dict[str, str]

    @property
    def image(self) -> str:
        return "kast-dev:" + self.architecture.value


@dataclass(frozen=True)
class Workspace:
    root: Path
    key: str
    name: str


def object_fields(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def fields(value: object) -> TypeGuard[dict[str, object]]:
    return object_fields(value) and all(isinstance(key, str) for key in value)


def elements(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def native_architecture(machine: str) -> Architecture | Rejected:
    match machine:
        case "arm64" | "aarch64":
            return Architecture.ARM64
        case "x86_64" | "amd64":
            return Architecture.AMD64
        case _:
            return Rejected(Failure.UNSUPPORTED_ARCHITECTURE, machine)


def validate_vm(config: Path, arch: Architecture) -> Architecture | Rejected:
    try:
        raw: object = yaml.safe_load(config.read_text())
    except (OSError, yaml.YAMLError):
        return Rejected(Failure.INVALID_VM, "Cannot read the effective Lima configuration")
    if not fields(raw) or raw.get("arch") != arch.machine or raw.get("mounts") not in (None, []):
        return Rejected(
            Failure.INVALID_VM, "Dedicated VM must match the CPU and have no host mounts"
        )
    ssh = raw.get("ssh")
    if not fields(ssh) or ssh.get("forwardAgent") is not False:
        return Rejected(Failure.INVALID_VM, "Dedicated VM must not forward an SSH agent")
    return arch


def invoke(
    args: list[str],
    environment: dict[str, str],
    *,
    capture: bool = False,
    stdin: BinaryIO | None = None,
    stdout: BinaryIO | None = None,
    cwd: Path = ROOT,
) -> subprocess.CompletedProcess[bytes] | Rejected:
    try:
        result = subprocess.run(
            args,
            env=environment,
            cwd=cwd,
            stdin=stdin,
            stdout=subprocess.PIPE if capture else (stdout if stdout is not None else sys.stderr),
            stderr=subprocess.PIPE if capture else None,
            check=False,
        )
    except OSError as error:
        return Rejected(Failure.IO_FAILED, type(error).__name__)
    if result.returncode != 0:
        return Rejected(Failure.PROCESS_FAILED, f"{Path(args[0]).name} exited {result.returncode}")
    return result


def effect_environment(source: Mapping[str, str]) -> dict[str, str]:
    selectors = {
        "DOCKER_HOST",
        "DOCKER_CONTEXT",
        "DOCKER_TLS",
        "DOCKER_TLS_VERIFY",
        "DOCKER_CERT_PATH",
        "DOCKER_DEFAULT_PLATFORM",
    }
    return {
        key: value
        for key, value in source.items()
        if key not in selectors and not key.startswith("GIT_")
    }


def runtime() -> Runtime | Rejected:
    arch = native_architecture(platform.machine())
    if isinstance(arch, Rejected):
        return arch
    if platform.system() != "Darwin":
        return Rejected(Failure.INVALID_VM, "This MVP uses a dedicated Colima VM on macOS")
    docker = shutil.which("docker") or shutil.which("docker", path="/opt/homebrew/opt/docker/bin")
    colima = shutil.which("colima")
    if docker is None or colima is None:
        return Rejected(
            Failure.MISSING_TOOL, "Install Docker CLI and Colima before running kast-container"
        )
    environment = effect_environment(os.environ)
    environment["PATH"] = (
        str(Path(docker).parent) + os.pathsep + environment.get("PATH", os.defpath)
    )
    result = invoke(
        [
            colima,
            "start",
            "--profile",
            PROFILE,
            "--arch",
            arch.machine,
            "--cpus",
            "4",
            "--memory",
            "12",
            "--disk",
            "48",
            "--mount",
            "none",
            "--vm-type",
            "vz",
            "--vz-rosetta=false",
            "--binfmt=false",
            "--ssh-agent=false",
            "--activate=false",
            "--port-forwarder",
            "none",
            "--template=false",
        ],
        environment,
    )
    if isinstance(result, Rejected):
        return result
    colima_home = Path(environment.get("COLIMA_HOME", str(Path.home() / ".colima")))
    validated = validate_vm(colima_home / "_lima" / ("colima-" + PROFILE) / "lima.yaml", arch)
    if isinstance(validated, Rejected):
        return validated
    selected = Runtime(
        (docker, "--host=unix://" + str(colima_home / PROFILE / "docker.sock")), arch, environment
    )
    info = observe(selected, ["info", "--format", "{{json .Architecture}}"])
    if isinstance(info, Rejected) or native_architecture(str(info)) != arch:
        return Rejected(Failure.INVALID_VM, "Docker daemon CPU does not match this host")
    return selected


def observe(runtime: Runtime, args: list[str]) -> object | Rejected:
    result = invoke([*runtime.docker, *args], runtime.environment, capture=True)
    if isinstance(result, Rejected):
        return result
    try:
        value: object = json.loads(result.stdout)
        return value
    except (json.JSONDecodeError, UnicodeDecodeError):
        return Rejected(Failure.INVALID_OBSERVATION, args[0])


def image_identity(runtime: Runtime) -> str | Rejected:
    listing = invoke(
        [*runtime.docker, "image", "ls", "--filter=reference=" + runtime.image, "--quiet"],
        runtime.environment,
        capture=True,
    )
    if isinstance(listing, Rejected):
        return listing
    if not listing.stdout.strip():
        return Rejected(Failure.MISSING_IMAGE, "Run kast-container build")
    raw = observe(runtime, ["image", "inspect", runtime.image])
    if not elements(raw) or len(raw) != 1 or not fields(raw[0]):
        return Rejected(Failure.INVALID_IMAGE, "Run kast-container build")
    image = raw[0]
    config = image.get("Config")
    labels = config.get("Labels") if fields(config) else None
    identity = image.get("Id")
    if (
        image.get("Architecture") != runtime.architecture.value
        or image.get("Os") != "linux"
        or not fields(labels)
        or labels.get("io.kast.dev-container") != "1"
        or not fields(config)
        or config.get("User") != "developer"
        or not isinstance(identity, str)
        or not identity.startswith("sha256:")
    ):
        return Rejected(Failure.INVALID_IMAGE, "Image is not a native Kast development image")
    return identity


def container_arguments(
    name: str,
    image: str,
    key: str,
    cpus: int,
    memory: int,
    offline: bool,
) -> list[str]:
    return [
        "create",
        "--init",
        "--name",
        name,
        "--label=io.kast.dev-container=1",
        "--label=io.kast.workspace=" + key,
        "--user=10001:10001",
        "--workdir=/work",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--pids-limit=2048",
        f"--cpus={cpus}",
        f"--memory={memory}g",
        f"--memory-swap={memory}g",
        "--network=none" if offline else "--network=bridge",
        image,
        "sleep",
        "infinity",
    ]


def workspace(path: Path, arch: Architecture) -> Workspace | Rejected:
    root = path.resolve()
    result = invoke(
        ["git", "-c", "core.fsmonitor=false", "rev-parse", "--show-toplevel"],
        effect_environment(os.environ),
        capture=True,
        cwd=root,
    )
    if isinstance(result, Rejected):
        return Rejected(Failure.INVALID_WORKSPACE, "Select a directory inside a Git repository")
    repository = Path(os.fsdecode(result.stdout).rstrip("\n")).resolve()
    if not root.is_relative_to(repository):
        return Rejected(
            Failure.INVALID_WORKSPACE, "Git repository identity differs from this directory"
        )
    root = repository
    key = hashlib.sha256(os.fsencode(root)).hexdigest()[:16]
    return Workspace(root, key, f"kast-work-{key}-{arch.value}")


def copy_workspace(runtime: Runtime, workspace: Workspace) -> Rejected | None:
    env = runtime.environment
    index = invoke(
        ["git", "-c", "core.fsmonitor=false", "ls-files", "--stage", "-z"],
        env,
        capture=True,
        cwd=workspace.root,
    )
    if isinstance(index, Rejected):
        return index
    if any(entry.startswith(b"160000 ") for entry in index.stdout.split(b"\0")):
        return Rejected(
            Failure.INVALID_WORKSPACE, "Submodules need a separate source copy; none was imported"
        )
    files = invoke(
        [
            "git",
            "-c",
            "core.fsmonitor=false",
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
        ],
        env,
        capture=True,
        cwd=workspace.root,
    )
    if isinstance(files, Rejected):
        return files
    with tempfile.TemporaryFile() as snapshot:
        with tarfile.open(fileobj=snapshot, mode="w") as archive:
            for raw in sorted(set(files.stdout.split(b"\0")) - {b""}):
                name = Path(os.fsdecode(raw))
                if name.is_absolute() or any(part in {"..", ".git"} for part in name.parts):
                    return Rejected(Failure.INVALID_WORKSPACE, "Unsafe repository path")
                source = workspace.root / name
                # Keep leaf symlinks as links, but never open indexed children
                # through a replaced directory outside the selected repository.
                if source.parent.resolve() != source.parent:
                    return Rejected(Failure.INVALID_WORKSPACE, "Symlinked repository directory")
                if source.is_file() or source.is_symlink():
                    archive.add(source, arcname=str(name), recursive=False)
        _ = snapshot.seek(0)
        copied = invoke(
            [
                *runtime.docker,
                "exec",
                "-i",
                workspace.name,
                "tar",
                "--no-same-owner",
                "-xf",
                "-",
                "-C",
                "/work",
            ],
            env,
            stdin=snapshot,
        )
    if isinstance(copied, Rejected):
        return copied
    commands = [
        ["git", "init", "--quiet", "--initial-branch=work"],
        ["git", "add", "--all", "--force"],
        [
            "git",
            "-c",
            "user.name=Kast workspace",
            "-c",
            "user.email=workspace@invalid",
            "-c",
            "commit.gpgsign=false",
            "commit",
            "--quiet",
            "--allow-empty",
            "-m",
            "Copied workspace baseline",
        ],
        [
            "python3",
            "/opt/kast/packaging/composed_bundle.py",
            "trust",
            "--root",
            "/opt/kast",
            "--workspace",
            "/work",
        ],
        [
            "python3",
            "-c",
            "from pathlib import Path; "
            "Path('/home/developer/.kast-workspace-ready').write_text('1\\n')",
        ],
    ]
    for command in commands:
        result = invoke([*runtime.docker, "exec", workspace.name, *command], env)
        if isinstance(result, Rejected):
            return result
    return None


def open_workspace(
    runtime: Runtime,
    workspace: Workspace,
    image: str,
    cpus: int,
    memory: int,
    offline: bool,
) -> Rejected | None:
    observed = invoke(
        [
            *runtime.docker,
            "container",
            "ls",
            "-a",
            "--filter",
            "name=^/" + workspace.name + "$",
            "--format",
            "{{.Names}}",
        ],
        runtime.environment,
        capture=True,
    )
    if isinstance(observed, Rejected):
        return observed
    if not observed.stdout.strip():
        # Docker otherwise injects proxy settings from the host client config,
        # even without --env. Creation needs no registry credentials: the image
        # was already admitted by immutable local identity.
        with tempfile.TemporaryDirectory(prefix="kast-docker-client-") as config:
            created = invoke(
                [
                    *runtime.docker,
                    *container_arguments(
                        workspace.name, image, workspace.key, cpus, memory, offline
                    ),
                ],
                {**runtime.environment, "DOCKER_CONFIG": config},
            )
        if isinstance(created, Rejected):
            return created
        started = invoke([*runtime.docker, "start", workspace.name], runtime.environment)
        if isinstance(started, Rejected):
            return started
        return copy_workspace(runtime, workspace)
    if observed.stdout.strip() != workspace.name.encode():
        return Rejected(Failure.INVALID_OBSERVATION, "workspace name")
    raw = observe(runtime, ["container", "inspect", workspace.name])
    if not elements(raw) or len(raw) != 1 or not fields(raw[0]):
        return Rejected(Failure.INVALID_OBSERVATION, "container inspection")
    container = raw[0]
    host = container.get("HostConfig")
    config = container.get("Config")
    labels = config.get("Labels") if fields(config) else None
    if (
        container.get("Image") != image
        or container.get("Mounts") != []
        or not fields(config)
        or config.get("User") != "10001:10001"
        or not fields(labels)
        or labels.get("io.kast.workspace") != workspace.key
        or labels.get("io.kast.dev-container") != "1"
        or not fields(host)
        or host.get("Privileged") is not False
        or host.get("CapDrop") != ["ALL"]
        or host.get("CapAdd") not in (None, [])
        or host.get("SecurityOpt") != ["no-new-privileges"]
        or host.get("NetworkMode") != ("none" if offline else "bridge")
    ):
        return Rejected(
            Failure.POLICY_MISMATCH,
            "Existing workspace differs from requested image or network mode; "
            "export it before replacing it",
        )
    if (
        host.get("NanoCpus") != cpus * 1_000_000_000
        or host.get("Memory") != memory * 1024**3
        or host.get("MemorySwap") != memory * 1024**3
        or host.get("PidsLimit") != 2048
    ):
        updated = invoke(
            [
                *runtime.docker,
                "update",
                f"--cpus={cpus}",
                f"--memory={memory}g",
                f"--memory-swap={memory}g",
                "--pids-limit=2048",
                workspace.name,
            ],
            runtime.environment,
        )
        if isinstance(updated, Rejected):
            return updated
    started = invoke([*runtime.docker, "start", workspace.name], runtime.environment)
    if isinstance(started, Rejected):
        return started
    ready = invoke(
        [
            *runtime.docker,
            "exec",
            workspace.name,
            "test",
            "-f",
            "/home/developer/.kast-workspace-ready",
        ],
        runtime.environment,
        capture=True,
    )
    return (
        Rejected(
            ready.reason,
            ready.detail + "; workspace readiness is unproven; existing files were preserved",
        )
        if isinstance(ready, Rejected)
        else None
    )


def build_image(runtime: Runtime) -> Rejected | None:
    options = ["-PcontrolVersion=" + VERSION, "-PhostedPluginVersion=" + VERSION, "--console=plain"]
    built = invoke(
        [
            str(ROOT / "gradlew"),
            ":cli:assembleNativeImageInputs",
            ":runtime:hosted:headlessBootstrapPlugin",
            "generateHostReleaseRecord",
            *options,
        ],
        runtime.environment,
    )
    if isinstance(built, Rejected):
        return built
    with tempfile.TemporaryDirectory(prefix="kast-container-build-") as directory:
        context = Path(directory)
        for source, destination in (
            (
                ROOT / "cli/build/distributions/native-inputs.tar.gz",
                context / "native-inputs.tar.gz",
            ),
            (ROOT / "packaging/container_compile.py", context / "container_compile.py"),
            (ROOT / "packaging/container/compiler.Dockerfile", context / "Dockerfile"),
        ):
            _ = shutil.copyfile(source, destination)
        compiler = "kast-native-compiler:" + runtime.architecture.value
        compiled = invoke(
            [
                *runtime.docker,
                "build",
                "--platform=linux/" + runtime.architecture.value,
                "-t",
                compiler,
                str(context),
            ],
            runtime.environment,
        )
        if isinstance(compiled, Rejected):
            return compiled
        name = "kast-compiler-export-" + uuid.uuid4().hex[:12]
        created = invoke(
            [*runtime.docker, "create", "--name", name, compiler, "true"], runtime.environment
        )
        if isinstance(created, Rejected):
            return created
        native = context / "kast"
        copied = invoke(
            [*runtime.docker, "cp", name + ":/out/kast", str(native)], runtime.environment
        )
        retired = invoke([*runtime.docker, "rm", name], runtime.environment)
        if isinstance(copied, Rejected):
            return copied
        if isinstance(retired, Rejected):
            return retired
        built = invoke(
            [
                str(ROOT / "gradlew"),
                "assembleKastControlDist",
                "verifyKastControlDistLayout",
                "-PkastNativeExecutable=" + str(native),
                "-PkastControlPlatform=" + runtime.architecture.platform,
                *options,
            ],
            runtime.environment,
        )
        if isinstance(built, Rejected):
            return built
        assets = context / "assets"
        assets.mkdir()
        for source in (
            ROOT
            / f"build/distributions/kast-control-v{VERSION}-{runtime.architecture.platform}.tar.gz",
            ROOT / f"runtime/hosted/build/distributions/kast-ide-hosted-v{VERSION}-idea-262.zip",
            ROOT / f"build/generated/host-release/kast-host-release-v{VERSION}.json",
            ROOT / "packaging/host-installation.py",
        ):
            destination = assets / source.name
            _ = shutil.copyfile(source, destination)
            with destination.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            _ = destination.with_name(destination.name + ".sha256").write_text(
                f"{digest}  {destination.name}\n"
            )
        _ = shutil.copyfile(
            ROOT
            / f"runtime/hosted/build/distributions/kast-managed-headless-v{VERSION}-idea-262.zip",
            context / "bootstrap.zip",
        )
        _ = shutil.copyfile(ROOT / "install.sh", context / "install.sh")
        modules = context / "packaging"
        modules.mkdir()
        for name in (
            "container_install.py",
            "managed_headless.py",
            "composed_bundle.py",
            "composed_host.py",
            "composition_descriptors.py",
            "composition_graph.py",
            "composition_index.py",
            "composition_platform.py",
            "composition_recipe.py",
        ):
            _ = shutil.copyfile(ROOT / "packaging" / name, modules / name)
        _ = shutil.copyfile(ROOT / "packaging/container/Dockerfile", context / "Dockerfile")
        result = invoke(
            [
                *runtime.docker,
                "build",
                "--platform=linux/" + runtime.architecture.value,
                "-t",
                runtime.image,
                str(context),
            ],
            runtime.environment,
        )
        return result if isinstance(result, Rejected) else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    _ = commands.add_parser(
        "build",
        help="Build the native image from this checkout; first build can take several minutes",
    )
    shell = commands.add_parser(
        "shell", help="Copy a repository once, then reopen its persistent development shell"
    )
    default_repository = Path.cwd()
    _ = shell.add_argument("--repository", type=Path, default=default_repository)
    _ = shell.add_argument("--offline", action="store_true", help="Disable the container network")
    _ = shell.add_argument("--cpus", type=int, choices=range(1, 9), default=4)
    _ = shell.add_argument("--memory-gib", type=int, choices=range(2, 13), default=8)
    _ = shell.add_argument("command", nargs=argparse.REMAINDER)
    export = commands.add_parser(
        "export", help="Save a workspace tarball without extracting or overwriting host files"
    )
    _ = export.add_argument("--repository", type=Path, default=default_repository)
    _ = export.add_argument("--output", type=Path, required=True)
    _ = commands.add_parser("status", help="List retained Kast workspace containers")
    arguments = sys.argv[1:]
    if not arguments or arguments[0] not in {"build", "shell", "status", "export", "-h", "--help"}:
        arguments = ["shell", *arguments]
    args = parser.parse_args(arguments)
    try:
        selected = runtime()
        if isinstance(selected, Rejected):
            return report(selected)
        if args.action == "build":
            result = build_image(selected)
            return report(result) if isinstance(result, Rejected) else 0
        if args.action == "status":
            status = invoke(
                [*selected.docker, "ps", "-a", "--filter", "label=io.kast.workspace"],
                selected.environment,
            )
            return report(status) if isinstance(status, Rejected) else 0
        admitted = workspace(Path(args.repository), selected.architecture)
        if isinstance(admitted, Rejected):
            return report(admitted)
        if args.action == "export":
            with Path(args.output).open("xb") as output:
                result = invoke(
                    [
                        *selected.docker,
                        "exec",
                        admitted.name,
                        "tar",
                        "--exclude=./.git",
                        "-czf",
                        "-",
                        "-C",
                        "/work",
                        ".",
                    ],
                    selected.environment,
                    stdout=output,
                )
            return report(result) if isinstance(result, Rejected) else 0
        image = image_identity(selected)
        if isinstance(image, Rejected) and image.reason is Failure.MISSING_IMAGE:
            print("kast-container: building the first native image", file=sys.stderr)
            built = build_image(selected)
            if isinstance(built, Rejected):
                return report(built)
            image = image_identity(selected)
            if isinstance(image, Rejected):
                return report(image)
        if isinstance(image, Rejected):
            return report(image)
        opened = open_workspace(
            selected, admitted, image, int(args.cpus), int(args.memory_gib), bool(args.offline)
        )
        if isinstance(opened, Rejected):
            return report(opened)
        command = list(args.command)
        if command[:1] == ["--"]:
            command = command[1:]
        if not command:
            command = ["bash"]
        flags = ["-it"] if sys.stdin.isatty() and sys.stdout.isatty() else ["-i"]
        print(
            f"kast-container: {admitted.name}; /work is a retained copy, not a host mount",
            file=sys.stderr,
        )
        # Pass the actual command exit code through; a failed command is not a setup error.
        result = subprocess.run(
            [*selected.docker, "exec", *flags, admitted.name, *command],
            env=selected.environment,
            check=False,
        )
        return result.returncode
    except KeyboardInterrupt:
        return 130
    except (OSError, tarfile.TarError) as error:
        return report(Rejected(Failure.IO_FAILED, type(error).__name__))


def report(rejected: Rejected) -> int:
    print(f"kast-container: outcome={rejected.reason.value}; {rejected.detail}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
