"""Combine native plugin registrations with the exact compiled module graph."""

from __future__ import annotations

import copy
import zipfile
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, tostring
from xml.sax.saxutils import escape

from defusedxml import ElementTree as XML
from defusedxml.common import DefusedXmlException

from composition_graph import GraphRejected, ModuleClosure, ModuleId, module_id, read_closure
from composition_index import PluginClasspath


class DescriptorFailure(StrEnum):
    INVALID_XML = "INVALID_XML"
    UNKNOWN_PROVIDER = "UNKNOWN_PROVIDER"
    AMBIGUOUS_MODULE = "AMBIGUOUS_MODULE"
    UNKNOWN_MODULE = "UNKNOWN_MODULE"
    UNREVIEWED_INCLUDE = "UNREVIEWED_INCLUDE"
    INVALID_REPOSITORY = "INVALID_REPOSITORY"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"


@dataclass(frozen=True, slots=True)
class DescriptorRejected:
    reason: DescriptorFailure
    subject: str


@dataclass(frozen=True, slots=True)
class Provider:
    identity: str
    directory: str
    header: bytes
    descriptor: bytes
    files: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class Registration:
    module: ModuleId
    owner: str
    namespace: str | None
    node: Element
    body: Element | None


@dataclass(frozen=True, slots=True)
class DescriptorSelection:
    closure: ModuleClosure
    plugins: tuple[str, ...]
    descriptors: tuple[PluginClasspath, ...]
    core: bytes
    support: tuple[ModuleId, ...]
    omitted: tuple[ModuleId, ...]


def parse_xml(data: bytes, subject: str) -> Element | DescriptorRejected:
    if not 0 < len(data) <= 4_194_304:
        return DescriptorRejected(DescriptorFailure.LIMIT_EXCEEDED, subject)
    try:
        return XML.fromstring(data)
    except (XML.ParseError, DefusedXmlException):
        return DescriptorRejected(DescriptorFailure.INVALID_XML, subject)


def xml_bytes(root: Element) -> bytes:
    """The native loader expects embedded content descriptors in CDATA sections."""
    result = tostring(root, encoding="unicode")
    for module in root.findall("./content/module"):
        if module.text and module.text.strip():
            if "]]>" in module.text:
                raise ValueError("Embedded descriptors cannot contain a CDATA terminator")
            result = result.replace(escape(module.text), "<![CDATA[" + module.text + "]]>")
    return result.encode()


def resolve_module(
    identity: ModuleId, available: frozenset[ModuleId]
) -> ModuleId | DescriptorRejected:
    if identity in available:
        return identity
    # This native plugin dependency names the Java provider in an implicit namespace.
    if identity == ModuleId("intellij.libraries.test.discovery"):
        java = ModuleId(identity.name, "com.intellij.java_$implicit")
        if java in available:
            return java
    matches = tuple(item for item in available if item.name == identity.name)
    if len(matches) != 1:
        reason = (
            DescriptorFailure.UNKNOWN_MODULE if not matches else DescriptorFailure.AMBIGUOUS_MODULE
        )
        return DescriptorRejected(reason, identity.descriptor)
    return matches[0]


def _identity(node: Element, namespace: str = "jetbrains") -> ModuleId | DescriptorRejected:
    value = module_id(node.get("name", ""), node.get("namespace", namespace))
    if isinstance(value, GraphRejected):
        return DescriptorRejected(DescriptorFailure.INVALID_XML, value.subject)
    return value


def _registrations(
    providers: tuple[Provider, ...], available: frozenset[ModuleId]
) -> tuple[Registration, ...] | DescriptorRejected:
    result: list[Registration] = []
    for provider in providers:
        root = parse_xml(provider.descriptor, provider.identity)
        if isinstance(root, DescriptorRejected):
            return root
        if root.tag != "idea-plugin" or root.findtext("id") != provider.identity:
            return DescriptorRejected(DescriptorFailure.INVALID_XML, provider.identity)
        for section in root.findall("content"):
            for child in section.findall("module"):
                identity = _identity(child, section.get("namespace", "jetbrains"))
                if isinstance(identity, DescriptorRejected):
                    return identity
                resolved = resolve_module(identity, available)
                if isinstance(resolved, DescriptorRejected):
                    # Unselected optional registrations can name modules outside this distribution.
                    if child.get("loading") not in {"embedded", "required"}:
                        continue
                    return resolved
                body = parse_xml(child.text.encode(), identity.descriptor) if child.text else None
                if isinstance(body, DescriptorRejected):
                    return body
                node = copy.deepcopy(child)
                result.append(
                    Registration(
                        resolved,
                        provider.identity,
                        section.get("namespace"),
                        node,
                        body,
                    )
                )
    identities = [registration.module for registration in result]
    if len(set(identities)) != len(identities):
        return DescriptorRejected(DescriptorFailure.AMBIGUOUS_MODULE, "content providers")
    return tuple(result)


def _append_content(root: Element, registrations: tuple[Registration, ...]) -> None:
    sections: dict[str | None, Element] = {}
    for item in registrations:
        section = sections.get(item.namespace)
        if section is None:
            attributes = {} if item.namespace is None else {"namespace": item.namespace}
            section = SubElement(root, "content", attributes)
            sections[item.namespace] = section
        section.append(copy.deepcopy(item.node))


def _dependencies(
    root: Element, available: frozenset[ModuleId], aliases: dict[str, str | ModuleId]
) -> tuple[tuple[ModuleId, ...], tuple[str, ...]] | DescriptorRejected:
    modules: list[ModuleId] = []
    plugins: list[str] = []
    for node in root.findall("./dependencies/module"):
        identity = _identity(node)
        if isinstance(identity, DescriptorRejected):
            return identity
        resolved = resolve_module(identity, available)
        if isinstance(resolved, DescriptorRejected):
            return resolved
        modules.append(resolved)
    names = [node.get("id", "") for node in root.findall("./dependencies/plugin")]
    names.extend(
        (node.text or "").strip()
        for node in root.findall("depends")
        if node.get("optional") != "true"
    )
    for name in names:
        provider = aliases.get(name)
        if provider is None:
            return DescriptorRejected(DescriptorFailure.UNKNOWN_PROVIDER, name)
        if isinstance(provider, ModuleId):
            modules.append(provider)
        else:
            plugins.append(provider)
    return tuple(modules), tuple(plugins)


def select_descriptors(
    repository: Path,
    available: frozenset[ModuleId],
    providers: tuple[Provider, ...],
    roots: tuple[ModuleId, ...],
    plugin_roots: tuple[str, ...],
    core_base: bytes,
) -> DescriptorSelection | DescriptorRejected | GraphRejected:
    registrations = _registrations(providers, available)
    if isinstance(registrations, DescriptorRejected):
        return registrations
    by_module = {registration.module: registration for registration in registrations}
    by_plugin = {provider.identity: provider for provider in providers}
    if len(by_plugin) != len(providers):
        return DescriptorRejected(DescriptorFailure.UNKNOWN_PROVIDER, "duplicate provider")
    aliases: dict[str, str | ModuleId] = {name: name for name in by_plugin}
    aliases.update(
        {
            name: "com.intellij"
            for name in (
                "com.intellij.modules.platform",
                "com.intellij.modules.lang",
                "com.intellij.modules.externalSystem",
                "com.intellij.modules.xdebugger",
                "com.intellij.modules.java-capable",
                "com.intellij.modules.xml",
            )
        }
    )
    parsed: dict[str, Element] = {}
    headers: dict[str, Element] = {}
    core = parse_xml(core_base, "core base")
    if isinstance(core, DescriptorRejected):
        return core
    for node in core.findall("module"):
        aliases[node.get("value", "")] = "com.intellij"
    for provider in providers:
        header = parse_xml(provider.header, provider.identity)
        if isinstance(header, DescriptorRejected):
            return header
        if header.tag != "plugin" or header.get("id") != provider.identity:
            return DescriptorRejected(DescriptorFailure.INVALID_XML, provider.identity)
        headers[provider.identity] = header
        root = parse_xml(provider.descriptor, provider.identity)
        if isinstance(root, DescriptorRejected):
            return root
        parsed[provider.identity] = root
        if provider.identity != "com.intellij":
            for node in root.findall("module"):
                aliases[node.get("value", "")] = provider.identity
    for registration in registrations:
        if registration.body is not None:
            for node in registration.body.findall("module"):
                aliases[node.get("value", "")] = registration.module
    selected_roots = set(roots)
    selected_plugins = set(plugin_roots)
    processed: set[ModuleId] = set()
    processed_plugins: set[str] = set()
    # Each iteration consumes a new module or provider. The repository bounds both sets.
    for _ in range(4096):
        for identity in sorted(selected_plugins - processed_plugins):
            provider = by_plugin.get(identity)
            if provider is None:
                return DescriptorRejected(DescriptorFailure.UNKNOWN_PROVIDER, identity)
            processed_plugins.add(identity)
            if identity == "com.intellij":
                continue
            header = headers[identity]
            descriptor = header.find("plugin-descriptor-module")
            if descriptor is None:
                return DescriptorRejected(DescriptorFailure.INVALID_XML, identity)
            physical = _identity(descriptor)
            if isinstance(physical, DescriptorRejected):
                return physical
            selected_roots.add(physical)
            dependencies = _dependencies(parsed[identity], available, aliases)
            if isinstance(dependencies, DescriptorRejected):
                return dependencies
            selected_roots.update(dependencies[0])
            selected_plugins.update(dependencies[1])
            selected_roots.update(
                item.module
                for item in registrations
                if item.owner == identity and item.node.get("loading") in {"embedded", "required"}
            )
        closure = read_closure(repository, tuple(sorted(selected_roots)))
        if isinstance(closure, GraphRejected):
            return closure
        identities = {member.module.identity for member in closure.members}
        for identity in sorted(identities - processed):
            processed.add(identity)
            registration = by_module.get(identity)
            if registration is None:
                continue
            selected_plugins.add(registration.owner)
            if registration.body is not None:
                dependencies = _dependencies(registration.body, available, aliases)
                if isinstance(dependencies, DescriptorRejected):
                    return dependencies
                selected_roots.update(dependencies[0])
                selected_plugins.update(dependencies[1])
        if selected_roots <= identities and selected_plugins <= processed_plugins:
            break
    else:
        return DescriptorRejected(DescriptorFailure.LIMIT_EXCEEDED, "closure")
    for name in ("com.intellij.modules.java-capable", "com.intellij.modules.xml"):
        _ = SubElement(core, "module", {"value": name})
    _append_content(
        core,
        tuple(
            item
            for item in registrations
            if item.owner == "com.intellij" and item.module in identities
        ),
    )
    descriptors: list[PluginClasspath] = []
    support: set[ModuleId] = set()
    for identity in sorted(selected_plugins - {"com.intellij"}):
        provider = by_plugin[identity]
        root = copy.deepcopy(parsed[identity])
        for section in root.findall("content"):
            root.remove(section)
        _append_content(
            root,
            tuple(
                item
                for item in registrations
                if item.owner == identity and item.module in identities
            ),
        )
        if identity == "org.jetbrains.kotlin":
            includes = root.findall("{http://www.w3.org/2001/XInclude}include")
            if {node.get("href") for node in includes} != {
                "ultimate-plugin.k2.xml",
                "command-completion-k2.xml",
            }:
                return DescriptorRejected(DescriptorFailure.UNREVIEWED_INCLUDE, identity)
            for node in includes:
                root.remove(node)
        header = headers[identity]
        for node in header.findall("module"):
            if node.get("loading") == "embedded":
                physical = _identity(node)
                if isinstance(physical, DescriptorRejected):
                    return physical
                support.add(physical)
        descriptors.append(PluginClasspath(provider.directory, xml_bytes(root), provider.files))
    return DescriptorSelection(
        closure,
        tuple(sorted(selected_plugins)),
        tuple(descriptors),
        xml_bytes(core),
        tuple(sorted(support)),
        tuple(sorted(set(by_module) - identities)),
    )


def repository_identities(repository: Path) -> frozenset[ModuleId] | DescriptorRejected:
    """Catalog names only. The graph reader parses only selected compiled descriptors."""
    try:
        with zipfile.ZipFile(repository) as archive:
            names = archive.namelist()
        result: set[ModuleId] = set()
        for name in names:
            if "/" in name or not name.endswith(".xml"):
                continue
            stem = name[:-4]
            module, separator, namespace = stem.partition("_")
            identity = module_id(module, namespace if separator else "jetbrains")
            if isinstance(identity, GraphRejected):
                return DescriptorRejected(DescriptorFailure.INVALID_REPOSITORY, name)
            result.add(identity)
        return frozenset(result)
    except (OSError, zipfile.BadZipFile):
        return DescriptorRejected(DescriptorFailure.INVALID_REPOSITORY, str(repository))
