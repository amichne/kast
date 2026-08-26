package support.architecture.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import support.architecture.ArchitectureAdmission
import support.architecture.ArchitectureObservationParser
import support.architecture.ArchitectureObservationValidation
import support.architecture.ArchitecturePolicyValidation
import support.architecture.ArchitectureViolation
import support.architecture.BytecodeScanFailure
import support.architecture.BytecodeScanOutcome
import support.architecture.JvmEffectScanner
import support.architecture.KastArchitecturePolicy
import support.architecture.ObservedArchitecture
import support.architecture.ValidatedArchitecturePolicy
import support.architecture.projection.ArchitectureProjection
import java.nio.file.Files
import java.nio.file.Path

@CacheableTask
abstract class GenerateKastArchitectureProjectionTask : DefaultTask() {
    @get:OutputFile
    abstract val projectionFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val architecture = when (val policy = canonicalArchitecturePolicy()) {
            is ArchitecturePolicyValidation.Valid -> policy.architecture
            is ArchitecturePolicyValidation.Invalid -> throw GradleException(
                "Canonical Kast repository architecture policy is invalid: ${policy.failures}",
            )
        }
        val target = projectionFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.writeString(target, ArchitectureProjection.render(architecture))
    }
}

@CacheableTask
abstract class VerifyKastArchitectureTask : DefaultTask() {
    init {
        mustRunAfter("generateKastArchitectureProjection")
        observedProjectPaths.convention(emptyList())
        observedProjectDependencies.convention(emptyList())
        observedExportedProjectDependencies.convention(emptyList())
        observedModuleRoleConventions.convention(emptyList())
        classDirectoryOwners.convention(emptyList())
    }

    @get:Input
    abstract val observedProjectPaths: ListProperty<String>

    @get:Input
    abstract val observedProjectDependencies: ListProperty<String>

    @get:Input
    abstract val observedExportedProjectDependencies: ListProperty<String>

    @get:Input
    abstract val observedModuleRoleConventions: ListProperty<String>

    @get:Input
    abstract val classDirectoryOwners: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledClassDirectories: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectionFile: RegularFileProperty

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val canonical = when (val policy = canonicalArchitecturePolicy()) {
            is ArchitecturePolicyValidation.Valid -> policy.architecture
            is ArchitecturePolicyValidation.Invalid -> throw GradleException(
                "Canonical Kast repository architecture policy is invalid: ${policy.failures}",
            )
        }
        verifyProjection(canonical)
        val policy = canonical
        val graph = when (
            val parsed = ArchitectureObservationParser.parse(
                policy,
                observedProjectPaths.get(),
                observedProjectDependencies.get(),
                observedExportedProjectDependencies.get(),
                observedModuleRoleConventions.get(),
            )
        ) {
            is ArchitectureObservationValidation.Valid -> parsed.graph
            is ArchitectureObservationValidation.Invalid -> fail(
                "INVALID_OBSERVATION",
                parsed.failures.map { finding("INVALID_OBSERVATION", it.toString()) },
            )
        }
        val effects = scanEffects(policy)
        when (
            val admission = ArchitectureAdmission.evaluate(
                policy,
                ObservedArchitecture(
                    graph.modules,
                    graph.projectDependencies,
                    effects,
                    graph.exportedProjectDependencies,
                    graph.moduleRoleConventions,
                ),
            )
        ) {
            ArchitectureAdmission.Accepted -> writeReport("ACCEPTED", emptyList())
            is ArchitectureAdmission.Rejected -> fail(
                "REJECTED",
                admission.violations.map(::renderViolation).sortedBy(ArchitectureReportFinding::message),
            )
        }
    }

    private fun verifyProjection(policy: ValidatedArchitecturePolicy) {
        val projection = projectionFile.get().asFile.toPath()
        val expected = ArchitectureProjection.render(policy)
        val observed = if (Files.isRegularFile(projection)) Files.readString(projection) else ""
        if (observed != expected) {
            fail(
                "PROJECTION_DRIFT",
                listOf(
                    finding(
                        "PROJECTION_DRIFT",
                        "Run ./gradlew generateKastArchitectureProjection and commit the result.",
                    ),
                ),
            )
        }
    }

    private fun scanEffects(policy: ValidatedArchitecturePolicy) =
        classDirectoryOwners.get().groupByOwner().flatMapTo(linkedSetOf()) { (projectPath, directories) ->
            val module = policy.modules.values.single { it.id.projectPath == projectPath }
            val classFiles = directories.flatMap(::classFiles)
            when (val scan = JvmEffectScanner.scan(module, classFiles)) {
                is BytecodeScanOutcome.Scanned -> scan.effects()
                is BytecodeScanOutcome.Failed -> fail(
                    "BYTECODE_SCAN_FAILED",
                    scan.failures().map(::renderScanFailure),
                )
            }
        }

    private fun List<String>.groupByOwner(): Map<String, List<Path>> =
        map { notation ->
            val parts = notation.split(CLASS_DIRECTORY_SEPARATOR, limit = 2)
            if (parts.size != 2) {
                fail(
                    "INVALID_CLASS_DIRECTORY",
                    listOf(finding("INVALID_CLASS_DIRECTORY", notation)),
                )
            }
            parts[0] to rootDirectory.get().asFile.toPath().resolve(parts[1])
        }.groupBy({ it.first }, { it.second })

    private fun classFiles(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(".class") }
                .sorted()
                .toList()
        }
    }

    private fun fail(
        status: String,
        findings: List<ArchitectureReportFinding>,
    ): Nothing {
        writeReport(status, findings)
        throw GradleException(
            buildString {
                append("Kast architecture verification ").append(status).append(':')
                findings.forEach { append("\n - ").append(it.code).append(' ').append(it.message) }
            },
        )
    }

    private fun writeReport(
        status: String,
        findings: List<ArchitectureReportFinding>,
    ) {
        val target = reportFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            architectureReportJson.encodeToString(
                ArchitectureReportDocument.serializer(),
                ArchitectureReportDocument(
                    schemaVersion = 1,
                    status = status,
                    findings = findings,
                ),
            ) + "\n",
        )
    }

    companion object {
        const val CLASS_DIRECTORY_SEPARATOR: String = "|"
    }
}

/**
 * Proof transition: canonical `ArchitecturePolicyDefinition -> ArchitecturePolicyValidation`.
 *
 * Returns either the complete validated graph or its finite policy failures. Rendering is
 * permitted only in an owning Gradle task action.
 */
internal fun canonicalArchitecturePolicy(): ArchitecturePolicyValidation =
    KastArchitecturePolicy.validate()

private fun renderViolation(violation: ArchitectureViolation): ArchitectureReportFinding = when (violation) {
    is ArchitectureViolation.ActiveModuleMissing -> finding(
        "ACTIVE_MODULE_MISSING",
        violation.module.projectPath,
        "module" to violation.module.projectPath,
    )
    is ArchitectureViolation.PlannedModuleMaterialized -> finding(
        "PLANNED_MODULE_MATERIALIZED",
        violation.module.projectPath,
        "module" to violation.module.projectPath,
    )
    is ArchitectureViolation.RetiredModulePresent -> finding(
        "RETIRED_MODULE_PRESENT",
        violation.module.projectPath,
        "module" to violation.module.projectPath,
    )
    is ArchitectureViolation.ForbiddenExportedProjectDependency -> finding(
        "FORBIDDEN_EXPORTED_PROJECT_DEPENDENCY",
        "${violation.dependency.consumer.projectPath} -> ${violation.dependency.dependency.projectPath}",
        "consumer" to violation.dependency.consumer.projectPath,
        "dependency" to violation.dependency.dependency.projectPath,
    )
    is ArchitectureViolation.MissingModuleRoleConvention -> finding(
        "MISSING_MODULE_ROLE_CONVENTION",
        violation.module.projectPath,
        "module" to violation.module.projectPath,
        "expectedPlugin" to violation.expected.pluginId,
    )
    is ArchitectureViolation.UnexpectedModuleRoleConvention -> finding(
        "UNEXPECTED_MODULE_ROLE_CONVENTION",
        violation.module.projectPath,
        "module" to violation.module.projectPath,
        "observedPlugin" to violation.observed.pluginId,
    )
    is ArchitectureViolation.MismatchedModuleRoleConvention -> finding(
        "MISMATCHED_MODULE_ROLE_CONVENTION",
        violation.module.projectPath,
        "module" to violation.module.projectPath,
        "expectedPlugin" to violation.expected.pluginId,
        "observedPlugin" to violation.observed.pluginId,
    )
    is ArchitectureViolation.UnapprovedProjectDependency -> finding(
        "UNAPPROVED_PROJECT_DEPENDENCY",
        "${violation.dependency.consumer.projectPath} -> ${violation.dependency.dependency.projectPath}",
        "consumer" to violation.dependency.consumer.projectPath,
        "dependency" to violation.dependency.dependency.projectPath,
    )
    is ArchitectureViolation.ForbiddenEffectUse -> with(violation.observation) {
        finding(
            "FORBIDDEN_EFFECT",
            "${module.projectPath} ${effect.name} " +
                "${caller.owner.internalName}.${caller.name.value}${caller.descriptor.value} -> " +
                "${target.owner.internalName}.${target.name.value}${target.descriptor.value}",
            "module" to module.projectPath,
            "effect" to effect.name,
            "callerOwner" to caller.owner.internalName,
            "callerName" to caller.name.value,
            "callerDescriptor" to caller.descriptor.value,
            "targetOwner" to target.owner.internalName,
            "targetName" to target.name.value,
            "targetDescriptor" to target.descriptor.value,
        )
    }
}

private fun renderScanFailure(failure: BytecodeScanFailure): ArchitectureReportFinding = when (failure) {
    is BytecodeScanFailure.InvalidClassIdentity -> finding(
        "INVALID_CLASS_IDENTITY",
        failure.relativeName,
        "relativeName" to failure.relativeName,
    )
    is BytecodeScanFailure.MalformedClass -> finding(
        "MALFORMED_CLASS",
        failure.path.toString(),
        "path" to failure.path.toString(),
    )
    is BytecodeScanFailure.UnreadableClass -> finding(
        "UNREADABLE_CLASS",
        failure.path.toString(),
        "path" to failure.path.toString(),
    )
}

@Serializable
private data class ArchitectureReportDocument(
    val schemaVersion: Int,
    val status: String,
    val findings: List<ArchitectureReportFinding>,
)

@Serializable
private data class ArchitectureReportFinding(
    val code: String,
    val message: String,
    val attributes: Map<String, String>,
)

private fun finding(
    code: String,
    message: String,
    vararg attributes: Pair<String, String>,
): ArchitectureReportFinding = ArchitectureReportFinding(
    code = code,
    message = message,
    attributes = mapOf(*attributes).toSortedMap(),
)

private val architectureReportJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}
