"""Check one assembled artifact and install it in an owned session."""
from dataclasses import asdict, dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tarfile
import zipfile
import xml.etree.ElementTree as ET

from installer_fixture import InstallerFixture, FixtureFailure, FixtureRejected, admitted_tools


@dataclass(frozen=True)
class IdeaProductInfo:
    buildNumber: str
    dataDirectoryName: str = "IntelliJIdea2026.2"
    version: str = "2026.2.3"


@dataclass(frozen=True)
class AdmittedArtifactPair:
    control_version: str
    host_version: str
    control: Path
    host: Path
    host_release_record: Path
    host_installer: Path


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise AssertionError("installed-product: " + detail)


def verify_artifacts(product: Path, control: Path, plugin: Path, host_record: Path) -> AdmittedArtifactPair:
    metadata = json.loads((product / "share/kast/ide-host.json").read_text())
    require(set(metadata) == {"schemaVersion", "productVersion", "execution", "ideaBuild",
                             "kotlinPluginBuild", "requiredHostedContract"}, "metadata fields")
    require(metadata["schemaVersion"] == 2 and metadata["execution"] == "existing_ide", "execution metadata")
    require(metadata["kotlinPluginBuild"] == metadata["ideaBuild"] + "-IJ", "plugin build")
    control_match = re.fullmatch(r"kast-control-v(\d+\.\d+\.\d+)-macos-aarch64\.tar\.gz", control.name)
    require(control_match is not None and metadata["productVersion"] == control_match.group(1), "control version")
    record = json.loads(host_record.read_text())
    require(set(record) == {"type", "hostedPluginVersion", "artifact", "providedHostedContract",
                            "supportedIntellijReleaseLine", "sourceRevision"}, "host release fields")
    require(record["type"] == "HOST_RELEASE", "host release discriminator")
    host_match = re.fullmatch(r"kast-ide-hosted-v(\d+\.\d+\.\d+)-idea-(\d{3})\.zip", plugin.name)
    require(host_match is not None and record["hostedPluginVersion"] == host_match.group(1)
            and record["supportedIntellijReleaseLine"] == host_match.group(2), "host version and platform")
    require(host_record.name == f"kast-host-release-v{record['hostedPluginVersion']}.json", "host record identity")
    artifact = record["artifact"]
    require(set(artifact) == {"fileName", "sha256", "bytes"}
            and artifact["fileName"] == plugin.name and artifact["bytes"] == plugin.stat().st_size, "plugin identity")
    require(artifact["sha256"] == "sha256:" + hashlib.sha256(plugin.read_bytes()).hexdigest(), "plugin digest")
    require(record["providedHostedContract"] == metadata["requiredHostedContract"], "fresh pair contract")
    for name in ("operation-registry.json", "wire-schema.json", "ide-host.json", "knowledge/manifest.json"):
        require((product / "share/kast" / name).is_file(), "missing resource " + name)
    with zipfile.ZipFile(plugin) as archive:
        names = archive.namelist()
        require(bool(names) and all(name.startswith("kast-ide-hosted/") for name in names), "plugin layout")
        plugin_jar = f"kast-ide-hosted/lib/kast-ide-hosted-{record['hostedPluginVersion']}.jar"
        require(plugin_jar in names, "plugin jar")
        with zipfile.ZipFile(io.BytesIO(archive.read(plugin_jar))) as jar:
            require(ET.fromstring(jar.read("META-INF/plugin.xml")).findtext("version")
                    == record["hostedPluginVersion"], "embedded host version")
            require(json.loads(jar.read("hosted-contract.json")) == record["providedHostedContract"],
                    "embedded host contract")
    with tarfile.open(control) as archive:
        for launcher in ("bin/kast", "bin/kast-codex", "share/kast/libexec/kast-daemon",
                         "share/kast/libexec/kast-service", "share/kast/libexec/kast-management"):
            member = archive.getmember(launcher)
            require(member.isfile() and member.mode & 0o111 == 0o111, "launcher " + launcher)
        require(archive.getmember("share/kast/knowledge/manifest.json").isfile(), "knowledge manifest")
        require(archive.getmember("share/kast/reset-fence-v1").isfile(), "reset lifecycle marker")
    helper = product / "share/kast/host-installation.py"
    require(helper.is_file() and not helper.is_symlink(), "host installer")
    return AdmittedArtifactPair(control_match.group(1), host_match.group(1), control, plugin, host_record, helper)


def verify_launcher(fixture: InstallerFixture, product: Path, pair: AdmittedArtifactPair) -> None:
    launcher = product / "bin/kast"
    require(launcher.is_file() and os.access(launcher, os.X_OK), "launcher absent")
    environment = dict(fixture.environment)
    environment.pop("JAVA_TOOL_OPTIONS", None)
    environment.pop("_JAVA_OPTIONS", None)
    environment["JAVA_OPTS"] = f'-Duser.home="{fixture.root / "home"}"'
    version = subprocess.run([str(launcher), "--version"], cwd=fixture.root / "workspace",
                             env=environment, capture_output=True, text=True, timeout=60)
    require(version.returncode == 0 and version.stdout.startswith(f"kast {pair.control_version} ")
            and version.stdout.strip().endswith("(IntelliJ plugin)"), "launcher version")
    rejected = subprocess.run([str(launcher), "tool"], cwd=fixture.root / "workspace",
                              env=environment, capture_output=True, text=True, timeout=60)
    require(rejected.returncode != 0, "unsupported command accepted")
    require(json.loads(rejected.stderr.splitlines()[-1]) == {
        "status": "rejected", "boundary": "usage", "reason": "unsupported-private-installer-command"
    }, "unsupported command result")


def verify_assembled_installer(fixture: InstallerFixture, pair: AdmittedArtifactPair, product: Path) -> None:
    if "java" not in fixture.tools:
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    assets = fixture.root / "release-assets"
    assets.mkdir()
    for source in (pair.control, pair.host, pair.host_release_record, pair.host_installer):
        destination = assets / source.name
        shutil.copyfile(source, destination)
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        (assets / (source.name + ".sha256")).write_text(f"{digest}  {source.name}\n")

    metadata = json.loads((product / "share/kast/ide-host.json").read_text())
    idea = fixture.root / "IntelliJ IDEA.app/Contents"
    for relative in ("Resources", "plugins/Kotlin", "jbr/Contents/Home/bin"):
        (idea / relative).mkdir(parents=True)
    (idea / "Resources/build.txt").write_text("IU-" + metadata["ideaBuild"] + "\n")
    (idea / "Resources/product-info.json").write_text(json.dumps(asdict(IdeaProductInfo(metadata["ideaBuild"]))))
    (idea / "jbr/Contents/Home/release").write_text('JAVA_VERSION="25.0.2"\nOS_ARCH="aarch64"\n')
    java = idea / "jbr/Contents/Home/bin/java"
    java.write_text("#!/bin/sh\nexec " + shlex.quote(str(fixture.tools["java"])) + ' "$@"\n')
    java.chmod(0o755)

    environment = dict(fixture.environment)
    installation = fixture.root / "installed"
    environment.update({
        "KAST_VERSION": pair.control_version,
        "KAST_HOST_VERSION": pair.host_version,
        "KAST_INSTALL_ASSETS_DIRECTORY": str(assets),
        "KAST_INSTALL_ROOT": str(installation),
        "KAST_BIN_DIR": str(fixture.root / "bin"),
        "KAST_INSTALL_PROFILE": "session",
        "NO_COLOR": "1",
    })
    result = subprocess.run(
        [str(fixture.tools["bash"]), str(Path(__file__).resolve().parent.parent / "install.sh"),
         "--idea-home", str(idea), "--verbose"],
        env=environment, cwd=fixture.root / "workspace", capture_output=True, text=True, timeout=120,
    )
    reports = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    require(result.returncode == 0 and (installation / "installation").is_dir()
            and not (installation / "installation").is_symlink()
            and not (installation / "current").exists()
            and not (installation / "versions").exists()
            and not (installation / "recovery/replacement").exists()
            and any(report.get("operation") == "installation.install"
                    and report.get("semanticVersion") == pair.control_version
                    and report.get("status") == "installed" for report in reports),
            "assembled installer rejected release:\n" + result.stderr[-4096:] + "\n" + result.stdout[-4096:])


def main() -> None:
    paths = {}
    for key in ("KAST_INSTALLED_PRODUCT", "KAST_CONTROL_ARCHIVE", "KAST_HOSTED_PLUGIN_ARCHIVE", "KAST_HOST_RELEASE_RECORD"):
        value = os.environ.get(key)
        if value is None or not Path(value).is_absolute():
            raise FixtureRejected(FixtureFailure.INVALID_INPUT)
        paths[key] = Path(value).resolve()
    if not paths["KAST_INSTALLED_PRODUCT"].is_dir() or not all(
            paths[key].is_file() for key in ("KAST_CONTROL_ARCHIVE", "KAST_HOSTED_PLUGIN_ARCHIVE", "KAST_HOST_RELEASE_RECORD")):
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    with InstallerFixture(admitted_tools()) as fixture:
        product = fixture.stage_product(paths["KAST_INSTALLED_PRODUCT"])
        pair = verify_artifacts(product, paths["KAST_CONTROL_ARCHIVE"], paths["KAST_HOSTED_PLUGIN_ARCHIVE"],
                                paths["KAST_HOST_RELEASE_RECORD"])
        verify_launcher(fixture, product, pair)
        verify_assembled_installer(fixture, pair, product)
        fixture.mark_passed()
    print("installed-product: artifact identity, launcher, and session installer passed")


if __name__ == "__main__":
    main()
