package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.distribution.contract.IndexerHeapFailure
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.contract.gradle.GradleImportExecutableDirectory
import io.github.amichne.kast.distribution.contract.gradle.GradleImportVariableName
import io.github.amichne.kast.kernel.ReadLimitSource
import io.github.amichne.kast.kernel.ReadLimitValue
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Raw inputs exist only until admission. Lists deliberately preserve duplicate saved assignments. */
data class ConfigurationSources(
    val environment: Map<String, String> = emptyMap(),
    val savedInstallation: List<Pair<String, String>> = emptyList(),
    val savedWorkspace: List<Pair<String, String>> = emptyList(),
    val commandLine: List<Pair<String, String>> = emptyList(),
) {
    override fun toString(): String =
        "ConfigurationSources(environmentKeys=${environment.keys.size}, savedInstallationAssignments=${savedInstallation.size}, savedWorkspaceAssignments=${savedWorkspace.size}, commandLineAssignments=${commandLine.size})"
}

@Serializable
enum class ConfigurationFailure {
    UNKNOWN_KEY,
    DUPLICATE_ASSIGNMENT,
    UNSUPPORTED_SOURCE,
    TEST_ONLY_INPUT,
    BUILD_ONLY_INPUT,
    INVALID_VALUE,
    INVALID_PATH,
    INVALID_HEAP,
    DELEGATED_ENVIRONMENT_REJECTED,
    SAVED_CONFIGURATION_REJECTED,
}

/** Only a bounded setting identity and finite condition leave rejection; never raw values. */
data class ConfigurationRejection
internal constructor(
    val key: String,
    val reason: ConfigurationFailure,
    val detail: ConfigurationRejectionDetail = ConfigurationRejectionDetail.General,
)

sealed interface ConfigurationRejectionDetail {
    data object General : ConfigurationRejectionDetail

    class Heap(val failure: IndexerHeapFailure) : ConfigurationRejectionDetail
}

enum class ConfigurationSwitch {
    ENABLED,
    DISABLED,
}

enum class ConfigurationOwner(val module: String) {
    APP_SERVER(":app-server")
}

/** Declared accounting limits; these values do not establish physical memory availability. */
@JvmInline
value class WorkerCountLimit private constructor(val value: Int) {
    companion object {
        const val Maximum: Int = 256

        fun admit(value: Int): Refinement<WorkerCountLimit, ConfigurationFailure> =
            if (value in 1..Maximum) Refinement.Refined(WorkerCountLimit(value))
            else Refinement.Rejected(ConfigurationFailure.INVALID_VALUE)
    }
}

@JvmInline
value class WorkerMemoryReservationMiB private constructor(val value: Long) {
    companion object {
        fun admit(value: Long): Refinement<WorkerMemoryReservationMiB, ConfigurationFailure> =
            if (value > 0) Refinement.Refined(WorkerMemoryReservationMiB(value))
            else Refinement.Rejected(ConfigurationFailure.INVALID_VALUE)
    }
}

class WorkerCapacityConfiguration
internal constructor(
    val resident: WorkerCountLimit,
    val startup: WorkerCountLimit,
    val aggregate: WorkerMemoryReservationMiB,
    val native: WorkerMemoryReservationMiB,
    val gradle: WorkerMemoryReservationMiB,
)

@Serializable
enum class ConfigurationSemanticAdmission {
    OWNER_REQUIRED,
    PHYSICAL_REQUIRED,
    ADMITTED,
}

sealed interface ConfigurationPathSelection {
    data object OwnerDefault : ConfigurationPathSelection

    class Selected internal constructor(val path: Path) : ConfigurationPathSelection
}

internal sealed interface ConfigurationValue {
    class ReadLimit(val value: ReadLimitValue) : ConfigurationValue

    class WorkerCount(val value: WorkerCountLimit) : ConfigurationValue

    class Memory(val value: WorkerMemoryReservationMiB) : ConfigurationValue

    class Heap(val value: IndexerHeapSize) : ConfigurationValue

    class Directory(val value: Path) : ConfigurationValue

    class Switch(val value: ConfigurationSwitch) : ConfigurationValue

    /** Bounded input for the declared owning parser; not proof of owner-specific semantic validity. */
    class OwnerInput(val value: String) : ConfigurationValue
}

internal data class ResolvedAssignment(
    val parameter: ConfigurationParameter,
    val value: ConfigurationValue,
    val source: ConfigurationSource,
    val overriddenSources: List<ConfigurationSource>,
)

@Serializable
data class ConfigurationInspection(
    val key: String,
    val source: ConfigurationSource,
    val value: String,
    val overriddenSources: List<ConfigurationSource>,
    val applicationBoundary: ConfigurationBoundary,
    val semanticAdmission: ConfigurationSemanticAdmission,
)

/** A supplied candidate retained until the named owner establishes its semantic meaning. */
class ConfigurationOwnerCandidate
internal constructor(
    val parameter: ConfigurationParameter,
    val source: ConfigurationSource,
    private val value: ConfigurationValue,
) {
    fun valueAtOwnerBoundary(): String = value.boundaryValue()

    override fun toString(): String = "ConfigurationOwnerCandidate(parameter=$parameter, source=$source)"
}

/** Resolved, non-secret launch evidence; arbitrary ambient variables can never enter it. */
class ResolvedLaunchEnvironment internal constructor(val variables: Map<String, String>)

/** Resolved syntax and provenance. Effectful owner admission (paths, tools, trust) remains explicit. */
class ResolvedKastConfiguration
private constructor(
    private val assignments: List<ResolvedAssignment>,
    private val suppliedCandidates: List<ResolvedAssignment>,
    val indexerHeap: IndexerHeapSize,
    val launchd: ConfigurationSwitch,
    val debug: ConfigurationSwitch,
    val workerCapacity: WorkerCapacityConfiguration,
    val readLimits: ReadLimits,
    private val delegatedEnvironment: GradleImportEnvironment,
) {
    val runtimeArchive: ConfigurationPathSelection
        get() = path(ConfigurationParameter.RUNTIME_ARCHIVE)

    val runtimeStore: ConfigurationPathSelection
        get() = path(ConfigurationParameter.RUNTIME_STORE)

    val runtimeDirectory: ConfigurationPathSelection
        get() = path(ConfigurationParameter.RUNTIME_DIRECTORY)

    val cacheRoot: ConfigurationPathSelection
        get() = path(ConfigurationParameter.CACHE_ROOT)

    private fun path(parameter: ConfigurationParameter): ConfigurationPathSelection =
        when (val assignment = assignments.singleOrNull { it.parameter == parameter }) {
            null -> ConfigurationPathSelection.OwnerDefault
            else ->
                when (val value = assignment.value) {
                    is ConfigurationValue.Directory -> ConfigurationPathSelection.Selected(value.value)
                    else -> error("Catalogue path declaration invariant lost")
                }
        }

    fun inspection(): List<ConfigurationInspection> = assignments.map { assignment ->
        val declaration = assignment.parameter.declaration()
        ConfigurationInspection(
            declaration.key,
            assignment.source,
            when (declaration.disclosure) {
                ConfigurationDisclosure.PUBLIC -> assignment.value.boundaryValue()
                ConfigurationDisclosure.SENSITIVE_PATH -> "<path>"
                ConfigurationDisclosure.SECRET_PRESENCE -> "<present>"
            },
            assignment.overriddenSources,
            declaration.applicationBoundary,
            when (assignment.value) {
                is ConfigurationValue.OwnerInput -> ConfigurationSemanticAdmission.OWNER_REQUIRED
                is ConfigurationValue.Directory -> ConfigurationSemanticAdmission.PHYSICAL_REQUIRED
                is ConfigurationValue.ReadLimit,
                is ConfigurationValue.Heap,
                is ConfigurationValue.Switch,
                is ConfigurationValue.WorkerCount,
                is ConfigurationValue.Memory -> ConfigurationSemanticAdmission.ADMITTED
            },
        )
    }

    /** Complete non-secret runtime configuration, including catalogue defaults, for launch evidence. */
    fun launchEnvironment(): ResolvedLaunchEnvironment =
        ResolvedLaunchEnvironment(
            assignments
                .filter { assignment ->
                    assignment.parameter.scope in
                        setOf(
                            ConfigurationScope.INSTALLATION,
                            ConfigurationScope.HOST_PROFILE,
                            ConfigurationScope.WORKSPACE,
                        ) && assignment.parameter.disclosure != ConfigurationDisclosure.SECRET_PRESENCE
                }
                .associate { assignment ->
                    assignment.parameter.key to assignment.value.boundaryValue()
                }
        )

    /** Raw values may leave only at the named child process effect boundary. No ambient forwarding. */
    fun childEnvironment(child: ConfigurationChild): Map<String, String> = childProjection(child, reloadSaved = true)

    /** Internal launch identity includes materialized saved inputs, without exporting secret values. */
    fun childIdentityInputs(child: ConfigurationChild): Map<String, String> =
        childProjection(child, reloadSaved = false).mapValues { (key, value) ->
            if (delegatedEnvironment.evidence.any { it.name.value == key }) "<present>" else value
        }

    private fun childProjection(child: ConfigurationChild, reloadSaved: Boolean): Map<String, String> = buildMap {
        val reloadInstallation =
            reloadSaved &&
                child == ConfigurationChild.BROKER &&
                assignments.any { it.parameter == ConfigurationParameter.CONFIGURATION_FILE }
        for (assignment in assignments) {
            if (
                child in assignment.parameter.children &&
                    (!reloadSaved || assignment.source != ConfigurationSource.DEFAULT) &&
                    !(reloadInstallation && assignment.source == ConfigurationSource.SAVED_INSTALLATION)
            ) {
                put(assignment.parameter.key, assignment.value.boundaryValue())
            }
        }
        if (delegatedEnvironment.evidence.isNotEmpty() || delegatedEnvironment.executableDirectories.isNotEmpty()) {
            putAll(delegatedEnvironment.processVariables())
            if (
                !(reloadInstallation &&
                    assignments.any {
                        it.parameter == ConfigurationParameter.GRADLE_IMPORT_VARIABLES &&
                            it.source == ConfigurationSource.SAVED_INSTALLATION
                    })
            )
                put(
                    GradleImportEnvironment.VARIABLES_SETTING,
                    delegatedEnvironment.evidence.joinToString(",") { it.name.value },
                )
            if (
                !(reloadInstallation &&
                    assignments.any {
                        it.parameter == ConfigurationParameter.GRADLE_IMPORT_PATH &&
                            it.source == ConfigurationSource.SAVED_INSTALLATION
                    })
            )
                put(
                    GradleImportEnvironment.PATH_SETTING,
                    delegatedEnvironment.executableDirectories.joinToString(":") { it.path.toString() },
                )
        }
    }

    /** Shadowed syntax-admitted inputs must also receive canonical owner admission; invalid inputs never disappear. */
    fun ownerCandidates(owner: ConfigurationOwner): List<ConfigurationOwnerCandidate> =
        suppliedCandidates
            .filter { it.parameter.owner == owner.module }
            .map { ConfigurationOwnerCandidate(it.parameter, it.source, it.value) }

    /** Selected owner inputs retain their original provenance in this configuration object. */
    fun ownerInputs(owner: ConfigurationOwner): Map<String, String> =
        assignments
            .filter { it.parameter.owner == owner.module && it.source != ConfigurationSource.DEFAULT }
            .associate { it.parameter.key to it.value.boundaryValue() }

    override fun toString(): String = "ResolvedKastConfiguration(keys=${assignments.map { it.parameter.key }})"

    companion object {
        fun resolve(sources: ConfigurationSources): Refinement<ResolvedKastConfiguration, ConfigurationRejection> {
            val admitted = linkedMapOf<ConfigurationParameter, MutableList<ResolvedAssignment>>()
            val orderedSources =
                listOf(
                    ConfigurationSource.COMMAND_LINE to sources.commandLine,
                    ConfigurationSource.PROCESS_ENVIRONMENT to sources.environment.entries.map { it.key to it.value },
                    ConfigurationSource.SAVED_WORKSPACE to sources.savedWorkspace,
                    ConfigurationSource.SAVED_INSTALLATION to sources.savedInstallation,
                )
            for ((source, inputs) in orderedSources) {
                val seen = mutableSetOf<ConfigurationParameter>()
                for ((rawKey, rawValue) in inputs) {
                    val parameter = KastConfigurationCatalogue.parameter(rawKey)
                    if (parameter == null) {
                        if (source == ConfigurationSource.PROCESS_ENVIRONMENT && !rawKey.startsWith("KAST_")) continue
                        return rejected(rawKey, ConfigurationFailure.UNKNOWN_KEY)
                    }
                    if (!seen.add(parameter)) return rejected(rawKey, ConfigurationFailure.DUPLICATE_ASSIGNMENT)
                    if (parameter.mutability == ConfigurationMutability.TEST_ONLY)
                        return rejected(rawKey, ConfigurationFailure.TEST_ONLY_INPUT)
                    if (parameter.mutability == ConfigurationMutability.BUILD_SETTING)
                        return rejected(rawKey, ConfigurationFailure.BUILD_ONLY_INPUT)
                    if (source !in parameter.declaration().sources)
                        return rejected(rawKey, ConfigurationFailure.UNSUPPORTED_SOURCE)
                    if (parameter == ConfigurationParameter.SAVED_CONFIGURATION_FAILURE)
                        return rejected(rawKey, ConfigurationFailure.SAVED_CONFIGURATION_REJECTED)
                    val value =
                        when (val parsed = parse(parameter, rawValue, source)) {
                            is Refinement.Refined -> parsed.value
                            is Refinement.Rejected -> return parsed
                        }
                    admitted
                        .getOrPut(parameter) { mutableListOf() }
                        .add(ResolvedAssignment(parameter, value, source, emptyList()))
                }
            }
            val resolved = mutableListOf<ResolvedAssignment>()
            for (parameter in ConfigurationParameter.entries) {
                val supplied = admitted[parameter].orEmpty()
                if (supplied.isNotEmpty()) {
                    resolved.add(supplied.first().copy(overriddenSources = supplied.drop(1).map { it.source }))
                } else {
                    val default = parameter.declaration().defaultValue ?: continue
                    val value =
                        when (val parsed = parse(parameter, default)) {
                            is Refinement.Refined -> parsed.value
                            is Refinement.Rejected -> return parsed
                        }
                    resolved.add(ResolvedAssignment(parameter, value, ConfigurationSource.DEFAULT, emptyList()))
                }
            }
            val byKey = resolved.associateBy { it.parameter }
            val heap = (byKey.getValue(ConfigurationParameter.INDEXER_MAX_HEAP).value as ConfigurationValue.Heap).value
            val launchd =
                (byKey.getValue(ConfigurationParameter.ENABLE_LAUNCHD).value as ConfigurationValue.Switch).value
            val debug = (byKey.getValue(ConfigurationParameter.DEBUG).value as ConfigurationValue.Switch).value
            val rawNames = byKey.getValue(ConfigurationParameter.GRADLE_IMPORT_VARIABLES).value.boundaryValue()
            val gradleHome = byKey[ConfigurationParameter.GRADLE_USER_HOME]?.value?.boundaryValue()
            val names =
                if (gradleHome == null) rawNames
                else listOf(rawNames, "GRADLE_USER_HOME").filter { it.isNotEmpty() }.joinToString(",")
            val environment =
                if (gradleHome == null) sources.environment
                else sources.environment + ("GRADLE_USER_HOME" to gradleHome)
            val delegated =
                when (
                    val parsed =
                        GradleImportEnvironment.admit(
                            names,
                            byKey.getValue(ConfigurationParameter.GRADLE_IMPORT_PATH).value.boundaryValue(),
                            environment,
                        )
                ) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return rejected(
                            GradleImportEnvironment.VARIABLES_SETTING,
                            ConfigurationFailure.DELEGATED_ENVIRONMENT_REJECTED,
                        )
                }
            fun count(key: ConfigurationParameter) = (byKey.getValue(key).value as ConfigurationValue.WorkerCount).value
            fun memory(key: ConfigurationParameter) = (byKey.getValue(key).value as ConfigurationValue.Memory).value
            val capacity =
                WorkerCapacityConfiguration(
                    count(ConfigurationParameter.WORKER_RESIDENT_LIMIT),
                    count(ConfigurationParameter.WORKER_STARTUP_LIMIT),
                    memory(ConfigurationParameter.WORKER_AGGREGATE_MIB),
                    memory(ConfigurationParameter.WORKER_NATIVE_MIB),
                    memory(ConfigurationParameter.WORKER_GRADLE_MIB),
                )
            if (capacity.startup.value > capacity.resident.value)
                return rejected(ConfigurationParameter.WORKER_STARTUP_LIMIT.key, ConfigurationFailure.INVALID_VALUE)
            val readLimits =
                when (
                    val policy =
                        ReadLimits.admit(resolved.mapNotNull { (it.value as? ConfigurationValue.ReadLimit)?.value })
                ) {
                    is Refinement.Refined -> policy.value
                    is Refinement.Rejected -> return rejected("KAST_READ_", ConfigurationFailure.INVALID_VALUE)
                }
            return Refinement.Refined(
                ResolvedKastConfiguration(
                    resolved.sortedBy { it.parameter.key },
                    admitted.values.flatten().toList(),
                    heap,
                    launchd,
                    debug,
                    capacity,
                    readLimits,
                    delegated,
                )
            )
        }
    }
}

private fun ConfigurationValue.boundaryValue(): String =
    when (this) {
        is ConfigurationValue.ReadLimit -> value.value.toString()
        is ConfigurationValue.WorkerCount -> value.value.toString()
        is ConfigurationValue.Memory -> value.value.toString()
        is ConfigurationValue.Heap -> "${value.mebibytes}m"
        is ConfigurationValue.Directory -> value.toString()
        is ConfigurationValue.Switch ->
            when (value) {
                ConfigurationSwitch.ENABLED -> "1"
                ConfigurationSwitch.DISABLED -> "0"
            }
        is ConfigurationValue.OwnerInput -> value
    }

private fun parse(
    parameter: ConfigurationParameter,
    raw: String,
    source: ConfigurationSource = ConfigurationSource.DEFAULT,
): Refinement<ConfigurationValue, ConfigurationRejection> {
    if (raw.length > 32 * 1024 || raw.any { it == '\u0000' || it == '\n' || it == '\r' })
        return rejected(parameter.key, ConfigurationFailure.INVALID_VALUE)
    val limit = parameter.readLimit
    if (limit != null)
        return when (
            val value =
                ReadLimitValue.admit(
                    limit,
                    raw,
                    when (source) {
                        ConfigurationSource.DEFAULT -> ReadLimitSource.DEFAULT
                        ConfigurationSource.COMMAND_LINE -> ReadLimitSource.COMMAND_LINE
                        ConfigurationSource.PROCESS_ENVIRONMENT -> ReadLimitSource.ENVIRONMENT
                        ConfigurationSource.JVM_PROPERTY -> ReadLimitSource.JVM_PROPERTY
                        ConfigurationSource.SAVED_WORKSPACE -> ReadLimitSource.SAVED_WORKSPACE
                        ConfigurationSource.SAVED_INSTALLATION -> ReadLimitSource.SAVED_INSTALLATION
                    },
                )
        ) {
            is Refinement.Refined -> Refinement.Refined(ConfigurationValue.ReadLimit(value.value))
            is Refinement.Rejected -> rejected(parameter.key, ConfigurationFailure.INVALID_VALUE)
        }
    return when (parameter.syntax) {
        ConfigurationSyntax.WORKER_COUNT ->
            when (val value = raw.toIntOrNull()?.let(WorkerCountLimit::admit)) {
                is Refinement.Refined -> Refinement.Refined(ConfigurationValue.WorkerCount(value.value))
                else -> rejected(parameter.key, ConfigurationFailure.INVALID_VALUE)
            }
        ConfigurationSyntax.MEMORY_MIB ->
            when (val value = raw.toLongOrNull()?.let(WorkerMemoryReservationMiB::admit)) {
                is Refinement.Refined -> Refinement.Refined(ConfigurationValue.Memory(value.value))
                else -> rejected(parameter.key, ConfigurationFailure.INVALID_VALUE)
            }
        ConfigurationSyntax.HEAP ->
            when (val heap = IndexerHeapSize.parse(raw)) {
                is Refinement.Refined -> Refinement.Refined(ConfigurationValue.Heap(heap.value))
                is Refinement.Rejected ->
                    Refinement.Rejected(
                        ConfigurationRejection(
                            parameter.key,
                            ConfigurationFailure.INVALID_HEAP,
                            ConfigurationRejectionDetail.Heap(heap.failure),
                        )
                    )
            }
        ConfigurationSyntax.SWITCH ->
            when (raw) {
                "0" -> Refinement.Refined(ConfigurationValue.Switch(ConfigurationSwitch.DISABLED))
                "1" -> Refinement.Refined(ConfigurationValue.Switch(ConfigurationSwitch.ENABLED))
                else -> rejected(parameter.key, ConfigurationFailure.INVALID_VALUE)
            }
        ConfigurationSyntax.ABSOLUTE_PATH -> {
            val path =
                try {
                    Path.of(raw)
                } catch (_: InvalidPathException) {
                    return rejected(parameter.key, ConfigurationFailure.INVALID_PATH)
                }
            if (raw.isBlank() || raw.length > 4096 || !path.isAbsolute || path.normalize() != path)
                rejected(parameter.key, ConfigurationFailure.INVALID_PATH)
            else Refinement.Refined(ConfigurationValue.Directory(path))
        }
        ConfigurationSyntax.OWNER_INPUT ->
            if (raw.isBlank()) rejected(parameter.key, ConfigurationFailure.INVALID_VALUE)
            else Refinement.Refined(ConfigurationValue.OwnerInput(raw))
        ConfigurationSyntax.VARIABLE_NAMES -> {
            val names = if (raw.isEmpty()) emptyList() else raw.split(',')
            if (
                names.size > 64 ||
                    names.toSet().size != names.size ||
                    names.any { GradleImportVariableName.parse(it) is Refinement.Rejected }
            )
                rejected(parameter.key, ConfigurationFailure.DELEGATED_ENVIRONMENT_REJECTED)
            else Refinement.Refined(ConfigurationValue.OwnerInput(raw))
        }
        ConfigurationSyntax.EXECUTABLE_PATH -> {
            val paths = if (raw.isEmpty()) emptyList() else raw.split(':')
            if (
                paths.size > 32 ||
                    paths.toSet().size != paths.size ||
                    paths.any { GradleImportExecutableDirectory.parse(it) is Refinement.Rejected }
            )
                rejected(parameter.key, ConfigurationFailure.DELEGATED_ENVIRONMENT_REJECTED)
            else Refinement.Refined(ConfigurationValue.OwnerInput(raw))
        }
        ConfigurationSyntax.FAILURE_MARKER -> rejected(parameter.key, ConfigurationFailure.SAVED_CONFIGURATION_REJECTED)
    }
}

private fun rejected(key: String, failure: ConfigurationFailure): Refinement.Rejected<ConfigurationRejection> =
    Refinement.Rejected(
        ConfigurationRejection(key.take(128).filter { it.isLetterOrDigit() || it == '_' || it == '.' }, failure)
    )
