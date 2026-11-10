"""Build a native Kast development image from a source checkout."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import TypeGuard

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
    INVALID_CHECKOUT = "INVALID_CHECKOUT"
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


def object_fields(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def fields(value: object) -> TypeGuard[dict[str, object]]:
    return object_fields(value) and all(isinstance(key, str) for key in value)


def native_architecture(machine: str) -> Architecture | Rejected:
    match machine:
        case "arm64" | "aarch64":
            return Architecture.ARM64
        case "x86_64" | "amd64":
            return Architecture.AMD64
        case _:
            return Rejected(Failure.UNSUPPORTED_ARCHITECTURE, machine)


def validate_vm(config: Path, arch: Architecture) -> Architecture | Rejected:
    import yaml

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
    cwd: Path = ROOT,
) -> subprocess.CompletedProcess[bytes] | Rejected:
    try:
        result = subprocess.run(
            args,
            env=environment,
            cwd=cwd,
            stdout=subprocess.PIPE if capture else sys.stderr,
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
            Failure.MISSING_TOOL, "Install Docker CLI and Colima: brew install docker colima"
        )
    environment = effect_environment(os.environ)
    environment["PATH"] = (
        str(Path(docker).parent) + os.pathsep + environment.get("PATH", os.defpath)
    )
    result = invoke(
        [
            colima,
            "start",
            "--runtime",
            "docker",
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


def build_image(runtime: Runtime, checkout: Path = ROOT) -> Rejected | None:
    if (
        not (checkout / "gradlew").is_file()
        or not (checkout / "packaging/container/Dockerfile").is_file()
    ):
        return Rejected(
            Failure.INVALID_CHECKOUT, "Use kast-container build --checkout /path/to/kast"
        )
    options = ["-PcontrolVersion=" + VERSION, "-PhostedPluginVersion=" + VERSION, "--console=plain"]
    built = invoke(
        [
            str(checkout / "gradlew"),
            ":cli:assembleNativeImageInputs",
            ":runtime:hosted:headlessBootstrapPlugin",
            "generateHostReleaseRecord",
            *options,
        ],
        runtime.environment,
        cwd=checkout,
    )
    if isinstance(built, Rejected):
        return built
    with tempfile.TemporaryDirectory(prefix="kast-container-build-") as directory:
        context = Path(directory)
        for source, destination in (
            (
                checkout / "cli/build/distributions/native-inputs.tar.gz",
                context / "native-inputs.tar.gz",
            ),
            (checkout / "packaging/container_compile.py", context / "container_compile.py"),
            (checkout / "packaging/container/compiler.Dockerfile", context / "Dockerfile"),
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
            cwd=checkout,
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
                str(checkout / "gradlew"),
                "assembleKastControlDist",
                "verifyKastControlDistLayout",
                "-PkastNativeExecutable=" + str(native),
                "-PkastControlPlatform=" + runtime.architecture.platform,
                *options,
            ],
            runtime.environment,
            cwd=checkout,
        )
        if isinstance(built, Rejected):
            return built
        assets = context / "assets"
        assets.mkdir()
        for source in (
            checkout
            / f"build/distributions/kast-control-v{VERSION}-{runtime.architecture.platform}.tar.gz",
            checkout
            / f"runtime/hosted/build/distributions/kast-ide-hosted-v{VERSION}-idea-262.zip",
            checkout / f"build/generated/host-release/kast-host-release-v{VERSION}.json",
            checkout / "packaging/host-installation.py",
        ):
            destination = assets / source.name
            _ = shutil.copyfile(source, destination)
            with destination.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            _ = destination.with_name(destination.name + ".sha256").write_text(
                f"{digest}  {destination.name}\n"
            )
        _ = shutil.copyfile(
            checkout
            / f"runtime/hosted/build/distributions/kast-managed-headless-v{VERSION}-idea-262.zip",
            context / "bootstrap.zip",
        )
        _ = shutil.copyfile(checkout / "install.sh", context / "install.sh")
        modules = context / "packaging"
        modules.mkdir()
        for name in (
            "container_install.py",
            "oci_task.py",
            "managed_headless.py",
            "composed_bundle.py",
            "composed_host.py",
            "composition_descriptors.py",
            "composition_graph.py",
            "composition_index.py",
            "composition_platform.py",
            "composition_recipe.py",
        ):
            _ = shutil.copyfile(checkout / "packaging" / name, modules / name)
        _ = shutil.copyfile(checkout / "packaging/container/Dockerfile", context / "Dockerfile")
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
            cwd=checkout,
        )
        return result if isinstance(result, Rejected) else None


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="kast-container",
        description=__doc__,
        epilog="Use kast container to open a workspace or run a task.",
    )
    commands = parser.add_subparsers(dest="action", required=True)
    build = commands.add_parser("build", help="Build a native image from a Kast source checkout")
    _ = build.add_argument("--checkout", type=Path, default=ROOT, help="Kast source checkout")
    args = parser.parse_args()
    try:
        selected = runtime()
        if isinstance(selected, Rejected):
            return report(selected)
        result = build_image(selected, args.checkout.resolve())
        return report(result) if isinstance(result, Rejected) else 0
    except KeyboardInterrupt:
        return 130
    except OSError as error:
        return report(Rejected(Failure.IO_FAILED, type(error).__name__))


def report(rejected: Rejected) -> int:
    print(f"kast-container: outcome={rejected.reason.value}; {rejected.detail}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
