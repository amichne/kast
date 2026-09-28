"""Check one assembled artifact and install it in an owned session."""
from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tarfile
import zipfile

from installer_fixture import InstallerFixture, FixtureFailure, FixtureRejected, admitted_tools


@dataclass(frozen=True)
class IdeaProductInfo:
    buildNumber: str
    dataDirectoryName: str = "IntelliJIdea2026.2"
    version: str = "2026.2.3"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise AssertionError("installed-product: " + detail)


def verify_artifacts(product: Path, control: Path, plugin: Path) -> None:
    metadata = json.loads((product / "share/kast/ide-host.json").read_text())
    require(set(metadata) == {"schemaVersion", "productVersion", "execution", "ideaBuild",
                             "kotlinPluginBuild", "fileName", "sha256", "bytes"}, "metadata fields")
    require(metadata["schemaVersion"] == 1 and metadata["execution"] == "existing_ide", "execution metadata")
    require(metadata["kotlinPluginBuild"] == metadata["ideaBuild"] + "-IJ", "plugin build")
    require(metadata["fileName"] == plugin.name and metadata["bytes"] == plugin.stat().st_size, "plugin identity")
    require(metadata["sha256"] == "sha256:" + hashlib.sha256(plugin.read_bytes()).hexdigest(), "plugin digest")
    for name in ("operation-registry.json", "wire-schema.json", "ide-host.json", "knowledge/manifest.json"):
        require((product / "share/kast" / name).is_file(), "missing resource " + name)
    with zipfile.ZipFile(plugin) as archive:
        names = archive.namelist()
        require(bool(names) and all(name.startswith("kast-ide-hosted/") for name in names), "plugin layout")
        require(any("/lib/kast-ide-hosted-" in name and name.endswith(".jar") for name in names), "plugin jar")
    with tarfile.open(control) as archive:
        for launcher in ("bin/kast", "bin/kast-codex", "share/kast/libexec/kast-daemon",
                         "share/kast/libexec/kast-service"):
            member = archive.getmember(launcher)
            require(member.isfile() and member.mode & 0o111 == 0o111, "launcher " + launcher)
        require(archive.getmember("share/kast/knowledge/manifest.json").isfile(), "knowledge manifest")


def verify_launcher(fixture: InstallerFixture, product: Path) -> None:
    launcher = product / "bin/kast"
    require(launcher.is_file() and os.access(launcher, os.X_OK), "launcher absent")
    environment = dict(fixture.environment)
    environment.pop("JAVA_TOOL_OPTIONS", None)
    environment.pop("_JAVA_OPTIONS", None)
    environment["JAVA_OPTS"] = f'-Duser.home="{fixture.root / "home"}"'
    version = subprocess.run([str(launcher), "--version"], cwd=fixture.root / "workspace",
                             env=environment, capture_output=True, text=True, timeout=60)
    require(version.returncode == 0 and version.stdout.startswith("kast ")
            and version.stdout.strip().endswith("(IntelliJ plugin)"), "launcher version")
    rejected = subprocess.run([str(launcher), "tool"], cwd=fixture.root / "workspace",
                              env=environment, capture_output=True, text=True, timeout=60)
    require(rejected.returncode != 0, "unsupported command accepted")
    require(json.loads(rejected.stderr.splitlines()[-1]) == {
        "status": "rejected", "boundary": "usage", "reason": "unsupported-private-installer-command"
    }, "unsupported command result")


def verify_assembled_installer(fixture: InstallerFixture, control: Path, plugin: Path, product: Path) -> None:
    match = re.fullmatch(r"kast-control-v(\d+\.\d+\.\d+)-macos-aarch64\.tar\.gz", control.name)
    if match is None or "java" not in fixture.tools:
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    version = match.group(1)
    assets = fixture.root / "release-assets"
    assets.mkdir()
    for source in (control, plugin):
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
        "KAST_VERSION": version,
        "KAST_INSTALL_ASSETS_DIRECTORY": str(assets),
        "KAST_INSTALL_ROOT": str(installation),
        "KAST_BIN_DIR": str(fixture.root / "bin"),
        "KAST_INSTALL_PROFILE": "session",
        "NO_COLOR": "1",
    })
    result = subprocess.run(
        [str(fixture.tools["bash"]), str(Path(__file__).resolve().parent.parent / "install.sh"),
         "--idea-home", str(idea)],
        env=environment, cwd=fixture.root / "workspace", capture_output=True, text=True, timeout=120,
    )
    reports = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    require(result.returncode == 0 and (installation / "current").is_symlink()
            and any(report.get("operation") == "installation.install"
                    and report.get("semanticVersion") == version
                    and report.get("status") == "installed" for report in reports),
            "assembled installer rejected release:\n" + result.stderr[-4096:] + "\n" + result.stdout[-4096:])


def main() -> None:
    paths = {}
    for key in ("KAST_INSTALLED_PRODUCT", "KAST_CONTROL_ARCHIVE", "KAST_HOSTED_PLUGIN_ARCHIVE"):
        value = os.environ.get(key)
        if value is None or not Path(value).is_absolute():
            raise FixtureRejected(FixtureFailure.INVALID_INPUT)
        paths[key] = Path(value).resolve()
    if not paths["KAST_INSTALLED_PRODUCT"].is_dir() or not all(
            paths[key].is_file() for key in ("KAST_CONTROL_ARCHIVE", "KAST_HOSTED_PLUGIN_ARCHIVE")):
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    with InstallerFixture(admitted_tools()) as fixture:
        product = fixture.stage_product(paths["KAST_INSTALLED_PRODUCT"])
        verify_artifacts(product, paths["KAST_CONTROL_ARCHIVE"], paths["KAST_HOSTED_PLUGIN_ARCHIVE"])
        verify_launcher(fixture, product)
        verify_assembled_installer(fixture, paths["KAST_CONTROL_ARCHIVE"],
                                   paths["KAST_HOSTED_PLUGIN_ARCHIVE"], product)
        fixture.mark_passed()
    print("installed-product: artifact identity, launcher, and session installer passed")


if __name__ == "__main__":
    main()
