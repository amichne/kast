"""Compose a pinned analysis Host into an empty tree from selected source files."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import zipfile
from dataclasses import asdict, dataclass, replace
from enum import StrEnum
from pathlib import Path
from typing import TypeGuard
from xml.etree.ElementTree import tostring

from composition_descriptors import DescriptorRejected, DescriptorSelection, parse_xml
from composition_graph import GraphRejected, ModuleId
from composition_index import IndexRejected, PluginIndex, encode_plugin_index
from composition_platform import HostPlatform, UnsupportedHost, host_platform
from composition_recipe import BUILD, ROOTS, plan, support_resources
from managed_headless import (
    AssemblyEntryKind,
    AssemblyFile,
    Input,
    Installed,
    Rejected,
    admit,
    file_record,
    install_prepared_runtime,
    object_fields,
)


class CompositionFailure(StrEnum):
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    WRONG_PIN = "WRONG_PIN"
    INVALID_SOURCE_MANIFEST = "INVALID_SOURCE_MANIFEST"
    SOURCE_DIGEST_MISMATCH = "SOURCE_DIGEST_MISMATCH"
    UNSAFE_SOURCE = "UNSAFE_SOURCE"
    MISSING_RESOURCE = "MISSING_RESOURCE"
    INVALID_PRODUCT = "INVALID_PRODUCT"
    EFFECT_FAILED = "EFFECT_FAILED"


@dataclass(frozen=True, slots=True)
class CompositionRejected:
    reason: CompositionFailure
    subject: str


@dataclass(frozen=True, slots=True)
class Launch:
    os: str
    arch: str
    launcherPath: str
    javaExecutablePath: str
    vmOptionsFilePath: str
    bootClassPathJarNames: tuple[str, ...]
    additionalJvmArguments: tuple[str, ...]
    mainClass: str


@dataclass(frozen=True, slots=True)
class Product:
    name: str
    version: str
    buildNumber: str
    productCode: str
    productVendor: str
    envVarBaseName: str
    dataDirectoryName: str
    launch: tuple[Launch, ...]
    bundledPlugins: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class InputFile:
    record: AssemblyFile
    # The admitted canonical root carries the path scope for every copy.
    source: Path


@dataclass(frozen=True, slots=True)
class _VerifiedFile:
    record: AssemblyFile
    source: Path


@dataclass(frozen=True, slots=True)
class ModuleDecision:
    identity: ModuleId
    predecessor: ModuleId | None
    descriptorSha256: str


@dataclass(frozen=True, slots=True)
class CompositionReceipt:
    schemaVersion: int
    distributionSha256: str
    sourceManifestSha256: str
    bootstrapArchiveSha256: str
    assemblerFiles: tuple[AssemblyFile, ...]
    roots: tuple[ModuleId, ...]
    modules: tuple[ModuleDecision, ...]
    plugins: tuple[str, ...]
    embeddedArchiveModules: tuple[ModuleId, ...]
    omittedRegistrations: tuple[ModuleId, ...]
    omittedKotlinFragments: tuple[str, ...]
    sourceInputs: tuple[AssemblyFile, ...]
    vendorFiles: tuple[AssemblyFile, ...]
    generatedFiles: tuple[AssemblyFile, ...]
    acceptance: str = "NOT_RUN"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _safe_path(value: str) -> bool:
    return (
        bool(value)
        and not any(c in value for c in ("\\", "\x00", ":"))
        and all(part not in {"", ".", ".."} for part in value.split("/"))
    )


def source_inventory(
    home: Path,
    manifest: Path,
    expected: str,
    target: HostPlatform = HostPlatform.MACOS_ARM64,
) -> tuple[InputFile, ...] | CompositionRejected:
    if home.resolve() != home or not home.is_dir() or manifest.is_symlink():
        return CompositionRejected(CompositionFailure.UNSAFE_SOURCE, str(home))
    if not re.fullmatch("[0-9a-f]{64}", expected) or digest(manifest) != expected:
        return CompositionRejected(CompositionFailure.SOURCE_DIGEST_MISMATCH, str(manifest))
    try:
        raw: object = json.loads(manifest.read_bytes())
    except (json.JSONDecodeError, UnicodeDecodeError):
        return CompositionRejected(CompositionFailure.INVALID_SOURCE_MANIFEST, str(manifest))
    if (
        not object_fields(raw)
        or raw.get("schemaVersion") != 1
        or raw.get("distributionSha256") != target.layout.archive_sha256
    ):
        return CompositionRejected(CompositionFailure.INVALID_SOURCE_MANIFEST, str(manifest))
    records = raw.get("retainedFiles")
    if not object_items(records) or not records or len(records) > 100_000:
        return CompositionRejected(CompositionFailure.INVALID_SOURCE_MANIFEST, "retainedFiles")
    result: list[InputFile] = []
    seen: set[str] = set()
    for record in records:
        if not object_fields(record):
            return CompositionRejected(CompositionFailure.INVALID_SOURCE_MANIFEST, "file")
        path, kind, size, sha = (record.get(key) for key in ("path", "kind", "bytes", "sha256"))
        if (
            not isinstance(path, str)
            or not _safe_path(path)
            or path in seen
            or not isinstance(kind, str)
            or kind not in {"FILE", "SYMLINK"}
            or not isinstance(size, int)
            or isinstance(size, bool)
            or size < 0
            or not isinstance(sha, str)
            or not re.fullmatch("[0-9a-f]{64}", sha)
        ):
            return CompositionRejected(CompositionFailure.INVALID_SOURCE_MANIFEST, "file fields")
        seen.add(path)
        result.append(InputFile(AssemblyFile(path, AssemblyEntryKind(kind), size, sha), home))
    return tuple(result)


def examine_source(item: InputFile) -> _VerifiedFile | CompositionRejected:
    path = item.source / item.record.path
    if (
        path.parent.resolve() != path.parent
        or not path.resolve().is_relative_to(item.source)
        or not (path.is_file() or path.is_symlink())
    ):
        return CompositionRejected(CompositionFailure.UNSAFE_SOURCE, item.record.path)
    if file_record(path, item.source) != item.record:
        return CompositionRejected(CompositionFailure.SOURCE_DIGEST_MISMATCH, item.record.path)
    return _VerifiedFile(item.record, item.source)


def object_items(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _strings(value: object) -> tuple[str, ...] | None:
    if not object_items(value) or not all(isinstance(item, str) for item in value):
        return None
    return tuple(item for item in value if isinstance(item, str))


def product(
    data: bytes, files: frozenset[str], plugins: tuple[str, ...]
) -> Product | CompositionRejected:
    try:
        raw: object = json.loads(data)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return CompositionRejected(CompositionFailure.INVALID_PRODUCT, "product-info")
    if not object_fields(raw):
        return CompositionRejected(CompositionFailure.INVALID_PRODUCT, "product-info")
    keys = (
        "name",
        "version",
        "buildNumber",
        "productCode",
        "productVendor",
        "envVarBaseName",
        "dataDirectoryName",
    )
    values = tuple(raw.get(key) for key in keys)
    if not all(isinstance(value, str) for value in values) or raw.get("buildNumber") != BUILD:
        return CompositionRejected(CompositionFailure.INVALID_PRODUCT, "product fields")
    launches = raw.get("launch")
    if not object_items(launches) or len(launches) != 1 or not object_fields(launches[0]):
        return CompositionRejected(CompositionFailure.INVALID_PRODUCT, "launch")
    launch = launches[0]
    names = _strings(launch.get("bootClassPathJarNames"))
    arguments = _strings(launch.get("additionalJvmArguments"))
    fields = tuple(
        launch.get(key)
        for key in (
            "os",
            "arch",
            "launcherPath",
            "javaExecutablePath",
            "vmOptionsFilePath",
            "mainClass",
        )
    )
    if (
        names is None
        or arguments is None
        or not all(isinstance(value, str) for value in fields)
        or fields[:2]
        not in {(target.layout.launch_os, target.layout.launch_arch) for target in HostPlatform}
        or any(not _safe_path(name) for name in names)
    ):
        return CompositionRejected(CompositionFailure.INVALID_PRODUCT, "launch fields")
    # Preserve the vendor launch order only for archives selected by the source graphs.
    boot = tuple(name for name in names if "lib/" + name in files)
    boot += tuple(
        name for name in ("ext/platform-main.jar", "kast-product.jar") if name not in boot
    )
    args = arguments + (
        "-Dintellij.platform.load.app.info.from.resources=true",
        "-Didea.platform.prefix=idea",
    )
    # The checked fields keep their concrete string type through serialization.
    strings = tuple(value for value in values if isinstance(value, str))
    entry = tuple(value for value in fields if isinstance(value, str))
    return Product(
        strings[0],
        strings[1],
        strings[2],
        strings[3],
        strings[4],
        strings[5],
        strings[6],
        (Launch(entry[0], entry[1], entry[2], entry[3], entry[4], boot, args, entry[5]),),
        plugins,
    )


def compose(
    inputs: Input, source: Path, manifest: Path, manifest_sha256: str, installer: Path
) -> (
    Installed | Rejected | CompositionRejected | DescriptorRejected | GraphRejected | IndexRejected
):
    target = host_platform(platform.system(), platform.machine())
    if isinstance(target, UnsupportedHost):
        return CompositionRejected(CompositionFailure.UNSUPPORTED_PLATFORM, platform.platform())
    layout = target.layout
    if (
        inputs.build != BUILD
        or inputs.archive_sha256 != layout.archive_sha256
        or inputs.excluded_plugins
    ):
        return CompositionRejected(CompositionFailure.WRONG_PIN, inputs.build)
    admitted = admit(inputs)
    if isinstance(admitted, Rejected):
        return admitted
    inventory = source_inventory(source, manifest, manifest_sha256, target)
    if isinstance(inventory, CompositionRejected):
        return inventory
    by_path = {item.record.path: item for item in inventory}
    shaping = (
        "modules/module-descriptors.jar",
        "plugins/plugin-classpath.txt",
        "lib/intellij.platform.ide.impl.jar",
        "lib/intellij.idea.ultimate.customization.jar",
        layout.product,
    )
    for name in shaping:
        item = by_path.get(name)
        if item is None:
            return CompositionRejected(CompositionFailure.MISSING_RESOURCE, name)
        rejected = examine_source(item)
        if isinstance(rejected, CompositionRejected):
            return rejected
    selection = plan(source)
    if not isinstance(selection, DescriptorSelection):
        return selection
    support = support_resources(source, selection)
    if isinstance(support, (GraphRejected, DescriptorRejected)):
        return support
    paths = {item.path for item in (*selection.closure.resources, *support)}
    paths.update(
        {
            *layout.support,
            "lib/ext/platform-main.jar",
            "plugins/java/lib/resources/jdkAnnotations.jar",
        }
    )
    prefixes = (
        "jbr/",
        "MacOS/",
        "bin/",
        "modules/",
        "plugins/Kotlin/kotlinc/",
        "lib/jna/",
        "lib/pty4j/",
        "lib/async-profiler/",
        "lib/native/",
    )
    paths.update(name for name in by_path if name.startswith(prefixes))
    chosen: list[_VerifiedFile] = []
    for name in sorted(paths):
        item = by_path.get(name)
        if item is None:
            return CompositionRejected(CompositionFailure.MISSING_RESOURCE, name)
        rejected = examine_source(item)
        if isinstance(rejected, CompositionRejected):
            return rejected
        chosen.append(rejected)
    metadata = product((source / shaping[-1]).read_bytes(), frozenset(paths), selection.plugins)
    if isinstance(metadata, CompositionRejected):
        return metadata
    if (metadata.launch[0].os, metadata.launch[0].arch) != (layout.launch_os, layout.launch_arch):
        return CompositionRejected(CompositionFailure.INVALID_PRODUCT, "source platform")
    native_index = PluginIndex(
        selection.core,
        tuple(
            replace(
                plugin,
                files=tuple(
                    name
                    for name in plugin.files
                    if "plugins/" + plugin.directory + "/" + name in paths
                ),
            )
            for plugin in selection.descriptors
        ),
    )
    with zipfile.ZipFile(source / "lib/intellij.idea.ultimate.customization.jar") as archive:
        app = parse_xml(archive.read("idea/ApplicationInfo.xml"), "ApplicationInfo")
    if isinstance(app, DescriptorRejected):
        return app
    for node in list(app):
        if node.tag.endswith("essential-plugin") and node.text != "com.intellij.java":
            app.remove(node)
    root = inputs.root
    root.mkdir(mode=0o700)
    _ = (root / ".kast-headless-owned").write_text("kast-managed-headless-v1\n")
    home = root / layout.home
    home.mkdir(parents=True)
    for item in chosen:
        original, target = source / item.record.path, home / item.record.path
        target.parent.mkdir(parents=True, exist_ok=True)
        if item.record.kind == AssemblyEntryKind.SYMLINK:
            target.symlink_to(os.readlink(original))
        else:
            _ = shutil.copy2(original, target)
        if file_record(target, home) != item.record:
            return CompositionRejected(CompositionFailure.SOURCE_DIGEST_MISMATCH, item.record.path)
    (home / layout.product).parent.mkdir(exist_ok=True, parents=True)
    _ = (home / layout.product).write_text(json.dumps(asdict(metadata), indent=2) + "\n")
    (home / "plugins").mkdir(exist_ok=True)
    _ = (home / "plugins/plugin-classpath.txt").write_bytes(encode_plugin_index(native_index))
    with zipfile.ZipFile(
        home / "lib/kast-product.jar", "w", compression=zipfile.ZIP_DEFLATED
    ) as archive:
        for name, data in (
            ("META-INF/plugin.xml", selection.core),
            ("idea/ApplicationInfo.xml", tostring(app, encoding="utf-8")),
        ):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    generated = tuple(
        file_record(home / name, home)
        for name in (
            layout.product,
            "plugins/plugin-classpath.txt",
            "lib/kast-product.jar",
        )
    )
    code = Path(__file__).resolve().parent
    receipt = CompositionReceipt(
        1,
        inputs.archive_sha256,
        manifest_sha256,
        inputs.bootstrap_sha256,
        tuple(
            file_record(code / name, code)
            for name in (
                "composed_host.py",
                "composition_graph.py",
                "composition_index.py",
                "composition_descriptors.py",
                "composition_recipe.py",
                "composition_platform.py",
                "managed_headless.py",
            )
        ),
        ROOTS,
        tuple(
            ModuleDecision(
                member.module.identity, member.predecessor, member.module.descriptor_sha256
            )
            for member in selection.closure.members
        ),
        selection.plugins,
        selection.support,
        selection.omitted,
        ("ultimate-plugin.k2.xml", "command-completion-k2.xml"),
        tuple(by_path[name].record for name in shaping),
        tuple(item.record for item in chosen),
        generated,
    )
    _ = (root / "composition-manifest.json").write_text(
        json.dumps(asdict(receipt), indent=2) + "\n"
    )
    return install_prepared_runtime(admitted, home, installer)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "archive", "bootstrap", "assets", "source-home", "source-manifest"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    for name in ("archive-sha256", "bootstrap-sha256", "source-manifest-sha256", "version"):
        _ = parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        result = compose(
            Input(
                args.root,
                args.archive,
                args.archive_sha256,
                args.bootstrap,
                args.bootstrap_sha256,
                args.assets,
                args.version,
                BUILD,
            ),
            args.source_home,
            args.source_manifest,
            args.source_manifest_sha256,
            Path(__file__).resolve().parent.parent / "install.sh",
        )
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        result = CompositionRejected(CompositionFailure.EFFECT_FAILED, type(error).__name__)
    print(json.dumps(asdict(result), indent=2))
    return 0 if isinstance(result, Installed) else 1


if __name__ == "__main__":
    raise SystemExit(main())
