"""Public discovery excludes private and ignored input before result capacity."""

from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath

import pytest

import navigation as nav


@dataclass(frozen=True)
class SurfaceInput:
    scope: str
    path: str
    kind: str


@dataclass(frozen=True)
class ManifestInput:
    surfaces: tuple[SurfaceInput, ...]
    schemaVersion: int = 1


@pytest.fixture
def repository(tmp_path: Path) -> Path:
    git = shutil.which("git")
    assert git is not None
    subprocess.run([git, "init", "--quiet", str(tmp_path)], check=True)
    (tmp_path / ".agents").mkdir()
    (tmp_path / ".agents/repository-surfaces.json").write_text(
        json.dumps(
            asdict(
                ManifestInput(
                    (
                        SurfaceInput("public", "README.md", "file"),
                        SurfaceInput("public", "docs/public", "tree"),
                        SurfaceInput("packaging", "packaging/internal", "tree"),
                        SurfaceInput("knowledge", "openwiki", "tree"),
                    )
                )
            )
        )
    )
    for path, content in (
        ("README.md", "public entry\n"),
        ("docs/public/guide.md", "é\n"),
        ("docs/public/.env", "never read"),
        ("docs/public/secrets.json", "never read"),
        ("docs/public/cache/ignored.md", "never read"),
        ("packaging/internal/mcp/private.py", "private source\n"),
        ("openwiki/overview.md", "native managed knowledge\n"),
        ("openwiki/.run.json", "native checkpoint"),
        ("unowned.md", "not declared"),
    ):
        target = tmp_path / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    (tmp_path / ".gitignore").write_text("docs/public/cache/\n")
    return tmp_path


def test_default_excludes_private_before_capacity(repository: Path) -> None:
    result = nav.navigate(repository, ["list", "--limit", "2"])
    assert asdict(result) == {
        "outcome": "listed",
        "paths": ("README.md", "docs/public/guide.md"),
        "truncated": False,
    }


def test_expansion_and_native_knowledge_are_explicit(repository: Path) -> None:
    result = nav.navigate(repository, ["discover", "--internal"])
    assert isinstance(result, nav.Listing)
    assert result.paths == (
        "README.md",
        "docs/public/guide.md",
        "openwiki/overview.md",
        "packaging/internal/mcp/private.py",
    )
    result = nav.navigate(repository, ["list", "--internal", "--scope", "knowledge"])
    assert isinstance(result, nav.Listing)
    assert result.paths == ("openwiki/overview.md",)
    denied = nav.navigate(repository, ["list", "--scope", "knowledge"])
    assert isinstance(denied, nav.Rejected)
    assert denied.reason is nav.Failure.ACCESS_DENIED


def test_read_carries_scope_and_exact_utf8_byte_limit(repository: Path) -> None:
    result = nav.navigate(repository, ["read", "docs/public/guide.md", "--max-bytes", "3"])
    assert asdict(result) == {
        "outcome": "read",
        "path": "docs/public/guide.md",
        "scope": "public",
        "content": "é\n",
        "bytes": 3,
    }
    result = nav.navigate(repository, ["read", "docs/public/guide.md", "--max-bytes", "2"])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.READ_LIMIT
    private = nav.navigate(repository, ["read", "packaging/internal/mcp/private.py", "--internal"])
    assert isinstance(private, nav.Document)
    assert private.scope is nav.Scope.PACKAGING


@pytest.mark.parametrize(
    "path",
    [
        "../README.md",
        "docs/public/../public/guide.md",
        "/etc/passwd",
        "./README.md",
        "docs//public/guide.md",
        "docs\\public\\guide.md",
        "docs/public/\ud800.md",
    ],
)
def test_noncanonical_paths_reject(repository: Path, path: str) -> None:
    result = nav.navigate(repository, ["read", path])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.UNSAFE_PATH


@pytest.mark.parametrize(
    "path",
    [
        "packaging/internal/mcp/private.py",
        "unowned.md",
        "docs/public/.env",
        "docs/public/secrets.json",
        "docs/public/cache/ignored.md",
        ".git/config",
    ],
)
def test_unadmitted_reads_never_open_content(
    repository: Path,
    path: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observed: list[PurePosixPath] = []
    original = nav.read_bytes

    def observe_read(root: Path, source: PurePosixPath, limit: int) -> bytes | nav.Failure:
        observed.append(source)
        return original(root, source, limit)

    monkeypatch.setattr(nav, "read_bytes", observe_read)
    result = nav.navigate(repository, ["read", path])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.ACCESS_DENIED
    assert all(source == PurePosixPath(".agents/repository-surfaces.json") for source in observed)


def test_symlink_file_and_directory_reject(repository: Path) -> None:
    (repository / "docs/public/link.md").symlink_to(repository / "README.md")
    (repository / "docs/public/linked").symlink_to(
        repository / "packaging/internal", target_is_directory=True
    )
    for arguments in (
        ["read", "docs/public/link.md"],
        ["read", "docs/public/linked/private.py"],
        ["list"],
    ):
        result = nav.navigate(repository, arguments)
        assert isinstance(result, nav.Rejected)
        assert result.reason is nav.Failure.SYMLINK


def test_private_tree_is_not_inspected_by_default(repository: Path) -> None:
    (repository / "packaging/internal").rename(repository / "private-source")
    (repository / "packaging/internal").symlink_to(
        repository / "private-source", target_is_directory=True
    )
    result = nav.navigate(repository, ["list", "--limit", "2"])
    assert isinstance(result, nav.Listing)
    assert not result.truncated
    result = nav.navigate(repository, ["list", "--internal"])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.SYMLINK


def test_declared_file_cannot_expand_to_a_directory(repository: Path) -> None:
    (repository / "README.md").unlink()
    (repository / "README.md").mkdir()
    (repository / "README.md/unexpected.md").write_text("not a declared surface")
    result = nav.navigate(repository, ["list"])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.NOT_FILE


def test_manifest_symlink_and_unknown_ignore_state_reject(repository: Path) -> None:
    manifest = repository / ".agents/repository-surfaces.json"
    manifest.rename(repository / "surfaces.json")
    manifest.symlink_to(repository / "surfaces.json")
    result = nav.navigate(repository, ["list"])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.SYMLINK
    manifest.unlink()
    (repository / "surfaces.json").rename(manifest)
    (repository / ".git").rename(repository / ".not-git")
    result = nav.navigate(repository, ["read", "README.md"])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.IGNORE_CHECK_FAILED


def test_ambient_git_identity_cannot_redirect_ignore_policy(
    repository: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GIT_DIR", str(repository / "absent-git-directory"))
    result = nav.navigate(repository, ["list"])
    assert isinstance(result, nav.Listing)
    assert result.paths == ("README.md", "docs/public/guide.md")


@pytest.mark.parametrize(
    "document",
    [
        '{"schemaVersion":1,"schemaVersion":1,"surfaces":[]}',
        '{"schemaVersion":true,"surfaces":[]}',
        '{"schemaVersion":1,"surfaces":[]}',
        '{"schemaVersion":1,"surfaces":[{"scope":"unknown","path":"README.md","kind":"file"}]}',
        '{"schemaVersion":1,"surfaces":[{"scope":"public","path":"../README.md","kind":"file"}]}',
        '{"schemaVersion":1,"surfaces":[{"scope":"public","path":".git","kind":"tree"}]}',
        '{"schemaVersion":1,"surfaces":[{"scope":"public","path":"README.md","kind":"file"},'
        '{"scope":"packaging","path":"README.md","kind":"file"}]}',
        '{"schemaVersion":1,"surfaces":[{"scope":"public","path":"docs/public","kind":"tree"},'
        '{"scope":"packaging","path":"docs/public/private","kind":"tree"}]}',
    ],
)
def test_malformed_manifest_rejects(repository: Path, document: str) -> None:
    (repository / ".agents/repository-surfaces.json").write_text(document)
    result = nav.navigate(repository, ["list"])
    assert isinstance(result, nav.Rejected)
    assert result.reason is nav.Failure.INVALID_MANIFEST


def test_list_prefix_limits_and_read_rejections(repository: Path) -> None:
    result = nav.navigate(repository, ["list", "docs/public", "--limit", "1"])
    assert isinstance(result, nav.Listing)
    assert result.paths == ("docs/public/guide.md",)
    assert not result.truncated
    result = nav.navigate(repository, ["list", "--limit", "1"])
    assert isinstance(result, nav.Listing)
    assert result.paths == ("README.md",)
    assert result.truncated
    for arguments, expected in (
        (["list", "--scope", "unknown"], nav.Failure.UNKNOWN_SCOPE),
        (["list", "--limit", "0"], nav.Failure.INVALID_ARGUMENTS),
        (["read", "packaging/internal/mcp/private.py", "--int"], nav.Failure.INVALID_ARGUMENTS),
        (["read"], nav.Failure.INVALID_ARGUMENTS),
        (["read", "docs/public"], nav.Failure.NOT_FILE),
        (["read", "docs/public/missing.md"], nav.Failure.NOT_FOUND),
        (["read", "docs/public/binary.md"], nav.Failure.NOT_TEXT),
    ):
        (repository / "docs/public/binary.md").write_bytes(b"\xff\x00")
        result = nav.navigate(repository, arguments)
        assert isinstance(result, nav.Rejected)
        assert result.reason is expected


def test_launcher_from_other_directory_is_read_only(tmp_path: Path) -> None:
    root = Path(__file__).resolve().parents[3]
    command = root / "bin/kast-repo"
    paths = [
        root / name
        for name in (
            ".agents/repository-surfaces.json",
            "README.md",
            "docs/internal/navigation.py",
            "bin/kast-repo",
        )
    ]
    before = {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in paths}
    cache = root / "docs/internal/__pycache__"
    before_cache = tuple(cache.glob("*"))
    for arguments, code, expected in (
        (
            ["list", "--limit", "1"],
            0,
            {"outcome": "listed", "paths": ["AGENTS.md"], "truncated": True},
        ),
        (
            ["read", "docs/internal/navigation.py"],
            1,
            {"outcome": "rejected", "reason": "ACCESS_DENIED"},
        ),
        (["read", "../README.md"], 1, {"outcome": "rejected", "reason": "UNSAFE_PATH"}),
    ):
        result = subprocess.run(
            [str(command), *arguments], cwd=tmp_path, text=True, capture_output=True, check=False
        )
        assert result.returncode == code, result.stderr
        assert json.loads(result.stdout) == expected
        assert not result.stderr
    assert before == {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in paths}
    assert tuple(cache.glob("*")) == before_cache
