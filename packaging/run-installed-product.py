"""Enter the shared private environment before the passive shell acceptance."""
from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess

from acceptance_environment import AcceptanceEnvironment, EnvironmentFailure, EnvironmentRejected, admitted_tools


@dataclass(frozen=True)
class IdeaProductInfo:
    buildNumber: str
    dataDirectoryName: str = "IntelliJIdea2026.2"
    version: str = "2026.2.3"


def verify_assembled_installer(fixture, control: Path, plugin: Path, product: Path):
    match = re.fullmatch(r"kast-control-v(\d+\.\d+\.\d+)-macos-aarch64\.tar\.gz", control.name)
    if match is None or "java" not in fixture.tools:
        raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
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
    if (result.returncode != 0 or not (installation / "current").is_symlink()
            or not any(report.get("operation") == "installation.install"
                       and report.get("semanticVersion") == version
                       and report.get("status") == "installed" for report in reports)):
        raise AssertionError("assembled installer rejected its release:\n" + result.stderr[-4096:]
                             + "\n" + result.stdout[-4096:])
    print("installed-product: assembled release installed in a private session fixture")


def main():
    # These four paths are explicit build inputs/outputs, not inherited settings.
    paths = {}
    for key in ("KAST_INSTALLED_PRODUCT", "KAST_CONTROL_ARCHIVE",
                "KAST_HOSTED_PLUGIN_ARCHIVE", "KAST_INSTALLED_REPORT_DIRECTORY"):
        value = os.environ.get(key)
        if value is None or not Path(value).is_absolute():
            raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
        paths[key] = str(Path(value).resolve())
    if (not Path(paths["KAST_INSTALLED_PRODUCT"]).is_dir()
            or not Path(paths["KAST_CONTROL_ARCHIVE"]).is_file()
            or not Path(paths["KAST_HOSTED_PLUGIN_ARCHIVE"]).is_file()):
        raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
    with AcceptanceEnvironment(admitted_tools()) as fixture:
        paths["KAST_INSTALLED_PRODUCT"] = str(fixture.stage_product(Path(paths["KAST_INSTALLED_PRODUCT"])))
        environment = dict(fixture.environment)
        subprocess.run([
            str(fixture.tools["bash"]), str(Path(__file__).with_name("test-installed-product.sh")),
            "--isolated-fixture", str(fixture.root),
            "--product", paths["KAST_INSTALLED_PRODUCT"],
            "--control-archive", paths["KAST_CONTROL_ARCHIVE"],
            "--plugin-archive", paths["KAST_HOSTED_PLUGIN_ARCHIVE"],
            "--report-directory", paths["KAST_INSTALLED_REPORT_DIRECTORY"],
        ], env=environment, cwd=fixture.root / "workspace", check=True, timeout=120)
        verify_assembled_installer(
            fixture,
            Path(paths["KAST_CONTROL_ARCHIVE"]),
            Path(paths["KAST_HOSTED_PLUGIN_ARCHIVE"]),
            Path(paths["KAST_INSTALLED_PRODUCT"]),
        )
        fixture.mark_passed()


if __name__ == "__main__":
    main()
