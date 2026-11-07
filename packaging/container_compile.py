"""Compile Control with the pinned GraalVM for the container's native CPU."""

from __future__ import annotations

import hashlib
import platform
import subprocess
import tarfile
from pathlib import Path
from urllib.request import urlopen

GRAAL = {
    "aarch64": (
        "aarch64",
        "e0e18106fa1d8628d8ba21f548865211d7c8608a3423f7b25cb2aa4eef9abf10",
    ),
    "x86_64": (
        "x64",
        "af545b1bc12ecd24a5ba95e2370da2163e87c4cf13991a482d5b176bf4165c12",
    ),
}


def main() -> int:
    selected = GRAAL.get(platform.machine())
    if selected is None:
        print("kast-container: stage=compile outcome=UNSUPPORTED_ARCHITECTURE", flush=True)
        return 2
    arch, expected = selected
    archive = Path("/tmp/graal.tar.gz")
    url = (
        f"https://download.oracle.com/graalvm/25/archive/graalvm-jdk-25.0.2_linux-{arch}_bin.tar.gz"
    )
    with urlopen(url, timeout=120) as response, archive.open("wb") as output:
        while data := response.read(1024 * 1024):
            _ = output.write(data)
    with archive.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected:
        print("kast-container: stage=compile outcome=GRAAL_DIGEST_MISMATCH", flush=True)
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
