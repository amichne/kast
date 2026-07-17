#!/usr/bin/env python3
import argparse
import os
import stat
import zipfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract regular-file ZIP content without traversal or links."
    )
    parser.add_argument("archive")
    parser.add_argument("output_directory")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    archive_path = Path(args.archive)
    output = Path(args.output_directory)
    output.mkdir(parents=True, exist_ok=True)
    resolved_output = output.resolve(strict=True)
    with zipfile.ZipFile(archive_path) as archive:
        for info in archive.infolist():
            destination = (output / info.filename).resolve()
            if destination != resolved_output and resolved_output not in destination.parents:
                raise SystemExit(f"unsafe zip member: {info.filename}")
            mode = info.external_attr >> 16
            member_type = stat.S_IFMT(mode)
            if not info.is_dir() and member_type not in (0, stat.S_IFREG):
                raise SystemExit(f"unsafe zip member type: {info.filename}")
        archive.extractall(output)
        for info in archive.infolist():
            if info.is_dir():
                continue
            mode = info.external_attr >> 16
            if mode:
                os.chmod(output / info.filename, stat.S_IMODE(mode))


if __name__ == "__main__":
    main()
