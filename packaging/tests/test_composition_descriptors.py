"""Real repository declarations separate archive support from semantic registrations."""

import zipfile
from dataclasses import replace
from pathlib import Path

from composition_descriptors import (
    DescriptorFailure,
    DescriptorRejected,
    DescriptorSelection,
    Provider,
    parse_xml,
    resolve_module,
    select_descriptors,
)
from composition_graph import ModuleId


def provider(descriptor: bytes) -> Provider:
    return Provider(
        "p",
        "p",
        b'<plugin id="p"><plugin-descriptor-module name="root"/>'
        b'<module name="packed" loading="embedded"/></plugin>',
        descriptor,
        ("lib/root.jar",),
    )


def test_selected_module_registers_required_dependency_and_omits_unselected_content(
    tmp_path: Path,
) -> None:
    repository = tmp_path / "modules.jar"
    with zipfile.ZipFile(repository, "w") as archive:
        for name in ("root", "leaf", "other", "packed"):
            archive.writestr(name + ".xml", '<module name="' + name + '"/>')
        archive.writestr("unselected.xml", "malformed unused descriptor")
    available = frozenset(ModuleId(name) for name in ("root", "leaf", "other", "packed"))
    descriptor = (
        b'<idea-plugin><id>p</id><content namespace="jetbrains"><module name="root"><![CDATA['
        b'<idea-plugin><dependencies><module name="leaf"/></dependencies></idea-plugin>'
        b']]></module><module name="leaf"/><module name="other"/></content></idea-plugin>'
    )
    selected = select_descriptors(
        repository,
        available,
        (provider(descriptor),),
        (ModuleId("root"),),
        ("p",),
        b"<idea-plugin><id>com.intellij</id></idea-plugin>",
    )
    assert isinstance(selected, DescriptorSelection)
    assert {member.module.identity for member in selected.closure.members} == {
        ModuleId("root"),
        ModuleId("leaf"),
    }
    assert selected.support == (ModuleId("packed"),)
    assert selected.omitted == (ModuleId("other"),)
    output = selected.descriptors[0].descriptor
    assert b"<![CDATA[" in output
    parsed = parse_xml(output, "output")
    assert not isinstance(parsed, DescriptorRejected)
    content = parsed.find("content")
    assert content is not None and content.get("namespace") == "jetbrains"
    assert [node.get("name") for node in parsed.findall("./content/module")] == ["root", "leaf"]


def test_unknown_required_plugin_fails_before_a_descriptor_is_emitted(tmp_path: Path) -> None:
    repository = tmp_path / "modules.jar"
    with zipfile.ZipFile(repository, "w") as archive:
        archive.writestr("root.xml", '<module name="root"/>')
    selected = select_descriptors(
        repository,
        frozenset({ModuleId("root")}),
        (provider(b"<idea-plugin><id>p</id><depends>missing</depends></idea-plugin>"),),
        (ModuleId("root"),),
        ("p",),
        b"<idea-plugin/>",
    )
    assert selected == DescriptorRejected(DescriptorFailure.UNKNOWN_PROVIDER, "missing")


def test_ambiguous_namespace_never_selects_the_first_name_match() -> None:
    assert resolve_module(
        ModuleId("same"),
        frozenset(
            {
                ModuleId("same", "one"),
                ModuleId("same", "two"),
            }
        ),
    ) == DescriptorRejected(DescriptorFailure.AMBIGUOUS_MODULE, "same.xml")


def test_exact_namespace_beats_other_same_name_providers() -> None:
    identity = ModuleId("same", "one")
    assert resolve_module(identity, frozenset({identity, ModuleId("same", "two")})) == identity


def test_provider_header_cannot_change_the_admitted_plugin_identity(tmp_path: Path) -> None:
    repository = tmp_path / "modules.jar"
    with zipfile.ZipFile(repository, "w") as archive:
        archive.writestr("root.xml", '<module name="root"/>')
    foreign = replace(
        provider(b"<idea-plugin><id>p</id></idea-plugin>"),
        header=b'<plugin id="foreign"><plugin-descriptor-module name="root"/></plugin>',
    )
    assert select_descriptors(
        repository,
        frozenset({ModuleId("root")}),
        (foreign,),
        (ModuleId("root"),),
        ("p",),
        b"<idea-plugin/>",
    ) == DescriptorRejected(DescriptorFailure.INVALID_XML, "p")
