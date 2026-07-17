#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import stat
import subprocess
import sys
from pathlib import Path


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def git_bytes(root: Path, *args: str) -> bytes:
    completed = subprocess.run(
        ["git", "-C", str(root), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        fail(
            f"git {args!r} failed for {root}: "
            f"{completed.stderr.decode(errors='replace').strip()}"
        )
    return completed.stdout


def git_text(root: Path, *args: str) -> str:
    try:
        return git_bytes(root, *args).decode("utf-8").strip()
    except UnicodeDecodeError as error:
        fail(f"git {args!r} returned non-UTF-8 text: {error}")


def framed(digest: "hashlib._Hash", value: bytes) -> None:
    digest.update(len(value).to_bytes(8, "big"))
    digest.update(value)


def listed_paths(raw: bytes) -> list[bytes]:
    return [entry for entry in raw.split(b"\0") if entry]


def require_safe_relative(raw: bytes) -> None:
    if not raw or raw.startswith(b"/") or b"\0" in raw:
        fail(f"git returned an unsafe source path: {os.fsdecode(raw)}")
    parts = raw.split(b"/")
    if any(part in (b"", b".", b"..") for part in parts):
        fail(f"git returned an unsafe source path: {os.fsdecode(raw)}")


def source_entry_digest(root: Path, relative: bytes, tracked: bool) -> bytes:
    path = os.path.join(os.fsencode(root), relative)
    digest = hashlib.sha256()
    try:
        metadata = os.lstat(path)
    except FileNotFoundError:
        if tracked:
            digest.update(b"deleted\0")
            return digest.digest()
        raise
    if stat.S_ISLNK(metadata.st_mode):
        digest.update(b"symlink\0")
        framed(digest, os.fsencode(os.readlink(path)))
        return digest.digest()
    if stat.S_ISREG(metadata.st_mode):
        digest.update(b"file\0")
        digest.update(bytes([1 if metadata.st_mode & 0o111 else 0]))
        digest.update(metadata.st_size.to_bytes(8, "big"))
        observed = 0
        with open(path, "rb") as handle:
            while chunk := handle.read(64 * 1024):
                digest.update(chunk)
                observed += len(chunk)
        if observed != metadata.st_size:
            fail(f"source file length changed while hashing {os.fsdecode(path)}")
        return digest.digest()
    if stat.S_ISDIR(metadata.st_mode):
        digest.update(b"gitlink\0")
        nested = Path(os.fsdecode(path))
        framed(digest, git_text(nested, "rev-parse", "--verify", "HEAD").encode())
        framed(
            digest,
            git_bytes(
                nested,
                "status",
                "--porcelain=v2",
                "-z",
                "--untracked-files=all",
            ),
        )
        return digest.digest()
    fail(f"source snapshot refuses unsupported entry {os.fsdecode(path)}")


def source_tree_digest(root: Path) -> str:
    tracked = listed_paths(git_bytes(root, "ls-files", "-z", "--cached"))
    tracked_set = set(tracked)
    untracked = listed_paths(
        git_bytes(root, "ls-files", "-z", "--others", "--exclude-standard")
    )
    paths = sorted(set(tracked + untracked))
    digest = hashlib.sha256()
    digest.update(b"kast-local-source-snapshot-v2\0")
    digest.update(len(paths).to_bytes(8, "big"))
    for relative in paths:
        require_safe_relative(relative)
        framed(digest, relative)
        digest.update(source_entry_digest(root, relative, relative in tracked_set))
    return digest.hexdigest()


def resolved_git_directory(root: Path, raw: str) -> Path:
    candidate = Path(raw)
    if not candidate.is_absolute():
        candidate = root / candidate
    return candidate.resolve(strict=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Capture Kast local source identity without a build toolchain."
    )
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--output-file", required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    requested = Path(args.source_root).resolve(strict=True)
    repository = Path(git_text(requested, "rev-parse", "--show-toplevel")).resolve(
        strict=True
    )
    git_directory = resolved_git_directory(
        repository, git_text(repository, "rev-parse", "--git-dir")
    )
    common_directory = resolved_git_directory(
        repository, git_text(repository, "rev-parse", "--git-common-dir")
    )
    payload = {
        "canonicalRoot": str(repository),
        "worktreeKind": "primary" if git_directory == common_directory else "linked",
        "gitCommit": git_text(repository, "rev-parse", "--verify", "HEAD").lower(),
        "sourceTreeSha256": source_tree_digest(repository),
    }
    output = Path(args.output_file)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f"{output.name}.tmp-{os.getpid()}")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, output)
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
