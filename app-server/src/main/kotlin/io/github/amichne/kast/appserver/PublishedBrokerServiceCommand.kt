package io.github.amichne.kast.appserver

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Refines the broker's owned launch receipt back into the exact command required for retirement. */
class PublishedBrokerServiceEnvironment internal constructor(
    val values: Map<String, String>,
)

object PublishedBrokerServiceCommand {
    private const val MAXIMUM_PLIST_BYTES = 64 * 1024
    private const val MAXIMUM_ARGUMENTS = 64
    private val environmentName = Regex("[A-Z][A-Z0-9_]{0,127}")

    internal fun recover(observed: BrokerServiceLaunchCommand): BrokerServiceLaunchCommand? = try {
        val environment = readEnvironment(observed) ?: return null
        val publishedIdentity = environment["BROKER_SERVICE_IDENTITY"] ?: return null
        val publishedReadiness = environment["BROKER_READINESS_FILE"] ?: return null
        val javaHome = environment["JAVA_HOME"]?.let(Path::of) ?: return null
        val configurationEnvironment = environment - setOf(
            "BROKER_SERVICE_IDENTITY",
            "BROKER_READINESS_FILE",
            "KAST_OPTS",
        )
        val recovered = when (val resolution = BrokerServiceLaunchCommand.resolve(
            observed.kast,
            observed.userHome,
            configurationEnvironment,
            javaHomeCandidate = javaHome,
            purpose = BrokerServicePurpose.COORDINATOR,
        )) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command
            is BrokerServiceLaunchCommandResolution.Rejected -> return null
        }
        recovered.takeIf {
            it.identity.value == publishedIdentity &&
                it.readinessFile.toString() == publishedReadiness &&
                it.stateDirectory == observed.stateDirectory &&
                it.serviceLabel.value == observed.serviceLabel.value
        }
    } catch (_: Exception) {
        null
    }

    /** Recover a candidate environment for an older executable; that executable must re-prove it. */
    fun retirementEnvironment(
        installationRoot: Path,
        userHome: Path,
        codexHome: Path,
    ): PublishedBrokerServiceEnvironment? = try {
        if (installationRoot.toRealPath() != installationRoot || userHome.toRealPath() != userHome) return null
        val kast = installationRoot.resolve("bin/kast")
        if (Files.isSymbolicLink(kast) || kast.toRealPath() != kast || !Files.isExecutable(kast)) return null
        val baselineEnvironment = mapOf(
            "HOME" to userHome.toString(),
            "PATH" to "/usr/bin:/bin",
            "CODEX_HOME" to codexHome.toString(),
            "KAST_CONFIGURATION_FILE" to installationRoot.resolve("config/environment").toString(),
            "KAST_ENABLE_APP_SERVER" to "0",
        )
        val observed = when (val resolution = BrokerServiceLaunchCommand.resolveCoordinator(
            kast,
            userHome,
            baselineEnvironment,
        )) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command
            is BrokerServiceLaunchCommandResolution.Rejected -> return null
        }
        val environment = readEnvironment(observed) ?: return null
        if (
            environment["HOME"] != userHome.toString() ||
            environment["CODEX_HOME"] != codexHome.toString() ||
            environment["KAST_CONFIGURATION_FILE"] != installationRoot.resolve("config/environment").toString() ||
            environment["BROKER_READINESS_FILE"] != observed.readinessFile.toString()
        ) return null
        PublishedBrokerServiceEnvironment(
            environment - setOf("BROKER_SERVICE_IDENTITY", "BROKER_READINESS_FILE", "KAST_OPTS"),
        )
    } catch (_: Exception) {
        null
    }

    private fun readEnvironment(observed: BrokerServiceLaunchCommand): Map<String, String>? = try {
        val plist = observed.stateDirectory.resolve("service.plist")
        if (Files.isSymbolicLink(plist) || !Files.isRegularFile(plist, NOFOLLOW_LINKS)) return null
        val bytes = Files.newInputStream(plist, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_PLIST_BYTES + 1) }
        if (bytes.size > MAXIMUM_PLIST_BYTES) return null
        val document = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val dictionary = document.documentElement.directElements().singleOrNull { it.tagName == "dict" }
            ?: return null
        val entries = dictionary.directElements()
        if (entries.size % 2 != 0) return null
        val values = entries.chunked(2).associate { pair ->
            val key = pair[0].takeIf { it.tagName == "key" }?.textContent ?: return null
            if (key.isBlank()) return null
            key to pair[1]
        }
        if (values.size != entries.size / 2) return null
        if (values["Label"]?.takeIf { it.tagName == "string" }?.textContent != observed.serviceLabel.value) return null
        val arguments = values["ProgramArguments"]?.takeIf { it.tagName == "array" }
            ?.directElements()?.map { it.takeIf { value -> value.tagName == "string" }?.textContent ?: return null }
            ?: return null
        if (arguments.size !in 5..MAXIMUM_ARGUMENTS || arguments[0] != "/usr/bin/env" || arguments[1] != "-i" ||
            arguments.takeLast(3) != listOf(observed.kast.toString(), "broker", "serve")) return null
        val assignments = arguments.subList(2, arguments.size - 3)
        val environment = assignments.associate { assignment ->
            val separator = assignment.indexOf('=')
            if (separator <= 0) return null
            val name = assignment.substring(0, separator)
            if (!environmentName.matches(name)) return null
            name to assignment.substring(separator + 1)
        }
        if (environment.size != assignments.size) return null
        environment
    } catch (_: Exception) {
        null
    }

    private fun Element.directElements(): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let(::add)
        }
    }
}
