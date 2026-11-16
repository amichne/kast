"""Producer-only MCP wheel materialization; never shipped as a setup solver."""

from __future__ import annotations

import argparse
import os
import stat
import sys
import tempfile
from pathlib import Path

import mcp_environment as mcp

HERE = Path(__file__).resolve().parent
PACKAGING = HERE.parent
SOURCES = PACKAGING.parent / "agent-tools/skills/kast/scripts"


def source_export() -> mcp.RequirementsLock | mcp.Rejected:
    export = mcp.RequirementsLock(
        mcp.digest(SOURCES / "server.py"),
        mcp.digest(SOURCES / "server.py.lock"),
        mcp.digest(PACKAGING / "mcp-requirements.txt"),
    )
    if mcp.record_bytes(PACKAGING / "mcp-requirements.txt.lock") != mcp.encoded(export):
        return mcp.Rejected(mcp.Failure.INSTALLATION)
    return export


def produce(
    metadata: Path,
    target: mcp.DependencyTarget,
    *,
    uv: Path,
    runner: mcp.Runner = mcp.run,
) -> mcp.DependencyArtifact | mcp.Rejected:
    """Publish metadata/mcp-dependencies, refusing any existing destination.

    metadata must be an existing owned absolute share/kast staging directory.
    uv is an explicit absolute producer executable. The runner owns only process
    observations; this function owns export admission, inventory and publication.
    No interpreter or dependency is executed from a cross-platform artifact.
    """
    try:
        if not mcp.owned(metadata, directory=True) or not uv.is_absolute():
            return mcp.Rejected(mcp.Failure.INSTALLATION)
        destination = metadata / mcp.DEPENDENCIES.name
        if os.path.lexists(destination):
            return mcp.Rejected(mcp.Failure.CONCURRENT)
        export = source_export()
        if isinstance(export, mcp.Rejected):
            return export
        identity = mcp.Identity.observe(metadata)
        with tempfile.TemporaryDirectory(prefix=".mcp-artifact-", dir=metadata) as temporary:
            work = Path(temporary)
            stage = work / "artifact"
            stage.mkdir(mode=0o700)
            site = stage / "site-packages"
            result = runner(
                mcp.Command(
                    (
                        str(uv),
                        "--no-config",
                        "--no-cache",
                        "pip",
                        "install",
                        "--python-version",
                        "3.12.12",
                        "--python-platform",
                        target.triple,
                        "--require-hashes",
                        "--no-deps",
                        "--only-binary",
                        ":all:",
                        "--link-mode",
                        "copy",
                        "--index-url",
                        "https://pypi.org/simple",
                        "--target",
                        str(site),
                        "--requirements",
                        str(PACKAGING / "mcp-requirements.txt"),
                        "--python",
                        sys.executable,
                    ),
                    {
                        "PATH": "",
                        "HOME": str(work),
                        "TMPDIR": str(work),
                        "UV_PYTHON_DOWNLOADS": "never",
                        "UV_NO_PROGRESS": "1",
                        "PYTHONDONTWRITEBYTECODE": "1",
                    },
                )
            )
            if isinstance(result, mcp.Rejected):
                return result
            if result.code != 0:
                return mcp.Rejected(mcp.Failure.INSTALL)
            # uv's transient target lock is not a distribution member. It is
            # created world-writable; never ship it or weaken file admission.
            lock = site / ".lock"
            if os.path.lexists(lock):
                observed_lock = lock.lstat()
                if (
                    not stat.S_ISREG(observed_lock.st_mode)
                    or observed_lock.st_uid != os.getuid()
                    or observed_lock.st_nlink != 1
                    or observed_lock.st_size != 0
                ):
                    return mcp.Rejected(mcp.Failure.STATE)
                lock.unlink()
            observed = mcp.inventory(site)
            if isinstance(observed, mcp.Rejected) or not observed.files:
                return mcp.Rejected(mcp.Failure.STATE)
            # Control archives ship dependency imports as data, not commands.
            # Record their delivered modes rather than uv's executable wheel modes.
            for entry in observed.files:
                (site / entry.path).chmod(0o644)
            observed = mcp.inventory(site)
            if isinstance(observed, mcp.Rejected):
                return observed
            artifact = mcp.DependencyArtifact(target, export, observed)
            _ = (stage / "artifact.json").write_bytes(mcp.encoded(artifact))
            if source_export() != export or mcp.Identity.observe(metadata) != identity:
                return mcp.Rejected(mcp.Failure.CONCURRENT)
            published = mcp.publish(stage, destination)
            return published if isinstance(published, mcp.Rejected) else artifact
    except OSError:
        return mcp.Rejected(mcp.Failure.FILESYSTEM)


def main(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--metadata", type=Path, required=True)
    _ = parser.add_argument(
        "--target", type=mcp.DependencyTarget, choices=mcp.DependencyTarget, required=True
    )
    _ = parser.add_argument("--uv", type=Path, required=True)
    args = parser.parse_args(arguments)
    result = produce(args.metadata, args.target, uv=args.uv)
    print(mcp.encoded(result).decode(), end="")
    return 1 if isinstance(result, mcp.Rejected) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
