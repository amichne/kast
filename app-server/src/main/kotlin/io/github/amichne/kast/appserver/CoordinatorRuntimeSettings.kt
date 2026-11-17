package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationDisclosure
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationFailure
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import io.github.amichne.kast.distribution.contract.gradle.GradleImportVariableEvidence
import io.github.amichne.kast.distribution.contract.gradle.GradleImportVariableName
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val workspaceSettings =
    setOf(
        ConfigurationParameter.RUNTIME_DIRECTORY,
        ConfigurationParameter.GRADLE_JAVA_HOME,
        ConfigurationParameter.GRADLE_IMPORT_VARIABLES,
        ConfigurationParameter.GRADLE_IMPORT_PATH,
        ConfigurationParameter.GRADLE_USER_HOME,
        ConfigurationParameter.MANAGED_HEADLESS_STATE,
        ConfigurationParameter.INSTALL_IDEA_HOME,
        ConfigurationParameter.WORKSPACE_PREPARATION_WAIT,
        ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING,
        ConfigurationParameter.HOST_IDLE_SHUTDOWN,
        ConfigurationParameter.HOST_MAX_HEAP_MIB,
        ConfigurationParameter.DEBUG,
    )

private val statedParameters =
    ConfigurationParameter.entries.filter { it.readLimit != null || it in workspaceSettings }.sortedBy { it.key }

/**
 * Wire form of a caller's environment-tier settings: primitives only until coordinator admission. Delegated Gradle
 * variables travel as the established name and value digest; their values never leave the caller.
 */
@Serializable
internal data class DaemonRuntimeSettings(
    @Required val assignments: List<DaemonRuntimeAssignment>,
    @Required val delegated: List<DaemonDelegatedVariable>,
) {
    override fun toString(): String =
        "DaemonRuntimeSettings(keys=${assignments.map { it.key }}, delegated=${delegated.map { it.name }})"

    companion object {
        const val maximumBytes: Int = 64 * 1024
        const val maximumDelegated: Int = 64
    }
}

@Serializable
internal data class DaemonRuntimeAssignment(val key: String, val value: String) {
    override fun toString(): String = "DaemonRuntimeAssignment(key=$key)"
}

@Serializable
internal data class DaemonDelegatedVariable(val name: String, val digest: String) {
    override fun toString(): String = "DaemonDelegatedVariable(name=$name)"
}

/** Finite runtime-settings rejection. Raw values never appear; caller keys are bounded and sanitized. */
@Serializable
sealed interface DaemonRuntimeSettingsFailure {
    @Serializable @SerialName("capacity_exceeded") data object CapacityExceeded : DaemonRuntimeSettingsFailure

    /** A catalogue parameter that this route keeps request-local or coordinator-owned. */
    @Serializable
    @SerialName("not_governed")
    data class NotGoverned(val parameter: ConfigurationParameter) : DaemonRuntimeSettingsFailure

    @Serializable
    @SerialName("configuration")
    data class Configuration(val key: String, val reason: ConfigurationFailure) : DaemonRuntimeSettingsFailure

    /** The request states settings the selected execution cannot honor without an incompatible Host. */
    @Serializable
    @SerialName("conflict")
    data class Conflict(val conflicts: List<DaemonRuntimeSettingConflict>) : DaemonRuntimeSettingsFailure
}

@Serializable
data class DaemonRuntimeSettingConflict(
    val parameter: ConfigurationParameter,
    val request: RuntimeSettingStatement,
    val coordinator: RuntimeSettingStatement,
)

@Serializable
enum class RuntimeSettingStatement {
    STATED,
    UNSTATED,
}

/**
 * A caller's statement of its environment-tier runtime settings for one request. Projection is total and selects only
 * catalogue keys owned by semantic execution; the coordinator owns admission. Saved settings stay on the coordinator
 * side, so established precedence is preserved there.
 */
class RequestedRuntimeSettings private constructor(internal val wire: DaemonRuntimeSettings) {
    override fun toString(): String = "RequestedRuntimeSettings(${wire})"

    companion object {
        val Unstated = RequestedRuntimeSettings(DaemonRuntimeSettings(emptyList(), emptyList()))

        fun fromEnvironment(environment: Map<String, String>): RequestedRuntimeSettings {
            val assignments = statedParameters.mapNotNull { parameter ->
                environment[parameter.key]?.let { DaemonRuntimeAssignment(parameter.key, it) }
            }
            val names = environment[GradleImportEnvironment.VARIABLES_SETTING].orEmpty()
            // An unadmissible selection sends no digests; the coordinator then rejects the inconsistent statement.
            val delegated =
                when (val admitted = GradleImportEnvironment.admit(names, "", environment)) {
                    is Refinement.Refined ->
                        admitted.value.evidence.map { DaemonDelegatedVariable(it.name.value, it.digest.value) }
                    is Refinement.Rejected -> emptyList()
                }
            return RequestedRuntimeSettings(DaemonRuntimeSettings(assignments, delegated))
        }
    }
}

/** Canonical, admitted runtime settings. Values never appear in failures or [toString]. */
class AdmittedRuntimeSettings
private constructor(
    private val values: Map<ConfigurationParameter, String>,
    private val delegated: Set<GradleImportVariableEvidence>,
) {
    val parameters: Set<ConfigurationParameter>
        get() = values.keys

    /** Every stated parameter whose presence, canonical value or delegated value identity differs. */
    fun conflictsWith(coordinator: AdmittedRuntimeSettings): List<DaemonRuntimeSettingConflict> {
        fun statement(settings: AdmittedRuntimeSettings, parameter: ConfigurationParameter) =
            if (parameter in settings.values) RuntimeSettingStatement.STATED else RuntimeSettingStatement.UNSTATED
        return statedParameters
            .filter { parameter ->
                values[parameter] != coordinator.values[parameter] ||
                    (parameter == ConfigurationParameter.GRADLE_IMPORT_VARIABLES && delegated != coordinator.delegated)
            }
            .map { DaemonRuntimeSettingConflict(it, statement(this, it), statement(coordinator, it)) }
    }

    /**
     * The inherited environment for a settings-aware executor: the coordinator launch environment with its stated tier
     * replaced by this request's. An unstated key falls through to saved workspace, saved installation and defaults.
     * Delegated values exist only in the coordinator launch, so a different delegated identity is a conflict.
     */
    fun environmentOver(
        launch: Map<String, String>,
        launchSettings: AdmittedRuntimeSettings,
    ): Refinement<Map<String, String>, DaemonRuntimeSettingsFailure> {
        if (delegated != launchSettings.delegated)
            return Refinement.Rejected(
                DaemonRuntimeSettingsFailure.Conflict(
                    listOf(
                        DaemonRuntimeSettingConflict(
                            ConfigurationParameter.GRADLE_IMPORT_VARIABLES,
                            RuntimeSettingStatement.STATED,
                            RuntimeSettingStatement.STATED,
                        )
                    )
                )
            )
        val stated = statedParameters.map { it.key }.toSet()
        return Refinement.Refined(launch.filterKeys { it !in stated } + values.mapKeys { it.key.key })
    }

    override fun equals(other: Any?): Boolean =
        other is AdmittedRuntimeSettings && other.values == values && other.delegated == delegated

    override fun hashCode(): Int = 31 * values.hashCode() + delegated.hashCode()

    override fun toString(): String =
        "AdmittedRuntimeSettings(keys=${values.keys.map { it.key }.sorted()}, delegated=${delegated.map { it.name.value }.sorted()})"

    companion object {
        fun fromEnvironment(
            environment: Map<String, String>
        ): Refinement<AdmittedRuntimeSettings, DaemonRuntimeSettingsFailure> =
            admit(RequestedRuntimeSettings.fromEnvironment(environment).wire)

        internal fun admit(
            wire: DaemonRuntimeSettings
        ): Refinement<AdmittedRuntimeSettings, DaemonRuntimeSettingsFailure> {
            fun reject(failure: DaemonRuntimeSettingsFailure) = Refinement.Rejected(failure)
            fun configuration(key: String, reason: ConfigurationFailure) =
                reject(DaemonRuntimeSettingsFailure.Configuration(boundedKey(key), reason))
            if (!wire.withinLimits()) return reject(DaemonRuntimeSettingsFailure.CapacityExceeded)
            val raw = linkedMapOf<ConfigurationParameter, String>()
            for (assignment in wire.assignments) {
                val parameter =
                    KastConfigurationCatalogue.parameter(assignment.key)
                        ?: return configuration(assignment.key, ConfigurationFailure.UNKNOWN_KEY)
                if (parameter !in statedParameters) return reject(DaemonRuntimeSettingsFailure.NotGoverned(parameter))
                if (raw.put(parameter, assignment.value) != null)
                    return configuration(assignment.key, ConfigurationFailure.DUPLICATE_ASSIGNMENT)
            }
            val delegated =
                when (val admitted = delegatedEvidence(raw[ConfigurationParameter.GRADLE_IMPORT_VARIABLES], wire)) {
                    is Refinement.Rejected -> return admitted
                    is Refinement.Refined -> admitted.value
                }
            // Delegated values are absent by construction; their names and digests were admitted above.
            val resolved =
                when (
                    val resolution =
                        ResolvedKastConfiguration.resolve(
                            ConfigurationSources(
                                environment =
                                    raw.filterKeys { it != ConfigurationParameter.GRADLE_IMPORT_VARIABLES }
                                        .mapKeys { it.key.key }
                            )
                        )
                ) {
                    is Refinement.Rejected -> return configuration(resolution.failure.key, resolution.failure.reason)
                    is Refinement.Refined -> resolution.value
                }
            val inspected = resolved.inspection().associateBy { it.key }
            val values = raw.mapValues { (parameter, value) ->
                when {
                    parameter == ConfigurationParameter.GRADLE_IMPORT_VARIABLES ->
                        delegated.map { it.name.value }.sorted().joinToString(",")
                    parameter.declaration().disclosure == ConfigurationDisclosure.PUBLIC ->
                        inspected.getValue(parameter.key).value
                    // Sensitive values are admitted absolute, normalized paths; the path form is canonical.
                    else -> Path.of(value).toString()
                }
            }
            return Refinement.Refined(AdmittedRuntimeSettings(values, delegated))
        }

        private fun delegatedEvidence(
            names: String?,
            wire: DaemonRuntimeSettings,
        ): Refinement<Set<GradleImportVariableEvidence>, DaemonRuntimeSettingsFailure> {
            val rejected =
                Refinement.Rejected(
                    DaemonRuntimeSettingsFailure.Configuration(
                        GradleImportEnvironment.VARIABLES_SETTING,
                        ConfigurationFailure.DELEGATED_ENVIRONMENT_REJECTED,
                    )
                )
            val selected = if (names.isNullOrEmpty()) emptyList() else names.split(',')
            if (selected.toSet().size != selected.size) return rejected
            val evidence = mutableSetOf<GradleImportVariableEvidence>()
            for (variable in wire.delegated) {
                val identity = variable.evidence() ?: return rejected
                if (!evidence.add(identity)) return rejected
            }
            return if (evidence.map { it.name.value }.toSet() == selected.toSet() && evidence.size == selected.size)
                Refinement.Refined(evidence)
            else rejected
        }

        private fun boundedKey(key: String): String =
            key.take(MAXIMUM_REJECTED_KEY).filter { it.isLetterOrDigit() || it == '_' || it == '.' }
    }
}

private fun DaemonRuntimeSettings.withinLimits(): Boolean {
    val bytes =
        assignments.sumOf { it.key.length.toLong() + it.value.length } +
            delegated.sumOf { it.name.length.toLong() + it.digest.length }
    return assignments.size <= statedParameters.size &&
        delegated.size <= DaemonRuntimeSettings.maximumDelegated &&
        bytes <= DaemonRuntimeSettings.maximumBytes
}

private fun DaemonDelegatedVariable.evidence(): GradleImportVariableEvidence? {
    val admittedName = (GradleImportVariableName.parse(name) as? Refinement.Refined)?.value ?: return null
    val admittedDigest = (GradleImportEnvironmentIdentity.parse(digest) as? Refinement.Refined)?.value ?: return null
    return GradleImportVariableEvidence(admittedName, admittedDigest)
}

private const val MAXIMUM_REJECTED_KEY = 128

/** One admitted semantic request with the caller's runtime settings, for a settings-aware executor. */
class CoordinatorOperationRequest
internal constructor(
    val root: CanonicalRoot,
    val call: DaemonOperationCall,
    val settings: AdmittedRuntimeSettings,
)

/**
 * Settings-aware coordinator execution. It owns comparison against its retained bindings and must reject, or
 * deliberately fence and replace, a binding whose admitted runtime settings differ from
 * [CoordinatorOperationRequest.settings].
 */
fun interface CoordinatorRequestExecutor {
    suspend fun execute(request: CoordinatorOperationRequest): DaemonOperationResult
}

/** The ingress's execution choice; a launch-bound executor applies only the coordinator's own launch settings. */
internal sealed interface CoordinatorExecution {
    class LaunchBound(val executor: CoordinatorOperationExecutor, val launch: AdmittedRuntimeSettings) :
        CoordinatorExecution

    class RequestBound(val executor: CoordinatorRequestExecutor) : CoordinatorExecution
}

/** The caller-selected executor before the coordinator captures its own launch settings. */
internal sealed interface CoordinatorSemantic {
    class LaunchBound(val executor: CoordinatorOperationExecutor) : CoordinatorSemantic

    class RequestBound(val executor: CoordinatorRequestExecutor) : CoordinatorSemantic

    fun execution(environment: Map<String, String>): Refinement<CoordinatorExecution, DaemonRuntimeSettingsFailure> =
        when (this) {
            is RequestBound -> Refinement.Refined(CoordinatorExecution.RequestBound(executor))
            is LaunchBound ->
                when (val launch = AdmittedRuntimeSettings.fromEnvironment(environment)) {
                    is Refinement.Rejected -> launch
                    is Refinement.Refined ->
                        Refinement.Refined(CoordinatorExecution.LaunchBound(executor, launch.value))
                }
        }
}
