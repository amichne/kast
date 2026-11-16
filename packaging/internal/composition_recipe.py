"""Pinned production analysis roots and reviewed plugin provider containers."""

import zipfile
from pathlib import Path

from composition_descriptors import (
    DescriptorFailure,
    DescriptorRejected,
    DescriptorSelection,
    Provider,
    repository_identities,
    select_descriptors,
)
from composition_graph import GraphRejected, ModuleId, ModuleResource, parse_module
from composition_index import IndexRejected, parse_plugin_index
from composition_platform import HostPlatform

BUILD = "262.10315.125"
ARCHIVE_SHA256 = HostPlatform.MACOS_ARM64.layout.archive_sha256

# Roots name semantic, project, index, and backend service owners. Dependencies stay source-owned.
ROOTS = tuple(
    ModuleId(name)
    for name in (
        "intellij.platform.bootstrap",
        "intellij.platform.lang.impl",
        "intellij.platform.externalSystem.impl",
        "intellij.platform.ide.core.plugins",
        "intellij.kotlin.analysis.platform",
        "intellij.kotlin.projectStructure",
        "intellij.kotlin.searching",
        "intellij.kotlin.gradle.tooling",
        "intellij.kotlin.gradle.gradle",
        "kotlin.gradle.gradle-java",
        "intellij.platform.backend",
        "intellij.platform.monolith",
        "intellij.platform.settings.local",
        "intellij.platform.kernel.impl",
        "intellij.platform.backend.workspace.impl",
        "intellij.platform.kernel.backend",
        "intellij.platform.project.backend",
        "intellij.platform.progress.backend",
        "intellij.platform.lang.impl.backend",
        "intellij.platform.editor.backend",
        "intellij.kotlin.codeInsight.base",
    )
) + (
    ModuleId("intellij.platform.indexing.impl", "$legacy_jps_module"),
    ModuleId("intellij.platform.starter", "$legacy_jps_module"),
)

# These are provider candidates, not unconditional roots. The descriptor closure selects them.
PROVIDERS = (
    ("com.intellij", "", "intellij.idea.ultimate.customization"),
    ("com.intellij.gradle", "gradle-plugin", "intellij.gradle.plugin"),
    ("com.intellij.java", "java", "intellij.java.plugin"),
    ("com.intellij.properties", "properties", "intellij.properties"),
    (
        "intellij.java.aetherDependencyResolver.plugin",
        "java-aetherDependencyResolver-plugin",
        "intellij.java.aetherDependencyResolver.plugin",
    ),
    ("intellij.libraries.misc.plugin", "libraries-misc-plugin", "intellij.libraries.misc.plugin"),
    (
        "intellij.structuralSearch.plugin",
        "platform-structuralSearch-plugin",
        "intellij.platform.structuralSearch.plugin",
    ),
    (
        "intellij.structureView.plugin",
        "platform-structureView-plugin",
        "intellij.platform.structureView.plugin",
    ),
    (
        "intellij.testRunner.plugin",
        "platform-testRunner-plugin",
        "intellij.platform.testRunner.plugin",
    ),
    ("intellij.todo.plugin", "platform-todo-plugin", "intellij.platform.todo.plugin"),
    ("org.jetbrains.kotlin", "Kotlin", "kotlin.plugin"),
    ("org.jetbrains.plugins.gradle", "gradle-java-plugin", "intellij.gradle.java.plugin"),
)


def plan(home: Path) -> DescriptorSelection | DescriptorRejected | GraphRejected | IndexRejected:
    repository = home / "modules/module-descriptors.jar"
    available = repository_identities(repository)
    if isinstance(available, DescriptorRejected):
        return available
    try:
        index = parse_plugin_index((home / "plugins/plugin-classpath.txt").read_bytes())
        if isinstance(index, IndexRejected):
            return index
        by_directory = {plugin.directory: plugin for plugin in index.plugins}
        providers: list[Provider] = []
        with zipfile.ZipFile(repository) as archive:
            for identity, directory, name in PROVIDERS:
                header = archive.read("plugins/" + name + ".xml")
                if identity == "com.intellij":
                    descriptor = index.core_descriptor
                    files: tuple[str, ...] = ()
                else:
                    plugin = by_directory.get(directory)
                    if plugin is None:
                        return DescriptorRejected(DescriptorFailure.UNKNOWN_PROVIDER, identity)
                    descriptor, files = plugin.descriptor, plugin.files
                providers.append(Provider(identity, directory, header, descriptor, files))
        with zipfile.ZipFile(home / "lib/intellij.platform.ide.impl.jar") as archive:
            base = archive.read("META-INF/PlatformLangPlugin.xml")
        return select_descriptors(
            repository,
            available,
            tuple(providers),
            ROOTS,
            ("com.intellij.java", "org.jetbrains.kotlin", "org.jetbrains.plugins.gradle"),
            base,
        )
    except (OSError, KeyError, zipfile.BadZipFile):
        return DescriptorRejected(DescriptorFailure.INVALID_REPOSITORY, str(home))


def support_resources(
    home: Path, selection: DescriptorSelection
) -> tuple[ModuleResource, ...] | GraphRejected | DescriptorRejected:
    """Embedded archive bytes do not imply all co-located feature registrations."""
    resources: set[ModuleResource] = set()
    try:
        with zipfile.ZipFile(home / "modules/module-descriptors.jar") as archive:
            for identity in selection.support:
                module = parse_module(archive.read(identity.descriptor), identity)
                if isinstance(module, GraphRejected):
                    return module
                resources.update(module.resources)
        return tuple(sorted(resources))
    except (OSError, KeyError, zipfile.BadZipFile):
        return DescriptorRejected(DescriptorFailure.INVALID_REPOSITORY, str(home))
