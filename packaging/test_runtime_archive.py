"""The same installer-free ZIP contract is used for every native platform."""

from __future__ import annotations

import hashlib
import io
import json
import tarfile
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path

import pytest

import runtime_base
from composed_bundle import BundleRejected, PackedBundle
from composition_platform import HostPlatform
from managed_headless import AssemblyRecord
from managed_product import extract_runtime
from runtime_archive import pack_runtime


def runtime_fixture(
    tmp_path: Path, target: HostPlatform, host: bytes = b"Host"
) -> tuple[Path, Path]:
    root = tmp_path / "assembled"
    home = root / target.layout.home
    bootstrap = home / "third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
    bootstrap.parent.mkdir(parents=True)
    _ = bootstrap.write_bytes(b"bootstrap")
    _ = (home / "kast-headless.json").write_text(
        json.dumps(
            asdict(
                AssemblyRecord(
                    1,
                    str(home),
                    target.layout.archive_sha256,
                    hashlib.sha256(b"bootstrap").hexdigest(),
                    "262.10315.125",
                    "262.10315.125-IJ",
                )
            )
        )
    )
    executable = home / "bin/idea"
    executable.parent.mkdir()
    _ = executable.write_bytes(b"native")
    executable.chmod(0o500)
    link = executable.with_name("idea-link")
    link.symlink_to("idea")
    plugins = root / "state/home/.local/share/JetBrains/Kast/plugins"
    plugin = plugins / "kast-ide-hosted/lib/kast-ide-hosted.jar"
    plugin.parent.mkdir(parents=True)
    _ = plugin.write_bytes(host)
    selection = root / "state/home/.local/share/kast/installation/config/selected-ide.json"
    selection.parent.mkdir(parents=True)
    _ = selection.write_text(json.dumps(asdict(Selection(PluginTarget(str(plugins))))))
    support: Path | None = None
    if target.is_linux:
        support = tmp_path / "linux-support"
        for name in ("lib/font.so", "fonts/font.ttf", "licenses/copyright", "fonts.conf"):
            path = support / name
            path.parent.mkdir(parents=True, exist_ok=True)
            _ = path.write_bytes(b"support")
    output = tmp_path / "runtime.zip"
    packed = pack_runtime(root, output, "1.2.3", target, linux_support=support)
    assert isinstance(packed, PackedBundle), packed
    assert packed.sha256 == hashlib.sha256(output.read_bytes()).hexdigest()
    return root, output


@pytest.mark.parametrize("target", tuple(HostPlatform))
def test_payload_has_no_installer_or_mutable_state(tmp_path: Path, target: HostPlatform) -> None:
    root, output = runtime_fixture(tmp_path, target)
    with zipfile.ZipFile(output) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        assert set(manifest) == {
            "version",
            "platform",
            "platformBuild",
            "distributionSha256",
            "bootstrapSha256",
            "files",
            "schemaVersion",
            "kind",
        }
        assert manifest["kind"] == "KAST_RUNTIME_V1"
        assert manifest["schemaVersion"] == 1
        assert manifest["platform"] == target.value
        prefix = "host" if target.is_linux else "host/Kast.app/Contents"
        records = {item["path"]: item for item in manifest["files"]}
        assert records[prefix + "/bin/idea-link"] == {
            "path": prefix + "/bin/idea-link",
            "target": "idea",
            "type": "SYMLINK",
        }
        assert records[prefix + "/bin/idea"]["executable"] is True
        assert (
            records["components/plugins/kast-ide-hosted/lib/kast-ide-hosted.jar"]["type"] == "FILE"
        )
        assert not any(
            "state/" in name or name.endswith((".py", ".pyz", "install.sh", "kast-headless.json"))
            for name in archive.namelist()
        )
    assert isinstance(pack_runtime(root, output, "1.2.3", target), BundleRejected)


@pytest.mark.parametrize("target", (HostPlatform.MACOS_ARM64, HostPlatform.LINUX_X86_64))
def test_host_change_reuses_exact_base_and_composes_owned_bytes(
    tmp_path: Path, target: HostPlatform
) -> None:
    bases: list[bytes] = []
    components: list[bytes] = []
    for index, host in enumerate((b"Host one", b"Host two")):
        directory = tmp_path / str(index)
        directory.mkdir()
        _, runtime = runtime_fixture(directory, target, host)
        setup = directory / "setup.tar.gz"
        with tarfile.open(setup, "w:gz") as archive:
            member = tarfile.TarInfo("setup-kast/fixture")
            member.size = 7
            archive.addfile(member, io.BytesIO(b"fixture"))
        work = directory / "split"
        work.mkdir()
        selected = runtime_base.split(runtime, setup, target, Path(__file__).parent, work)
        base_archive = directory / selected.base.name
        bases.append(base_archive.read_bytes())
        components.append(runtime.read_bytes())
        base = directory / "base"
        manifest = runtime_base.extract_base(base_archive, selected, base)
        before = base_archive.read_bytes()
        composed = directory / "composed"
        _ = extract_runtime(runtime, composed, "1.2.3", target, base=(base, manifest, selected))
        vendor = runtime_base.prefix(target) + "/bin/idea"
        assert (composed / vendor).stat().st_mode & 0o777 == 0o555
        assert (composed / (vendor + "-link")).is_symlink()
        assert (
            composed / "components/plugins/kast-ide-hosted/lib/kast-ide-hosted.jar"
        ).read_bytes() == host
        (composed / vendor).chmod(0o700)
        _ = (composed / vendor).write_bytes(b"owned mutation")
        assert (base / vendor).read_bytes() == b"native"
        assert before == base_archive.read_bytes()
        with zipfile.ZipFile(runtime) as archive:
            assert vendor not in archive.namelist()
            assert runtime_base.MARKER in archive.namelist()
        with pytest.raises(ValueError, match="base artifact bytes"):
            _ = base_archive.write_bytes(b"corrupt")
            _ = runtime_base.extract_base(base_archive, selected, directory / "rejected")
        assert not (directory / "rejected").exists()
    assert bases[0] == bases[1]
    assert components[0] != components[1]


@dataclass(frozen=True)
class PluginTarget:
    root: str
    type: str = "RECORDED"


@dataclass(frozen=True)
class Selection:
    hostPluginTarget: PluginTarget
    type: str = "resolved"
