"""Independent declarations exercise namespace identity, exact closure, and rejected resources."""

import zipfile
from pathlib import Path

import pytest

from composition_graph import (
    GraphFailure,
    GraphRejected,
    ModuleClosure,
    ModuleId,
    parse_module,
    read_closure,
)


def test_closure_keeps_transitive_witnesses_and_ignores_unselected_invalid_modules(
    tmp_path: Path,
) -> None:
    repository = tmp_path / "repository.jar"
    with zipfile.ZipFile(repository, "w") as jar:
        jar.writestr(
            "root.xml",
            '<module name="root" namespace="jetbrains"><dependencies>'
            '<module name="leaf" namespace="$legacy_jps_module"/></dependencies>'
            '<resources><resource-root path="../lib/root.jar"/></resources></module>',
        )
        jar.writestr(
            "leaf_$legacy_jps_module.xml",
            '<module name="leaf" namespace="$legacy_jps_module"><dependencies>'
            '<module name="root"/></dependencies>'
            '<resources><resource-root path="../lib/leaf.jar"/></resources></module>',
        )
        jar.writestr("unrelated.xml", "deliberately malformed and outside the selected closure")
    result = read_closure(repository, (ModuleId("root"),))
    assert isinstance(result, ModuleClosure)
    assert [m.module.identity for m in result.members] == [
        ModuleId("leaf", "$legacy_jps_module"),
        ModuleId("root"),
    ]
    assert result.members[0].predecessor == ModuleId("root")
    assert result.members[1].predecessor is None
    assert [r.path for r in result.resources] == ["lib/leaf.jar", "lib/root.jar"]


@pytest.mark.parametrize(
    "path",
    [
        "../../outside.jar",
        "/absolute.jar",
        "../lib/../outside.jar",
        "../lib//alias.jar",
        "../lib/./alias.jar",
        "../lib\\outside.jar",
        "",
        "../lib",
    ],
)
def test_resource_ingress_rejects_escape_and_aliases(path: str) -> None:
    data = (
        f'<module name="root"><resources><resource-root path="{path}"/></resources></module>'
    ).encode()
    assert parse_module(data, ModuleId("root")) == GraphRejected(GraphFailure.UNSAFE_RESOURCE, path)


def test_identity_cannot_change_while_a_descriptor_is_loaded() -> None:
    assert parse_module(b'<module name="different"/>', ModuleId("root")) == GraphRejected(
        GraphFailure.IDENTITY_MISMATCH, "root.xml"
    )


def test_missing_dependency_keeps_its_exact_identity(tmp_path: Path) -> None:
    repository = tmp_path / "repository.jar"
    with zipfile.ZipFile(repository, "w") as jar:
        jar.writestr(
            "root.xml",
            '<module name="root"><dependencies>'
            '<module name="absent" namespace="$legacy_jps_module"/></dependencies></module>',
        )
    assert read_closure(repository, (ModuleId("root"),)) == GraphRejected(
        GraphFailure.UNKNOWN_MODULE, "absent_$legacy_jps_module.xml"
    )
