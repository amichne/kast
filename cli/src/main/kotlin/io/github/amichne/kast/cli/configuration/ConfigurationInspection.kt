package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.AdmittedAppServerConfiguration
import io.github.amichne.kast.appserver.AppliedConfigurationInspection
import io.github.amichne.kast.appserver.AppliedConfigurationUnavailable
import io.github.amichne.kast.appserver.InstalledConfigurationAppliedInspection
import kotlinx.coroutines.runBlocking
import io.github.amichne.kast.appserver.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import java.nio.file.Path
import java.nio.file.InvalidPathException
import io.github.amichne.kast.appserver.InstalledSavedConfigurationIngress
import io.github.amichne.kast.appserver.SavedConfigurationIngress
import io.github.amichne.kast.appserver.SavedConfigurationObservation

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSchemaDocument
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationSchema
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationInspection
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSemanticAdmission
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

internal sealed interface ConfigurationInspectionHandling {
    data object Unrelated : ConfigurationInspectionHandling
    class Handled(val exit: CliExit) : ConfigurationInspectionHandling
}

/** Passive configuration ingress runs before provider loading, installation admission, or runtime effects. */
internal object ConfigurationCliInspection {
    fun inspect(arguments: List<String>, environment: Map<String, String>): ConfigurationInspectionHandling {
        if (arguments.firstOrNull() != "config") return ConfigurationInspectionHandling.Unrelated
        val supplied = arguments.drop(1)
        val workspaceOption = supplied.indexOf("--workspace")
        val workspaceText = if (workspaceOption >= 0) supplied.getOrNull(workspaceOption + 1) else null
        if (workspaceOption >= 0 && (workspaceText == null || supplied.count { it == "--workspace" } != 1)) {
            return complete(failureFactory.create(ConfigurationInspectionFailureDocument(operation = "config", reason = "INVALID_COMMAND")))
        }
        val tail = if (workspaceOption < 0) supplied else supplied.filterIndexed { index, _ -> index != workspaceOption && index != workspaceOption + 1 }
        if (workspaceText != null && tail.firstOrNull() !in listOf("show", "explain")) {
            return complete(failureFactory.create(ConfigurationInspectionFailureDocument(operation = "config", reason = "INVALID_COMMAND")))
        }
        if (tail == listOf("schema") || tail == listOf("schema", "--json")) {
            return complete(schemaFactory.create(InstalledConfigurationSchema.document))
        }
        val explainKey = if (tail.size == 2 && tail.first() == "explain") tail[1] else null
        val validationFile = when {
            tail.size == 3 && tail[0] == "validate" && tail[1] == "--file" -> tail[2]
            tail.size == 4 && tail[0] == "validate" && tail[1] == "--file" && tail[3] == "--json" -> tail[2]
            else -> null
        }
        val show = tail == listOf("show") || tail == listOf("show", "--json")
        if (!show && explainKey == null && validationFile == null) return finish(failureFactory.create(ConfigurationInspectionFailureDocument(
            operation = "config", reason = "INVALID_COMMAND",
        )), rejectedValidation = tail.firstOrNull() == "validate")
        if (explainKey != null && KastConfigurationCatalogue.declarations.none { it.key == explainKey }) {
            return complete(failureFactory.create(ConfigurationInspectionFailureDocument(operation = "config-explain", reason = "UNKNOWN_KEY")))
        }
        val operation = when {
            validationFile != null -> "config-validate"
            show -> "config-show"
            else -> "config-explain"
        }
        val ingress = if (workspaceText != null) {
            val selector = environment[ConfigurationParameter.CONFIGURATION_FILE.key]
                ?: return complete(failureFactory.create(ConfigurationInspectionFailureDocument(operation = operation, reason = "INVALID_SELECTOR")))
            val paths = try { Path.of(selector) to Path.of(workspaceText) } catch (_: InvalidPathException) {
                return complete(failureFactory.create(ConfigurationInspectionFailureDocument(operation = operation, reason = "INVALID_SELECTOR")))
            }
            val installation = paths.first.parent?.parent
                ?: return complete(failureFactory.create(ConfigurationInspectionFailureDocument(operation = operation, reason = "INVALID_SELECTOR")))
            InstalledWorkspaceConfigurationIngress.load(installation, paths.second, environment)
        } else if (validationFile == null) InstalledSavedConfigurationIngress.load(environment)
        else InstalledSavedConfigurationIngress.read(validationFile, environment)
        val source = when (val read = ingress) {
            is SavedConfigurationIngress.Loaded -> read
            is SavedConfigurationIngress.Rejected -> return finish(failureFactory.create(ConfigurationInspectionFailureDocument(
                operation = operation, reason = read.rejection.reason(),
            )), rejectedValidation = validationFile != null)
        }
        val resolved = when (val result = ResolvedKastConfiguration.resolve(source.sources)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return finish(failureFactory.create(ConfigurationInspectionFailureDocument(
                operation = operation, reason = result.failure.reason.name, key = result.failure.key,
            )), rejectedValidation = validationFile != null)
        }
        if (validationFile != null) {
            when (val owner = AdmittedAppServerConfiguration.admit(resolved)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return finish(failureFactory.create(ConfigurationInspectionFailureDocument(
                    operation = operation, reason = owner.failure.name,
                )), rejectedValidation = true)
            }
            return complete(validationFactory.create(ConfigurationValidationDocument(
                sourceObservation = source.observation,
                resolvedNextLaunch = resolved.inspection(),
            )))
        }
        val applied = observeApplied(environment, resolved, workspaceText)
        if (show) return complete(showFactory.create(ConfigurationShowDocument(
            resolvedNextLaunch = resolved.inspection(), desiredSavedConfiguration = source.observation,
            desiredWorkspaceConfiguration = source.workspaceObservation,
            applied = applied.state(), appliedObservation = applied,
        )))
        val declaration = KastConfigurationCatalogue.declarations.single { it.key == explainKey }
        val assignment = resolved.inspection().singleOrNull { it.key == explainKey }
        return complete(explainFactory.create(ConfigurationExplainDocument(
            key = declaration.key,
            source = assignment?.source?.name ?: "OWNER_DEFAULT",
            value = assignment?.value ?: "owner-default-unobserved",
            overriddenSources = assignment?.overriddenSources?.map { it.name }.orEmpty(),
            applicationBoundary = declaration.applicationBoundary.name,
            semanticAdmission = assignment?.semanticAdmission ?: ConfigurationSemanticAdmission.OWNER_REQUIRED,
            applied = applied.state(), appliedObservation = applied,
        )))
    }

    private fun observeApplied(environment: Map<String,String>, configuration: ResolvedKastConfiguration, workspace: String?): AppliedConfigurationInspection {
        val selector = environment[ConfigurationParameter.CONFIGURATION_FILE.key]
            ?: return AppliedConfigurationInspection.Unobserved(AppliedConfigurationUnavailable.INSTALLATION_UNSELECTED)
        val home = environment["HOME"]
            ?: return AppliedConfigurationInspection.Unobserved(AppliedConfigurationUnavailable.INSTALLATION_UNSELECTED)
        return try {
            val selected = Path.of(selector)
            val root = selected.parent?.parent
                ?: return AppliedConfigurationInspection.Unobserved(AppliedConfigurationUnavailable.INSTALLATION_UNSELECTED)
            if (selected != root.resolve("config/environment")) return AppliedConfigurationInspection.Unobserved(AppliedConfigurationUnavailable.INSTALLATION_UNSELECTED)
            runBlocking { InstalledConfigurationAppliedInspection.read(root.resolve("bin/kast"), Path.of(home), environment, configuration, workspace?.let(Path::of)) }
        } catch (_: InvalidPathException) { AppliedConfigurationInspection.Unobserved(AppliedConfigurationUnavailable.OWNER_REJECTED) }
    }

    private fun AppliedConfigurationInspection.state(): String = when (this) {
        is AppliedConfigurationInspection.Unobserved -> "unobserved"
        is AppliedConfigurationInspection.Acknowledged -> "acknowledged"
        is AppliedConfigurationInspection.Pending -> "pending"
    }

    private fun complete(document: CliJsonDocument) = ConfigurationInspectionHandling.Handled(CliExit.Complete(document))
    private fun finish(document: CliJsonDocument, rejectedValidation: Boolean): ConfigurationInspectionHandling.Handled =
        if (rejectedValidation) ConfigurationInspectionHandling.Handled(CliExit.BoundaryRejected(CliBoundaryExitStatus.USAGE, document))
        else complete(document)
}

@Serializable
private data class ConfigurationShowDocument(
    val operation: String = "config-show",
    val status: String = "complete",
    val resolvedNextLaunch: List<ConfigurationInspection>,
    val sourceObservation: String = "explicit-process-environment-and-pinned-saved-source",
    val desiredSavedConfiguration: SavedConfigurationObservation,
    val desiredWorkspaceConfiguration: SavedConfigurationObservation,
    val appliedObservation: AppliedConfigurationInspection,
    val applied: String = "unobserved",
)

@Serializable
private data class ConfigurationValidationDocument(
    val operation: String = "config-validate",
    val status: String = "complete",
    val sourceObservation: SavedConfigurationObservation,
    val resolvedNextLaunch: List<ConfigurationInspection>,
    val validationBoundary: String = "configuration-syntax-source-policy-and-app-server-tools; physical-path-admission-remains-explicit",
    val applied: String = "unobserved",
)

@Serializable
private data class ConfigurationExplainDocument(
    val operation: String = "config-explain",
    val status: String = "complete",
    val key: String,
    val source: String,
    val value: String,
    val overriddenSources: List<String>,
    val applicationBoundary: String,
    val semanticAdmission: ConfigurationSemanticAdmission,
    val appliedObservation: AppliedConfigurationInspection,
    val applied: String = "unobserved",
)

@Serializable
private data class ConfigurationInspectionFailureDocument(
    val operation: String,
    val status: String = "rejected",
    val reason: String,
    val key: String? = null,
    val applied: String = "unobserved",
)

private val schemaFactory = CliJsonDocument.generated(ConfigurationSchemaDocument.serializer())
private val showFactory = CliJsonDocument.generated(ConfigurationShowDocument.serializer())
private val explainFactory = CliJsonDocument.generated(ConfigurationExplainDocument.serializer())
private val failureFactory = CliJsonDocument.generated(ConfigurationInspectionFailureDocument.serializer())
private val validationFactory = CliJsonDocument.generated(ConfigurationValidationDocument.serializer())
