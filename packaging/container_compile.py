"""Compile Control with the pinned Liberica NIK for the container's native CPU."""

from __future__ import annotations

import hashlib
import platform
import subprocess
import tarfile
from pathlib import Path
from urllib.request import urlopen

NIK = {
    "aarch64": (
        "aarch64",
        "0b022d9fd134677a1cacccb2e26fcd3353f08876232a327148c67c3de096f5c3",
    ),
    "x86_64": (
        "amd64",
        "fe9d71a107e523a19ec2eddf77618cca7c98fe6fdfa609f4dc93df7d798429d0",
    ),
}


def archive_url(arch: str) -> str:
    return (
        "https://github.com/bell-sw/LibericaNIK/releases/download/25.0.2%2B1-25.0.2%2B13/"
        f"bellsoft-liberica-vm-openjdk25.0.2%2B13-25.0.2%2B1-linux-{arch}.tar.gz"
    )


def main() -> int:
    selected = NIK.get(platform.machine())
    if selected is None:
        print("kast-container: stage=compile outcome=UNSUPPORTED_ARCHITECTURE", flush=True)
        return 2
    arch, expected = selected
    archive = Path("/tmp/graal.tar.gz")
    url = archive_url(arch)
    with urlopen(url, timeout=120) as response, archive.open("wb") as output:
        while data := response.read(1024 * 1024):
            _ = output.write(data)
    with archive.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected:
        print("kast-container: stage=compile outcome=NIK_DIGEST_MISMATCH", flush=True)
        return 2
    with tarfile.open(archive) as source:
        source.extractall("/tmp/graal", filter="data")
    homes = tuple(Path("/tmp/graal").iterdir())
    if len(homes) != 1:
        return 2
    root = Path("/inputs")
    root.mkdir()
    with tarfile.open("/native-inputs.tar.gz") as source:
        source.extractall(root, filter="data")
    metadata = sorted(
        {str(path.parent) for path in (root / "reachability-metadata").rglob("*.json")}
    )
    Path("/out").mkdir()
    print(f"kast-container: stage=compile outcome=STARTED arch={arch}", flush=True)
    result = subprocess.run(
        [
            str(homes[0] / "bin/native-image"),
            "@native-image.args",
            "--parallelism=4",
            "-J-Xmx7g",
            "-cp",
            "lib/*",
            "io.github.amichne.kast.cli.KastNativeMain",
            "-o",
            "/out/kast",
            "-H:+UnlockExperimentalVMOptions",
            "-H:ConfigurationFileDirectories=" + ",".join(metadata),
            "-H:-UnlockExperimentalVMOptions",
        ],
        cwd=root,
        check=False,
    )
    print(f"kast-container: stage=compile outcome=EXITED code={result.returncode}", flush=True)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
