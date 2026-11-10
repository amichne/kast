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
