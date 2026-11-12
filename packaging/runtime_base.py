"""Immutable vendor bases and disjoint, build-specific runtime components.

The full manifests remain unchanged. Only the producer may project split archives
back into native qualification archives; promotion never calls this module.
"""

from __future__ import annotations

import gzip
import io
import json
import os
import shutil
import stat
import tarfile
import zipfile
from dataclasses import asdict, dataclass, replace
from pathlib import Path

import composed_bundle as bundle
import managed_release as release
from composition_platform import HostPlatform
from runtime_archive import RuntimeFile, RuntimeLink

MARKER = "runtime-base.json"
BASE_MANIFEST = "base-manifest.json"
RECIPE_INPUTS = (
    "composed_host.py",
    "composition_recipe.py",
    "composition_platform.py",
    "composition_graph.py",
    "composition_index.py",
    "composition_descriptors.py",
    "runtime_base.py",
    "runtime_archive.py",
    "composed_bundle.py",
    "managed_headless.py",
    "uv.lock",
)


@dataclass(frozen=True)
class RecipeInput:
    path: str
    sha256: str


@dataclass(frozen=True)
class BaseManifest:
    target: str
    donorSha256: str
    recipeInputs: tuple[RecipeInput, ...]
    supportInputs: tuple[RecipeInput, ...]
    files: tuple[RuntimeFile | RuntimeLink, ...]
    schemaVersion: int = 1
    kind: str = "KAST_RUNTIME_BASE_V1"


@dataclass(frozen=True)
class Composition:
    target: str
    base: release.ProductAsset
    schemaVersion: int = 1
    kind: str = "KAST_RUNTIME_COMPOSITION_V1"


def encoded(value: BaseManifest | Composition) -> bytes:
    return (json.dumps(asdict(value), sort_keys=True, separators=(",", ":")) + "\n").encode()


def prefix(target: HostPlatform) -> str:
    return "host" if target.is_linux else "host/Kast.app/Contents"


def vendor(path: str, target: HostPlatform) -> bool:
    return path.startswith(prefix(target) + "/") and not path.startswith(
        prefix(target) + "/third-party-plugins/"
    )


def reference(raw: bytes) -> Composition:
    value = release.document(raw)
    if (
        not release.mapping(value)
        or set(value) != {"schemaVersion", "kind", "target", "base"}
        or type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or value["kind"] != "KAST_RUNTIME_COMPOSITION_V1"
        or value["target"] not in release.PRODUCT_TARGETS
        or not release.mapping(value["base"])
    ):
        raise ValueError("runtime composition")
    base = value["base"]
    sha = base.get("sha256")
    if not isinstance(sha, str) or not release.DIGEST.fullmatch(sha):
        raise ValueError("base digest")
    asset = release.product_asset(base, f"kast-runtime-base-{value['target']}-{sha}.zip")
    if isinstance(asset, release.Failure):
        raise ValueError("base artifact")
    return Composition(value["target"], asset)


def read_reference(archive: Path) -> Composition | None:
    with zipfile.ZipFile(archive) as source:
        entries = [entry for entry in source.infolist() if entry.filename == MARKER]
        if not entries:
            return None
        if len(entries) != 1 or entries[0].file_size > 65536:
            raise ValueError("composition marker")
        return reference(source.read(entries[0]))


def write_zip(output: Path, sources: tuple[tuple[str, bytes | Path, int], ...]) -> None:
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for name, source, mode in sorted(sources):
            metadata = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            metadata.compress_type = zipfile.ZIP_DEFLATED
            metadata.external_attr = mode << 16
            if isinstance(source, bytes):
                archive.writestr(metadata, source)
            else:
                with (
                    source.open("rb") as incoming,
                    archive.open(metadata, "w", force_zip64=True) as outgoing,
                ):
                    shutil.copyfileobj(incoming, outgoing)


def split(
    runtime: Path, setup: Path, target: HostPlatform, recipe: Path, work: Path
) -> Composition:
    """Producer-only split. Full archive inputs must already be admitted assembly outputs."""
    from managed_product import extract_runtime, verify_runtime

    with zipfile.ZipFile(runtime) as source:
        raw = release.document(source.read("manifest.json"))
    if not release.mapping(raw) or not isinstance(version := raw.get("version"), str):
        raise ValueError("runtime version")
    expanded = work / "runtime"
    manifest = extract_runtime(runtime, expanded, version, target)
    verify_runtime(expanded, manifest)
    files = tuple(item for item in manifest.files if vendor(item.path, target))
    if not files:
        raise ValueError("empty vendor base")
    base_names = {item.path for item in files}
    # A base must be closed under links; it may never depend on build-specific bytes.
    for item in files:
        if isinstance(item, RuntimeLink) and not link_owned(item.path, item.target, base_names):
            raise ValueError("base link crosses component boundary")
    inputs = tuple(RecipeInput(name, release.file_digest(recipe / name)) for name in RECIPE_INPUTS)
    support = tuple(
        RecipeInput(item.path, item.sha256)
        for item in files
        if isinstance(item, RuntimeFile) and "/kast-linux-support/" in item.path
    )
    metadata = BaseManifest(target.value, manifest.distributionSha256, inputs, support, files)
    temporary = work / "base.zip"
    write_zip(
        temporary,
        ((BASE_MANIFEST, encoded(metadata), stat.S_IFREG | 0o444),)
        + tuple(
            (
                item.path,
                os.fsencode(item.target) if isinstance(item, RuntimeLink) else expanded / item.path,
                stat.S_IFLNK | 0o777
                if isinstance(item, RuntimeLink)
                else stat.S_IFREG | (0o555 if item.executable else 0o444),
            )
            for item in files
        ),
    )
    digest = release.file_digest(temporary)
    asset = release.ProductAsset(
        f"kast-runtime-base-{target.value}-{digest}.zip", digest, temporary.stat().st_size
    )
    destination = runtime.parent / asset.name
    if destination.exists():
        raise ValueError("base output exists")
    _ = shutil.copyfile(temporary, destination)
    composition = Composition(target.value, asset)
    component = work / "component.zip"
    with (
        zipfile.ZipFile(runtime) as source,
        zipfile.ZipFile(component, "x", compression=zipfile.ZIP_DEFLATED) as output,
    ):
        for member in source.infolist():
            if member.filename not in base_names:
                with source.open(member) as incoming, output.open(member, "w") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
        output.writestr(MARKER, encoded(composition))
    bundle_names = {
        "setup-kast/runtime/" + name.removeprefix(prefix(target) + "/") for name in base_names
    }
    component_bundle = work / "component.tar.gz"
    with tarfile.open(setup, "r:gz") as source, tarfile.open(component_bundle, "x:gz") as output:
        for member in source:
            if member.name not in bundle_names:
                output.addfile(member, source.extractfile(member) if member.isfile() else None)
        marker = tarfile.TarInfo("setup-kast/" + MARKER)
        marker.size, marker.mode = len(encoded(composition)), 0o444
        output.addfile(marker, io.BytesIO(encoded(composition)))
    _ = component.replace(runtime)
    _ = component_bundle.replace(setup)
    for path in (runtime, setup):
        _ = path.with_name(path.name + ".sha256").write_text(
            f"{release.file_digest(path)}  {path.name}\n"
        )
    return composition


def extract_base(archive: Path, expected: Composition, root: Path) -> BaseManifest:
    if (
        archive.is_symlink()
        or archive.stat().st_size != expected.base.bytes
        or release.file_digest(archive) != expected.base.sha256
    ):
        raise ValueError("base artifact bytes")
    target = HostPlatform(expected.target)
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if len(entries) > 100001 or len({item.filename for item in entries}) != len(entries):
            raise ValueError("base inventory")
        header = source.getinfo(BASE_MANIFEST)
        if not 0 < header.file_size <= 16777216:
            raise ValueError("base metadata size")
        raw = source.read(header)
        value = release.document(raw)
        if (
            not release.mapping(value)
            or set(value)
            != {
                "schemaVersion",
                "kind",
                "target",
                "donorSha256",
                "recipeInputs",
                "supportInputs",
                "files",
            }
            or type(value["schemaVersion"]) is not int
            or value["schemaVersion"] != 1
            or value["kind"] != "KAST_RUNTIME_BASE_V1"
            or value["target"] != target.value
        ):
            raise ValueError("base manifest")
        # Reuse the runtime inventory's path, link, size and digest boundary, without
        # inventing a Host or bootstrap witness for the vendor-only subset.
        files = runtime_manifest_entries(value["files"], target)
        if not files or any(not vendor(item.path, target) for item in files):
            raise ValueError("nonvendor base member")
        donor = value["donorSha256"]
        if not isinstance(donor, str) or not release.DIGEST.fullmatch(donor):
            raise ValueError("base donor")
        recipe_inputs = input_records(value["recipeInputs"])
        if tuple(item.path for item in recipe_inputs) != RECIPE_INPUTS:
            raise ValueError("base recipe inventory")
        support = input_records(value["supportInputs"])
        actual_support = tuple(
            RecipeInput(item.path, item.sha256)
            for item in files
            if isinstance(item, RuntimeFile) and "/kast-linux-support/" in item.path
        )
        if support != actual_support:
            raise ValueError("base support inputs")
        if {entry.filename for entry in entries} != {item.path for item in files} | {BASE_MANIFEST}:
            raise ValueError("base extras")
        root.mkdir(mode=0o700)
        for item in files:
            destination = root / item.path
            destination.parent.mkdir(parents=True, exist_ok=True)
            member = source.getinfo(item.path)
            if isinstance(item, RuntimeFile):
                if member.file_size != item.bytes:
                    raise ValueError("base file size")
                with source.open(member) as incoming, destination.open("xb") as output:
                    shutil.copyfileobj(incoming, output)
                if release.file_digest(destination) != item.sha256:
                    raise ValueError("base file digest")
                destination.chmod(0o555 if item.executable else 0o444)
            else:
                if member.file_size > 4096 or source.read(member) != os.fsencode(item.target):
                    raise ValueError("base link bytes")
        for item in files:
            if isinstance(item, RuntimeLink):
                (root / item.path).symlink_to(item.target)
        for item in files:
            if not (root / item.path).resolve(strict=True).is_relative_to(root):
                raise ValueError("base link escape")
        _ = (root / BASE_MANIFEST).write_bytes(raw)
    return BaseManifest(target.value, donor, recipe_inputs, support, files)


def input_records(value: object) -> tuple[RecipeInput, ...]:
    if not release.sequence(value):
        raise ValueError("base inputs")
    result: list[RecipeInput] = []
    for item in value:
        if not release.mapping(item) or set(item) != {"path", "sha256"}:
            raise ValueError("base input")
        name, digest = item["path"], item["sha256"]
        if (
            not isinstance(name, str)
            or not bundle.safe_path(name)
            or not isinstance(digest, str)
            or not release.DIGEST.fullmatch(digest)
        ):
            raise ValueError("base input identity")
        result.append(RecipeInput(name, digest))
    if len({item.path for item in result}) != len(result):
        raise ValueError("duplicate base input")
    return tuple(result)


def runtime_manifest_entries(
    value: object, target: HostPlatform
) -> tuple[RuntimeFile | RuntimeLink, ...]:
    from managed_product import runtime_entries

    files = runtime_entries(value)
    names = {item.path for item in files}
    for item in files:
        if isinstance(item, RuntimeLink) and not link_owned(item.path, item.target, names):
            raise ValueError("base link target")
    return files


def link_owned(path: str, target: str, names: set[str]) -> bool:
    resolved = os.path.normpath(str(Path(path).parent / target))
    return resolved in names or any(name.startswith(resolved + "/") for name in names)


def overlay(base: Path, manifest: BaseManifest, destination: Path, *, setup: bool = False) -> None:
    """Copy, never hard-link: each composition owns its files and cannot mutate the base."""
    for item in manifest.files:
        name = (
            "runtime/" + item.path.removeprefix(prefix(HostPlatform(manifest.target)) + "/")
            if setup
            else item.path
        )
        output = destination / name
        if os.path.lexists(output) or any(parent.is_symlink() for parent in output.parents):
            raise ValueError("base/component overlap")
        output.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(item, RuntimeLink):
            output.symlink_to(item.target)
        else:
            source = base / item.path
            if source.is_symlink() or release.file_digest(source) != item.sha256:
                raise ValueError("base changed")
            _ = shutil.copy2(source, output)


def verify_base(root: Path, manifest: BaseManifest, raw: bytes) -> None:
    if root.resolve() != root or not root.is_dir() or root.stat().st_uid != os.getuid():
        raise ValueError("base ownership")
    if (root / BASE_MANIFEST).is_symlink() or (root / BASE_MANIFEST).read_bytes() != raw:
        raise ValueError("base metadata changed")
    actual = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if not path.is_dir() or path.is_symlink()
    }
    if actual != {item.path for item in manifest.files} | {BASE_MANIFEST}:
        raise ValueError("base inventory changed")
    for item in manifest.files:
        path = root / item.path
        if path.parent.resolve() != path.parent:
            raise ValueError("base linked ancestor")
        if isinstance(item, RuntimeLink):
            if (
                not path.is_symlink()
                or os.readlink(path) != item.target
                or not path.resolve(strict=True).is_relative_to(root)
            ):
                raise ValueError("base link changed")
        elif (
            path.is_symlink()
            or not path.is_file()
            or path.stat().st_size != item.bytes
            or release.file_digest(path) != item.sha256
            or stat.S_IMODE(path.stat().st_mode) != (0o555 if item.executable else 0o444)
        ):
            raise ValueError("base file changed")


def hydrate_bundle(
    archive: Path, destination: Path, composition: Composition, base: Path, manifest: BaseManifest
) -> Path:
    extracted = bundle.extract_bundle(archive, destination, release.file_digest(archive))
    if isinstance(extracted, bundle.BundleRejected):
        raise ValueError("component bundle")
    marker = extracted.source / MARKER
    if reference(marker.read_bytes()) != composition:
        raise ValueError("bundle base reference")
    marker.unlink()
    metadata = bundle.read_manifest((extracted.source / "manifest.json").read_bytes())
    if isinstance(metadata, bundle.BundleRejected):
        raise ValueError("component bundle manifest")
    expected = {item.path for item in metadata.files}
    subset = tuple(
        item
        for item in manifest.files
        if "runtime/" + item.path.removeprefix(prefix(HostPlatform(manifest.target)) + "/")
        in expected
    )
    overlay(base, replace(manifest, files=subset), extracted.source, setup=True)
    actual = {
        path.relative_to(extracted.source).as_posix()
        for path in extracted.source.rglob("*")
        if path.is_file() or path.is_symlink()
    }
    if actual != expected | {"manifest.json"} or isinstance(
        bundle.examine_files(extracted.source, metadata.files), bundle.BundleRejected
    ):
        raise ValueError("composed bundle inventory")
    return extracted.source


def admit_archives(directory: Path, target: release.ProductTarget) -> None:
    """Read-only retained-input binding. No assembly, extraction, or dependency resolution."""
    from managed_product import runtime_manifest

    base = target.runtimeBase
    if base is None:
        raise ValueError("base required")
    composition = Composition(target.target, base)
    runtime = directory / target.runtime.name
    if read_reference(runtime) != composition:
        raise ValueError("runtime base marker")
    with zipfile.ZipFile(directory / base.name) as archive:
        header = archive.getinfo(BASE_MANIFEST)
        if not 0 < header.file_size <= 16777216:
            raise ValueError("base manifest size")
        value = release.document(archive.read(header))
        if (
            not release.mapping(value)
            or value.get("kind") != "KAST_RUNTIME_BASE_V1"
            or value.get("schemaVersion") != 1
            or value.get("target") != target.target
            or value.get("donorSha256") != target.inputs.donorSha256
        ):
            raise ValueError("base identity")
        recipes = input_records(value.get("recipeInputs"))
        if tuple(item.path for item in recipes) != RECIPE_INPUTS or not any(
            item.path == "composition_recipe.py"
            and item.sha256 == target.inputs.compositionRecipeSha256
            for item in recipes
        ):
            raise ValueError("base recipe binding")
        entries = runtime_manifest_entries(value.get("files"), HostPlatform(target.target))
        names = {item.path for item in entries}
        if (
            any(not vendor(item.path, HostPlatform(target.target)) for item in entries)
            or len(archive.infolist()) != len(names) + 1
            or set(archive.namelist()) != names | {BASE_MANIFEST}
        ):
            raise ValueError("base inventory")
    with zipfile.ZipFile(runtime) as archive:
        full = runtime_manifest(
            archive.read("manifest.json"),
            target.runtime.name.split("-v", 1)[1].rsplit("-" + target.target, 1)[0],
            HostPlatform(target.target),
        )
        full_entries = set(full.files)
        if any(item not in full_entries for item in entries) or set(archive.namelist()) != (
            {item.path for item in full.files} - names
        ) | {"manifest.json", MARKER}:
            raise ValueError("runtime component inventory")
    with tarfile.open(directory / target.bundle.name, "r:gz") as archive:
        markers = [item for item in archive if item.name == "setup-kast/" + MARKER]
        if len(markers) != 1 or not markers[0].isfile() or markers[0].size > 65536:
            raise ValueError("bundle base marker")
        stream = archive.extractfile(markers[0])
        if stream is None:
            raise ValueError("bundle base marker missing")
        with stream:
            if reference(stream.read()) != composition:
                raise ValueError("bundle base binding")


def project_native(runtime: Path, setup: Path, work: Path) -> tuple[Path, Path, Composition]:
    """Producer-only native projection; never resolves dependencies."""
    from managed_product import extract_runtime

    composition = read_reference(runtime)
    if composition is None:
        raise ValueError("split runtime required")
    base = work / "base"
    metadata = extract_base(runtime.parent / composition.base.name, composition, base)
    target = HostPlatform(composition.target)
    with zipfile.ZipFile(runtime) as source:
        raw = release.document(source.read("manifest.json"))
    if not release.mapping(raw) or not isinstance(version := raw.get("version"), str):
        raise ValueError("runtime version")
    expanded = work / "runtime"
    manifest = extract_runtime(
        runtime, expanded, version, target, base=(base, metadata, composition)
    )
    projected_runtime = work / "native-runtime.zip"
    write_zip(
        projected_runtime,
        (("manifest.json", expanded / "manifest.json", stat.S_IFREG | 0o444),)
        + tuple(
            (
                item.path,
                os.fsencode(item.target) if isinstance(item, RuntimeLink) else expanded / item.path,
                stat.S_IFLNK | 0o777
                if isinstance(item, RuntimeLink)
                else stat.S_IFREG | (0o555 if item.executable else 0o444),
            )
            for item in manifest.files
        ),
    )
    extracted = work / "bundle"
    extracted.mkdir()
    source = hydrate_bundle(setup, extracted, composition, base, metadata)
    projected_bundle = work / "native-setup.tar.gz"
    with (
        projected_bundle.open("xb") as stream,
        gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0) as compressed,
        tarfile.open(fileobj=compressed, mode="w") as archive,
    ):
        for path in sorted(source.rglob("*")):
            if path.is_file() or path.is_symlink():
                member = archive.gettarinfo(
                    str(path), "setup-kast/" + path.relative_to(source).as_posix()
                )
                member.uid = member.gid = member.mtime = 0
                member.uname = member.gname = ""
                with path.open("rb") if not path.is_symlink() else io.BytesIO() as incoming:
                    archive.addfile(member, incoming if member.isfile() else None)
    return projected_runtime, projected_bundle, composition
