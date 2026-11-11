package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.ManagedHeadlessGradleHome
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Persisted trust and selectors must still name the creation-time workspace and private profile. */
internal fun admitContainerState(container: AdmittedContainer): Refinement<AdmittedContainer, ContainerFailure> {
    val plugins =
        when (val selected = selectedContainerPlugins(container)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    when (val saved = readContainerRecord<ManagedHeadlessGradleHome>(container.state.resolve("gradle-home.json"))) {
        is Refinement.Rejected -> return saved
        is Refinement.Refined ->
            if (saved.value.path != container.gradleHome.toString())
                return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }
    if (
        !boundedPhysicalRecord(container.configuration) ||
            Files.readString(container.configuration) != containerConfiguration(container)
    )
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    return when (
        val trust = admitContainerTrust(plugins.parent.resolve("options/trusted-paths.xml"), container.workspace)
    ) {
        is Refinement.Rejected -> trust
        is Refinement.Refined -> Refinement.Refined(container)
    }
}

private fun selectedContainerPlugins(container: AdmittedContainer): Refinement<Path, ContainerFailure> {
    val launch =
        when (val selected = SelectedIdeInstallation.resolve(container.runtime.home)) {
            is SelectedIdeLaunch.Unavailable -> return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
            is SelectedIdeLaunch.Resolved -> selected
        }
    val plugins =
        when (val selected = SelectedIdeInstallation.pluginRoot(launch, container.home)) {
            is Refinement.Rejected -> return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            is Refinement.Refined -> selected.value.value
        }
    val target =
        when (val selected = SelectedIdeInstallation.admitPluginTarget(plugins.toString(), container.home)) {
            is Refinement.Rejected -> return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
            is Refinement.Refined -> selected.value
        }
    val expected = SelectedIdeInstallation.recordInstalledHostTarget(launch, target)
    return when (
        val saved = readContainerRecord<SelectedIdeLaunch>(container.directory.resolve("config/selected-ide.json"))
    ) {
        is Refinement.Rejected -> saved
        is Refinement.Refined ->
            if (saved.value == expected) Refinement.Refined(plugins)
            else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }
}

private fun boundedPhysicalRecord(path: Path): Boolean =
    Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path && Files.size(path) <= CONTAINER_RECORD_BYTES

private fun admitContainerTrust(trust: Path, workspace: Path): Refinement<Unit, ContainerFailure> {
    if (!boundedPhysicalRecord(trust)) return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    return try {
        val factory = DocumentBuilderFactory.newDefaultInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        val actual = builder.parse(InputSource(StringReader(Files.readString(trust)))).documentElement
        val required = builder.parse(InputSource(StringReader(containerTrustXml(workspace)))).documentElement
        if (sameTrust(actual, required)) Refinement.Refined(Unit)
        else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    } catch (_: org.xml.sax.SAXException) {
        Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }
}

internal fun containerConfiguration(container: AdmittedContainer): String =
    "KAST_INSTALL_IDEA_HOME=${container.runtime.home}\nKAST_MANAGED_HEADLESS_STATE=${container.state}\nGRADLE_USER_HOME=${container.gradleHome}\n"

private fun sameTrust(actual: Element, required: Element): Boolean {
    if (actual.tagName != required.tagName || actual.attributes.length != required.attributes.length) return false
    for (index in 0 until required.attributes.length) {
        val attribute = required.attributes.item(index)
        if (actual.getAttribute(attribute.nodeName) != attribute.nodeValue) return false
    }
    val children = (0 until actual.childNodes.length).map { actual.childNodes.item(it) }
    if (children.any { it.nodeType != org.w3c.dom.Node.ELEMENT_NODE && !it.textContent.isNullOrBlank() }) return false
    val elements = children.filterIsInstance<Element>()
    val expected = (0 until required.childNodes.length).map { required.childNodes.item(it) }.filterIsInstance<Element>()
    return elements.size == expected.size && elements.zip(expected).all { (a, b) -> sameTrust(a, b) }
}
