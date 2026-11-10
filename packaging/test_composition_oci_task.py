"""Exercise task launch with a real process and a private Host profile."""

import json
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from xml.etree import ElementTree

import pytest

from test_composition_bundle import PluginTarget, Selection


@dataclass(frozen=True)
class Image:
    root: Path
    workspaces: Path
    settings: Path

    def run(
        self, workspace: Path, *command: str, options: tuple[str, ...] = ()
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                "-c",
                "import sys; from pathlib import Path; from oci_task import main; "
                "raise SystemExit(main(Path(sys.argv.pop(1)), Path(sys.argv.pop(1))))",
                str(self.root),
                str(self.workspaces),
                *options,
                str(workspace),
                *command,
            ],
            cwd=Path(__file__).parent,
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )


@pytest.fixture
def image(tmp_path: Path) -> Image:
    root = tmp_path / "runtime"
    home = root / "state/home"
    profile = home / "profile"
    plugins = profile / "plugins"
    plugins.mkdir(parents=True)
    selection = home / ".local/share/kast/installation/config/selected-ide.json"
    selection.parent.mkdir(parents=True)
    _ = selection.write_text(json.dumps(asdict(Selection(PluginTarget(str(plugins))))))
    _ = (root / ".kast-headless-owned").write_text("kast-managed-headless-v1\n")
    workspaces = tmp_path / "workspace"
    workspaces.mkdir()
    return Image(root, workspaces, profile / "options/trusted-paths.xml")


def test_task_selects_one_workspace_and_runs_the_command(image: Image) -> None:
    project = image.workspaces / "project with spaces"
    project.mkdir()
    result = image.run(
        project,
        sys.executable,
        "-c",
        "from pathlib import Path; import sys; "
        "assert Path(sys.argv[1]).is_file(); print(Path.cwd()); print(sys.argv[2]); sys.exit(17)",
        str(image.settings),
        "literal; *",
    )
    assert result.returncode == 17, result.stderr
    assert result.stdout.splitlines() == [str(project), "literal; *"]
    entries = ElementTree.parse(image.settings).findall("./component/option/map/entry")
    assert [entry.attrib for entry in entries] == [{"key": str(project), "value": "true"}]

    other = image.workspaces / "other"
    other.mkdir()
    before = image.settings.read_bytes()
    rejected = image.run(other, sys.executable, "-c", "print('unexpected command')")
    assert rejected.returncode == 2
    assert "INVALID_COMPONENT" in rejected.stderr
    assert rejected.stdout == ""
    assert image.settings.read_bytes() == before


def test_reuse_admits_the_same_workspace_without_replacing_its_grant(image: Image) -> None:
    project = image.workspaces / "project with spaces & symbols"
    project.mkdir()
    first = image.run(project, sys.executable, "-c", "print('first')", options=("--reuse",))
    assert first.returncode == 0, first.stderr
    assert first.stdout == "first\n"
    before = image.settings.read_bytes()
    identity = image.settings.stat()
    repeated = image.run(
        project,
        sys.executable,
        "-c",
        "import sys; print(sys.argv[1]); sys.exit(23)",
        "literal; $(false) *",
        options=("--reuse",),
    )
    assert repeated.returncode == 23, repeated.stderr
    assert repeated.stdout == "literal; $(false) *\n"
    assert image.settings.read_bytes() == before
    assert image.settings.stat().st_ino == identity.st_ino
    assert image.settings.stat().st_mtime_ns == identity.st_mtime_ns

    default = image.run(project, sys.executable, "-c", "print('unexpected child')")
    assert default.returncode == 2
    assert "INVALID_COMPONENT" in default.stderr
    assert default.stdout == ""
    assert image.settings.read_bytes() == before


@pytest.mark.parametrize(
    "grant", ["malformed", "foreign", "multiple", "false", "extra", "symlink", "oversized"]
)
def test_reuse_rejects_an_unproven_grant_without_starting_the_child(
    image: Image, grant: str
) -> None:
    project = image.workspaces / "project"
    project.mkdir()
    installed = image.run(project, sys.executable, "-c", "pass")
    assert installed.returncode == 0, installed.stderr
    tree = ElementTree.parse(image.settings)
    entries = tree.findall("./component/option/map/entry")
    assert len(entries) == 1
    if grant == "foreign":
        entries[0].set("key", str(image.workspaces / "other"))
    elif grant == "multiple":
        mapping = tree.find("./component/option/map")
        assert mapping is not None
        _ = ElementTree.SubElement(mapping, "entry", {"key": str(project), "value": "true"})
    elif grant == "false":
        entries[0].set("value", "false")
    elif grant == "extra":
        entries[0].set("foreign", "true")
    tree.write(image.settings, encoding="utf-8")
    if grant == "malformed":
        _ = image.settings.write_text("<application>")
    elif grant == "oversized":
        _ = image.settings.write_bytes(b" " * 65_537 + image.settings.read_bytes())
    elif grant == "symlink":
        outside = image.workspaces / "foreign-settings.xml"
        _ = image.settings.rename(outside)
        image.settings.symlink_to(outside)
    before = image.settings.read_bytes()

    rejected = image.run(
        project, sys.executable, "-c", "print('unexpected child')", options=("--reuse",)
    )
    assert rejected.returncode == 2
    assert ("SHARED_STATE" if grant == "symlink" else "INVALID_COMPONENT") in rejected.stderr
    assert rejected.stdout == ""
    assert image.settings.read_bytes() == before


@pytest.mark.parametrize("workspace", ["root", "outside", "symlink"])
def test_task_rejects_a_path_outside_the_selected_project(image: Image, workspace: str) -> None:
    outside = image.workspaces.parent / "outside"
    outside.mkdir()
    symlink = image.workspaces / "alias"
    symlink.symlink_to(outside, target_is_directory=True)
    path = {"root": image.workspaces, "outside": outside, "symlink": symlink}[workspace]
    rejected = image.run(path, sys.executable, "-c", "print('unexpected command')")
    assert rejected.returncode == 2
    assert "UNSAFE_PATH" in rejected.stderr
    assert rejected.stdout == ""
    assert not image.settings.exists()


def test_cloud_task_selects_explicit_runtime_and_workspace_parent(image: Image) -> None:
    parent = image.workspaces.parent / "cloud repos"
    project = parent / "project"
    project.mkdir(parents=True)
    # The public options must override the image defaults, including its runtime location.
    defaults = Image(image.root.parent / "absent-default", image.workspaces, image.settings)
    result = defaults.run(
        project,
        sys.executable,
        "-c",
        "from pathlib import Path; import sys; print(Path.cwd()); print(sys.argv[1]); sys.exit(19)",
        "literal; $(false) *",
        options=("--runtime-root", str(image.root), "--workspace-root", str(parent)),
    )
    assert result.returncode == 19, result.stderr
    assert result.stdout.splitlines() == [str(project), "literal; $(false) *"]
    entries = ElementTree.parse(image.settings).findall("./component/option/map/entry")
    assert [entry.attrib for entry in entries] == [{"key": str(project), "value": "true"}]


@pytest.mark.parametrize("selection", ["parent", "outside", "alias"])
def test_cloud_task_rejects_invalid_explicit_scope(image: Image, selection: str) -> None:
    parent = image.workspaces.parent / "cloud"
    parent.mkdir()
    project = parent / "project"
    project.mkdir()
    alias = image.workspaces.parent / "alias"
    alias.symlink_to(parent, target_is_directory=True)
    scope = alias if selection == "alias" else parent
    workspace = {"parent": parent, "outside": image.workspaces, "alias": alias / "project"}[
        selection
    ]
    result = image.run(
        workspace,
        sys.executable,
        "-c",
        "print('unexpected child')",
        options=("--workspace-root", str(scope)),
    )
    assert result.returncode == 2
    assert "UNSAFE_PATH" in result.stderr
    assert result.stdout == ""
    assert not image.settings.exists()
