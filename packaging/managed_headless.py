"""Assemble a private production Host and install Control through its existing installer."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import TypeGuard


class Failure(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    EXISTING_TARGET = "EXISTING_TARGET"
    DIGEST_MISMATCH = "DIGEST_MISMATCH"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    INVALID_DISTRIBUTION = "INVALID_DISTRIBUTION"
    INSTALLER_FAILED = "INSTALLER_FAILED"
    EFFECT_FAILED = "EFFECT_FAILED"
    UNKNOWN_COMPONENT = "UNKNOWN_COMPONENT"
    REQUIRED_COMPONENT = "REQUIRED_COMPONENT"
    COMPONENT_DEPENDENCY = "COMPONENT_DEPENDENCY"
    INVALID_COMPONENT = "INVALID_COMPONENT"
    SHARED_STATE = "SHARED_STATE"


@dataclass(frozen=True, slots=True)
class Rejected:
    reason: Failure


class AssemblyEntryKind(StrEnum):
    FILE = "FILE"
    SYMLINK = "SYMLINK"


class ComponentDisposition(StrEnum):
    RETAIN = "RETAIN"
    EXCLUDE = "EXCLUDE"


class ComponentReason(StrEnum):
    PINNED_DISTRIBUTION = "PINNED_DISTRIBUTION"
    EXPLICIT_NON_ROOT_CANDIDATE = "EXPLICIT_NON_ROOT_CANDIDATE"


class AssemblyAcceptance(StrEnum):
    NOT_RUN = "NOT_RUN"


@dataclass(frozen=True, slots=True)
class Component:
    directory: str
    identifiers: tuple[str, ...]
    modules: tuple[str, ...]
    required: tuple[str, ...]
    descriptor_sha256: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class ComponentSelection:
    kept: tuple[Component, ...]
    removed: tuple[Component, ...]


def select_components(
    components: tuple[Component, ...],
    excluded: tuple[str, ...],
    roots: tuple[str, ...],
) -> ComponentSelection | Rejected:
    requested = set(excluded)
    known = {identifier for component in components for identifier in component.identifiers}
    if len(known) != sum(len(component.identifiers) for component in components):
        return Rejected(Failure.INVALID_COMPONENT)
    if not requested <= known:
        return Rejected(Failure.UNKNOWN_COMPONENT)
    removed = tuple(c for c in components if requested.intersection(c.identifiers))
    providers = {
        name for component in removed for name in (*component.identifiers, *component.modules)
    }
    if providers.intersection(roots):
        return Rejected(Failure.REQUIRED_COMPONENT)
    kept = tuple(c for c in components if c not in removed)
    if any(providers.intersection(component.required) for component in kept):
        return Rejected(Failure.COMPONENT_DEPENDENCY)
    return ComponentSelection(kept, removed)


@dataclass(frozen=True, slots=True)
class AssemblyRecord:
    schemaVersion: int
    installation: str
    distributionSha256: str
    bootstrapSha256: str
    platformBuild: str
    kotlinPluginBuild: str


@dataclass(frozen=True, slots=True)
class Input:
    root: Path
    archive: Path
    archive_sha256: str
    bootstrap: Path
    bootstrap_sha256: str
    assets: Path
    version: str
    build: str
    excluded_plugins: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class Installed:
    root: str
    command: str
    assembly: AssemblyRecord
    hostReadiness: str = "NOT_RUN"


@dataclass(frozen=True, slots=True)
class _AdmittedInput:
    inputs: Input


def admit(inputs: Input) -> _AdmittedInput | Rejected:
    root = inputs.root
    if not root.is_absolute() or root != root.resolve() or root.parent.resolve() != root.parent:
        return Rejected(Failure.INVALID_INPUT)
    if root.exists():
        return Rejected(Failure.EXISTING_TARGET)
    socket = root / "state/home/.kast/ide-lifecycle" / ("0" * 32) / "host.sock"
    if len(os.fsencode(socket)) > 100:
        return Rejected(Failure.INVALID_INPUT)
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", inputs.version):
        return Rejected(Failure.INVALID_INPUT)
    if not re.fullmatch(r"262\.[0-9]+(?:\.[0-9]+)*", inputs.build):
        return Rejected(Failure.INVALID_INPUT)
    if not inputs.assets.is_dir():
        return Rejected(Failure.INVALID_INPUT)
    for path, expected in (
        (inputs.archive, inputs.archive_sha256),
        (inputs.bootstrap, inputs.bootstrap_sha256),
    ):
        if not path.is_file() or path.is_symlink() or not re.fullmatch(r"[0-9a-f]{64}", expected):
            return Rejected(Failure.INVALID_INPUT)
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != expected:
                return Rejected(Failure.DIGEST_MISMATCH)
    return _AdmittedInput(inputs)


def unpack_archive(archive: Path, target: Path, system: str) -> Path | Rejected:
    if system == "Linux":
        with tarfile.open(archive) as source:
            source.extractall(target, filter="data")
        children = list(target.iterdir())
        if len(children) != 1 or not children[0].is_dir():
            return Rejected(Failure.INVALID_DISTRIBUTION)
        return children[0]
    if system == "Darwin":
        with tempfile.TemporaryDirectory(prefix="kast-headless-mount-") as temporary:
            mount = Path(temporary)
            attached = subprocess.run(
                [
                    "hdiutil",
                    "attach",
                    "-readonly",
                    "-nobrowse",
                    "-mountpoint",
                    str(mount),
                    str(archive),
                ],
                check=False,
            )
            if attached.returncode != 0:
                return Rejected(Failure.INVALID_DISTRIBUTION)
            try:
                apps = list(mount.glob("*.app"))
                if len(apps) != 1:
                    return Rejected(Failure.INVALID_DISTRIBUTION)
                app = target / apps[0].name
                _ = subprocess.run(["ditto", str(apps[0]), str(app)], check=True)
                return app / "Contents"
            finally:
                _ = subprocess.run(["hdiutil", "detach", str(mount)], check=True)
    return Rejected(Failure.UNSUPPORTED_PLATFORM)


def add_bootstrap(home: Path, bootstrap: Path) -> str | Rejected:
    destination = home / "third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
    if destination.exists():
        return Rejected(Failure.EXISTING_TARGET)
    with zipfile.ZipFile(bootstrap) as source:
        files = [item for item in source.infolist() if not item.is_dir()]
        if (
            len(files) != 1
            or files[0].filename != "kast-managed-headless/lib/kast-managed-headless.jar"
        ):
            return Rejected(Failure.INVALID_INPUT)
        data = source.read(files[0])
    destination.parent.mkdir(parents=True)
    _ = destination.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def seal_runtime(home: Path) -> None:
    """Keep vendor bytes and execute bits while removing write permissions from this owned tree."""
    for path in (*home.rglob("*"), home):
        if not path.is_symlink():
            path.chmod(path.stat().st_mode & ~0o222)


def wrapper(home: Path, state: Path) -> str:
    executable = state / "home/.local/bin/kast"
    return (
        "#!/bin/sh\nset -eu\n"
        f"[ -f {shlex.quote(str(home / 'kast-headless.json'))} ] || exit 2\n"
        f"HOME={shlex.quote(str(state / 'home'))} "
        f"KAST_MANAGED_HEADLESS_STATE={shlex.quote(str(state))} "
        f'exec {shlex.quote(str(executable))} "$@"\n'
    )


def object_fields(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def descriptor_facts(
    data: bytes,
    resources: dict[str, bytes],
    visited: frozenset[str] = frozenset(),
) -> tuple[tuple[str, ...], tuple[str, ...], tuple[str, ...]]:
    if len(data) > 1_048_576 or len(visited) > 64:
        raise ValueError("Component descriptor limit")
    root = ET.fromstring(data)
    identifiers = tuple(filter(None, (root.findtext("id") or root.findtext("name"),)))
    modules = tuple(
        node.get("value") or node.get("name") or ""
        for node in (*root.findall("module"), *root.findall("./content/module"))
    )
    required = tuple(
        node.text.strip()
        for node in root.findall("depends")
        if node.text and node.get("optional") != "true"
    ) + tuple(
        node.get("id") or node.get("name") or ""
        for node in (*root.findall("./dependencies/plugin"), *root.findall("./dependencies/module"))
    )
    for include in root.findall("{http://www.w3.org/2001/XInclude}include"):
        name = include.get("href", "")
        candidates = tuple(key for key in resources if key == name or key == "META-INF/" + name)
        if len(candidates) != 1 or name in visited:
            raise ValueError("Unresolved component descriptor")
        nested = descriptor_facts(resources[candidates[0]], resources, visited | {name})
        identifiers += nested[0]
        modules += nested[1]
        required += nested[2]
    if not all(value.strip() == value and value for value in (*identifiers, *modules, *required)):
        raise ValueError("Invalid component identity")
    return identifiers, modules, required


def component_inventory(home: Path) -> tuple[Component, ...]:
    components: list[Component] = []
    for parent in (home / "plugins", home / "third-party-plugins"):
        if not parent.exists():
            continue
        for directory in sorted(parent.iterdir()):
            if directory.is_symlink():
                raise ValueError("Invalid component directory")
            if directory.is_file():
                continue
            if not directory.is_dir():
                raise ValueError("Invalid component directory")
            descriptors: list[bytes] = []
            jars = tuple(sorted((directory / "lib").glob("*.jar")))
            for jar in jars:
                with zipfile.ZipFile(jar) as archive:
                    if "META-INF/plugin.xml" in archive.namelist():
                        descriptors.append(archive.read("META-INF/plugin.xml"))
            resources = included_descriptor_resources(jars, tuple(descriptors))
            identifiers: set[str] = set()
            modules: set[str] = set()
            required: set[str] = set()
            for descriptor in descriptors:
                facts = descriptor_facts(descriptor, resources)
                identifiers.update(facts[0])
                modules.update(facts[1])
                required.update(facts[2])
            components.append(
                Component(
                    directory.relative_to(home).as_posix(),
                    tuple(sorted(identifiers)),
                    tuple(sorted(modules)),
                    tuple(sorted(required)),
                    tuple(
                        hashlib.sha256(data).hexdigest()
                        for data in (*descriptors, *resources.values())
                    ),
                )
            )
    return tuple(components)


def included_descriptor_resources(
    jars: tuple[Path, ...], descriptors: tuple[bytes, ...]
) -> dict[str, bytes]:
    resources: dict[str, bytes] = {}
    pending = list(descriptors)
    while pending:
        current = pending.pop()
        if len(current) > 1_048_576 or len(resources) > 64:
            raise ValueError("Component descriptor limit")
        for include in ET.fromstring(current).findall("{http://www.w3.org/2001/XInclude}include"):
            name = include.get("href", "")
            if name in resources or "META-INF/" + name in resources:
                continue
            found: list[tuple[str, bytes]] = []
            for path in jars:
                with zipfile.ZipFile(path) as jar:
                    for entry in jar.infolist():
                        if entry.filename not in (name, "META-INF/" + name):
                            continue
                        if entry.file_size > 1_048_576:
                            raise ValueError("Component descriptor limit")
                        found.append((entry.filename, jar.read(entry)))
            if len(found) != 1:
                raise ValueError("Unresolved component descriptor")
            key, data = found[0]
            resources[key] = data
            pending.append(data)
    return resources


def host_dependency_roots(assets: Path, version: str) -> tuple[str, ...]:
    artifact = assets / f"kast-ide-hosted-v{version}-idea-262.zip"
    roots: list[tuple[str, ...]] = []
    with zipfile.ZipFile(artifact) as distribution:
        for entry in distribution.infolist():
            if not entry.filename.endswith(".jar"):
                continue
            with zipfile.ZipFile(io.BytesIO(distribution.read(entry))) as jar:
                if "META-INF/plugin.xml" not in jar.namelist():
                    continue
                data = jar.read("META-INF/plugin.xml")
                if ET.fromstring(data).findtext("id") == "io.github.amichne.kast.ide-hosted":
                    roots.append(descriptor_facts(data, {})[2])
    if len(roots) != 1 or not roots[0]:
        raise ValueError("Missing Host dependency roots")
    return roots[0]


@dataclass(frozen=True, slots=True)
class AssemblyFile:
    path: str
    kind: AssemblyEntryKind
    bytes: int
    sha256: str


@dataclass(frozen=True, slots=True)
class ComponentDecision:
    component: Component
    decision: ComponentDisposition
    reason: ComponentReason


@dataclass(frozen=True, slots=True)
class AssemblyManifest:
    schemaVersion: int
    distributionSha256: str
    bootstrapArchiveSha256: str
    assemblerSha256: str
    installerSha256: str
    hostRoots: tuple[str, ...]
    components: tuple[ComponentDecision, ...]
    retainedFiles: tuple[AssemblyFile, ...]
    removedFiles: tuple[AssemblyFile, ...]
    assets: tuple[AssemblyFile, ...]
    acceptance: AssemblyAcceptance = AssemblyAcceptance.NOT_RUN

    @property
    def disabled_plugins(self) -> tuple[str, ...]:
        return tuple(
            sorted(
                identifier
                for decision in self.components
                if decision.decision == ComponentDisposition.EXCLUDE
                for identifier in decision.component.identifiers
            )
        )


def file_record(path: Path, base: Path) -> AssemblyFile:
    if path.is_symlink():
        data = os.fsencode(os.readlink(path))
        return AssemblyFile(
            path.relative_to(base).as_posix(),
            AssemblyEntryKind.SYMLINK,
            len(data),
            hashlib.sha256(data).hexdigest(),
        )
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return AssemblyFile(
        path.relative_to(base).as_posix(), AssemblyEntryKind.FILE, path.stat().st_size, digest
    )


def select_assembly_components(
    home: Path, inputs: Input, installer: Path
) -> AssemblyManifest | Rejected:
    try:
        components = component_inventory(home)
        roots = host_dependency_roots(inputs.assets, inputs.version)
        selected = select_components(components, inputs.excluded_plugins, roots)
        if isinstance(selected, Rejected):
            return selected
        removed = tuple(home / component.directory for component in selected.removed)
        records = tuple(
            file_record(path, home)
            for path in sorted(home.rglob("*"))
            if path.is_file() or path.is_symlink()
        )
        removed_records = tuple(
            record
            for record in records
            if any((home / record.path).is_relative_to(path) for path in removed)
        )
        retained_records = tuple(record for record in records if record not in removed_records)
        manifest = AssemblyManifest(
            1,
            inputs.archive_sha256,
            inputs.bootstrap_sha256,
            hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            hashlib.sha256(installer.read_bytes()).hexdigest(),
            roots,
            tuple(
                ComponentDecision(
                    component,
                    ComponentDisposition.RETAIN,
                    ComponentReason.PINNED_DISTRIBUTION,
                )
                for component in selected.kept
            )
            + tuple(
                ComponentDecision(
                    component,
                    ComponentDisposition.EXCLUDE,
                    ComponentReason.EXPLICIT_NON_ROOT_CANDIDATE,
                )
                for component in selected.removed
            ),
            retained_records,
            removed_records,
            tuple(
                file_record(path, inputs.assets)
                for path in sorted(inputs.assets.iterdir())
                if path.is_file()
            ),
        )
        for path in removed:
            if (
                path.is_symlink()
                or path.resolve() != path
                or path.parent not in (home / "plugins", home / "third-party-plugins")
            ):
                return Rejected(Failure.INVALID_COMPONENT)
        for path in removed:
            shutil.rmtree(path)
        return manifest
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile):
        return Rejected(Failure.INVALID_COMPONENT)


def configure_removed_plugins(state: Path, manifest: AssemblyManifest) -> Rejected | None:
    if not manifest.disabled_plugins:
        return None
    selected: object = json.loads(
        (state / "home/.local/share/kast/installation/config/selected-ide.json").read_text()
    )
    if not object_fields(selected) or selected.get("type") != "resolved":
        return Rejected(Failure.INVALID_COMPONENT)
    target = selected.get("hostPluginTarget")
    if not object_fields(target) or target.get("type") != "RECORDED":
        return Rejected(Failure.INVALID_COMPONENT)
    raw = target.get("root")
    if not isinstance(raw, str):
        return Rejected(Failure.INVALID_COMPONENT)
    plugins = Path(raw)
    if (
        plugins.resolve() != plugins
        or plugins.name != "plugins"
        or not plugins.is_relative_to(state / "home")
    ):
        return Rejected(Failure.SHARED_STATE)
    path = plugins.parent / "disabled_plugins.txt"
    if path.is_symlink():
        return Rejected(Failure.INVALID_COMPONENT)
    prior: set[str] = set(path.read_text().splitlines()) if path.exists() else set()
    _ = path.write_text("\n".join(sorted(prior | set(manifest.disabled_plugins))) + "\n")
    return None


def install(inputs: Input, installer: Path) -> Installed | Rejected:
    admitted = admit(inputs)
    if isinstance(admitted, Rejected):
        return admitted
    return assemble(admitted, installer)


def assemble(admitted: _AdmittedInput, installer: Path) -> Installed | Rejected:
    inputs = admitted.inputs
    system = platform.system()
    if system not in {"Linux", "Darwin"}:
        return Rejected(Failure.UNSUPPORTED_PLATFORM)
    root = inputs.root
    root.mkdir(mode=0o700)
    _ = (root / ".kast-headless-owned").write_text("kast-managed-headless-v1\n")
    runtime = root / "runtime"
    runtime.mkdir()
    home = unpack_archive(inputs.archive, runtime, system)
    if isinstance(home, Rejected):
        return home
    metadata = home / ("Resources/product-info.json" if system == "Darwin" else "product-info.json")
    # The archive digest fixes every vendor byte. These literal product fields reject a wrong pin.
    product: object = json.loads(metadata.read_text())
    if not object_fields(product) or product.get("buildNumber") != inputs.build:
        return Rejected(Failure.INVALID_DISTRIBUTION)
    manifest = select_assembly_components(home, inputs, installer)
    if isinstance(manifest, Rejected):
        return manifest
    _ = (root / "assembly-manifest.json").write_text(json.dumps(asdict(manifest), indent=2) + "\n")
    return install_prepared_runtime(admitted, home, installer, manifest)


def install_prepared_runtime(
    admitted: _AdmittedInput,
    home: Path,
    installer: Path,
    manifest: AssemblyManifest | None = None,
) -> Installed | Rejected:
    """Both assembly strategies use one installer, private state layout, and launcher."""
    inputs = admitted.inputs
    root = inputs.root
    if not home.is_relative_to(root / "runtime") or home.resolve() != home:
        return Rejected(Failure.INVALID_INPUT)
    system = platform.system()
    bootstrap = add_bootstrap(home, inputs.bootstrap)
    if isinstance(bootstrap, Rejected):
        return bootstrap
    record = AssemblyRecord(
        1, str(home), inputs.archive_sha256, bootstrap, inputs.build, inputs.build + "-IJ"
    )
    _ = (home / "kast-headless.json").write_text(json.dumps(asdict(record), indent=2) + "\n")
    seal_runtime(home)
    state = root / "state"
    for name in ("home", "system", "log", "tmp"):
        (state / name).mkdir(parents=True, mode=0o700)
    environment = {
        "PATH": os.defpath,
        "HOME": str(state / "home"),
        "TMPDIR": str(state / "tmp"),
        "KAST_VERSION": inputs.version,
        "KAST_HOST_VERSION": inputs.version,
        "KAST_INSTALL_ASSETS_DIRECTORY": str(inputs.assets),
        "KAST_JAVA_HOME": str(home / ("jbr/Contents/Home" if system == "Darwin" else "jbr")),
    }
    with (root / "installer.log").open("w") as log:
        result = subprocess.run(
            ["bash", str(installer), "--skip-codex-plugin", "--idea-home", str(home)],
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
        )
    if result.returncode != 0:
        return Rejected(Failure.INSTALLER_FAILED)
    if manifest is not None:
        configured = configure_removed_plugins(state, manifest)
        if configured is not None:
            return configured
    (root / "bin").mkdir()
    command = root / "bin/kast"
    _ = command.write_text(wrapper(home, state))
    command.chmod(0o700)
    return Installed(str(root), str(command), record)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "archive", "bootstrap", "assets"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    for name in ("archive-sha256", "bootstrap-sha256", "version", "build"):
        _ = parser.add_argument("--" + name, required=True)
    _ = parser.add_argument("--exclude-plugin", action="append", default=[])
    args = parser.parse_args()
    inputs = Input(
        args.root,
        args.archive,
        args.archive_sha256,
        args.bootstrap,
        args.bootstrap_sha256,
        args.assets,
        args.version,
        args.build,
        tuple(args.exclude_plugin),
    )
    try:
        outcome = install(inputs, Path(__file__).resolve().parent.parent / "install.sh")
    except (
        OSError,
        ValueError,
        tarfile.TarError,
        zipfile.BadZipFile,
        subprocess.CalledProcessError,
    ):
        outcome = Rejected(Failure.EFFECT_FAILED)
    print(json.dumps(asdict(outcome), indent=2))
    return 2 if isinstance(outcome, Rejected) else 0


if __name__ == "__main__":
    raise SystemExit(main())
