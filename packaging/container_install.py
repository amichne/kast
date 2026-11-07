"""Build the native composed Host through the existing shared installer owners."""

from __future__ import annotations

import hashlib
import platform
from dataclasses import replace
from pathlib import Path
from urllib.request import urlopen

from composed_bundle import BundleRejected, admit_trust_workspace, configure_trusted_workspace
from composed_host import compose
from composition_platform import UnsupportedHost, host_platform
from composition_recipe import BUILD
from managed_headless import Input, Installed, Rejected, install


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main() -> int:
    target = host_platform(platform.system(), platform.machine())
    if isinstance(target, UnsupportedHost):
        print("kast-container: stage=compose outcome=UNSUPPORTED_PLATFORM", flush=True)
        return 2
    base = Path("/opt/kast-build")
    archive = Path("/tmp/donor.tar.gz")
    suffix = "-aarch64" if target.layout.launch_arch == "aarch64" else ""
    url = f"https://download.jetbrains.com/idea/idea-2026.2.2{suffix}.tar.gz"
    print(f"kast-container: stage=donor outcome=DOWNLOADING platform={target.value}", flush=True)
    with urlopen(url, timeout=120) as response, archive.open("wb") as output:
        while data := response.read(1024 * 1024):
            _ = output.write(data)
    if sha256(archive) != target.layout.archive_sha256:
        print("kast-container: stage=donor outcome=DIGEST_MISMATCH", flush=True)
        return 2
    bootstrap = base / "bootstrap.zip"
    inputs = Input(
        Path("/tmp/kd"),
        archive,
        target.layout.archive_sha256,
        bootstrap,
        sha256(bootstrap),
        base / "assets",
        "0.0.0",
        BUILD,
    )
    installer = base / "install.sh"
    print("kast-container: stage=donor outcome=INSTALLING", flush=True)
    donor = install(inputs, installer)
    if not isinstance(donor, Installed):
        print(f"kast-container: stage=donor outcome={donor.reason.value}", flush=True)
        return 2
    manifest = inputs.root / "assembly-manifest.json"
    print("kast-container: stage=compose outcome=STARTED", flush=True)
    result = compose(
        replace(inputs, root=Path("/opt/kast")),
        Path(donor.assembly.installation),
        manifest,
        sha256(manifest),
        installer,
    )
    if not isinstance(result, Installed):
        print(f"kast-container: stage=compose outcome={result.reason.value}", flush=True)
        return 2
    trust = admit_trust_workspace(Path("/work"), Path("/opt/kast"))
    if isinstance(trust, BundleRejected):
        return 2
    configured = configure_trusted_workspace(Path("/opt/kast/state"), trust)
    if isinstance(configured, (Rejected, BundleRejected)):
        return 2
    print(f"kast-container: stage=compose outcome=INSTALLED platform={target.value}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
