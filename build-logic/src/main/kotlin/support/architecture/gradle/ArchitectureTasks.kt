package support.architecture.gradle

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
import support.architecture.ArchitectureReportFinding
import support.architecture.ArchitectureObservationParser
import support.architecture.ArchitectureObservationValidation
import support.architecture.ArchitecturePolicyValidation
import support.architecture.ArchitectureVerificationAdmission
import support.architecture.ArchitectureViolation
import support.architecture.BytecodeScanFailure
import support.architecture.BytecodeScanOutcome
import support.architecture.JvmEffectScanner
import support.architecture.KastArchitecturePolicy
import support.architecture.ObservedArchitecture
import support.architecture.ObservedProjectGraph
import support.architecture.ValidatedArchitecturePolicy
import support.architecture.AcceptedArchitectureVerification
import support.architecture.architectureFinding
import support.architecture.encodeArchitectureReport
import support.architecture.knowledge.ModuleKnowledgeProjection
import support.architecture.knowledge.ModuleKnowledgeProjectionResult
import support.architecture.knowledge.RawAgentGuide
import support.architecture.knowledge.RawModuleKnowledgeInput
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@CacheableTask
abstract class VerifyKastArchitectureTask : DefaultTask() {
    init {
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
        val effects = when (
            val scan = scanArchitectureEffects(
                policy,
                classDirectoryOwners.get(),
                rootDirectory.get().asFile.toPath(),
            )
        ) {
            is ArchitectureEffectScan.Scanned -> scan.effects
            is ArchitectureEffectScan.Rejected -> fail("BYTECODE_SCAN_FAILED", scan.findings)
        }
        when (
            val admission = AcceptedArchitectureVerification.establish(
                policy,
                graph,
                effects,
            )
        ) {
            is ArchitectureVerificationAdmission.Accepted -> writeReport(
                admission.evidence.reportBytes(),
            )
            is ArchitectureVerificationAdmission.Rejected -> fail(
                "REJECTED",
                admission.violations.map(::renderViolation).sortedBy(ArchitectureReportFinding::message),
            )
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
    ) = writeReport(encodeArchitectureReport(status, findings))

    private fun writeReport(encoded: ByteArray) {
        val target = reportFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.write(target, encoded)
    }

    companion object {
        const val CLASS_DIRECTORY_SEPARATOR: String = "|"
    }
}

@CacheableTask
abstract class GenerateKastModuleKnowledgeTask : DefaultTask() {
    init {
        observedProjectPaths.convention(emptyList())
        observedProjectDependencies.convention(emptyList())
        observedExportedProjectDependencies.convention(emptyList())
        observedModuleRoleConventions.convention(emptyList())
        classDirectoryOwners.convention(emptyList())
        agentGuidePaths.convention(emptyList())
    }

    @get:Input
    abstract val productVersion: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val sourceRevision: org.gradle.api.provider.Property<String>

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
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val architectureVerificationReport: RegularFileProperty

    @get:Input
    abstract val agentGuidePaths: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val agentGuideFiles: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val architecture = when (val policy = canonicalArchitecturePolicy()) {
            is ArchitecturePolicyValidation.Valid -> policy.architecture
            is ArchitecturePolicyValidation.Invalid -> throw GradleException(
                "Canonical Kast repository architecture policy is invalid: ${policy.failures}",
            )
        }
        val root = rootDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val graph = when (
            val parsed = ArchitectureObservationParser.parse(
                architecture,
                observedProjectPaths.get(),
                observedProjectDependencies.get(),
                observedExportedProjectDependencies.get(),
                observedModuleRoleConventions.get(),
            )
        ) {
            is ArchitectureObservationValidation.Valid -> parsed.graph
            is ArchitectureObservationValidation.Invalid -> throw GradleException(
                "Kast module knowledge observation rejected: ${parsed.failures}",
            )
        }
        val effects = when (
            val scan = scanArchitectureEffects(
                architecture,
                classDirectoryOwners.get(),
                root,
            )
        ) {
            is ArchitectureEffectScan.Scanned -> scan.effects
            is ArchitectureEffectScan.Rejected -> throw GradleException(
                "Kast module knowledge effect scan rejected: ${scan.findings}",
            )
        }
        val verification = when (
            val admission = AcceptedArchitectureVerification.establish(
                architecture,
                graph,
                effects,
            )
        ) {
            is ArchitectureVerificationAdmission.Accepted -> admission.evidence
            is ArchitectureVerificationAdmission.Rejected -> throw GradleException(
                "Kast module knowledge architecture rejected: ${admission.violations}",
            )
        }
        val publishedReport = Files.readAllBytes(architectureVerificationReport.get().asFile.toPath())
        if (!publishedReport.contentEquals(verification.reportBytes())) {
            throw GradleException(
                "Kast module knowledge report does not match the accepted typed architecture",
            )
        }
        val expectedGuideFiles = agentGuidePaths.get().map { relative ->
            val path = root.resolve(relative).normalize()
            if (
                !path.startsWith(root) ||
                Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                path.toRealPath() != path
            ) {
                throw GradleException("Tracked agent guide is not a canonical regular file: $relative")
            }
            path
        }
        val observedGuideFiles = agentGuideFiles.files.mapTo(linkedSetOf()) {
            it.toPath().toAbsolutePath().normalize()
        }
        if (observedGuideFiles != expectedGuideFiles.toSet()) {
            throw GradleException("Tracked agent-guide inputs do not match their Git identities")
        }
        val guides = expectedGuideFiles.map { path ->
            RawAgentGuide(
                relativePath = root.relativize(path).joinToString("/"),
                content = Files.readString(path),
            )
        }
        val result = ModuleKnowledgeProjection.render(
            RawModuleKnowledgeInput(
                productVersion = productVersion.get(),
                sourceRevision = sourceRevision.get(),
                architectureVerification = verification,
                agentGuides = guides,
            ),
        )
        val encoded = when (result) {
            is ModuleKnowledgeProjectionResult.Complete -> result.encoded
            is ModuleKnowledgeProjectionResult.Rejected -> throw GradleException(
                "Kast module knowledge generation rejected: " +
                    result.failures.joinToString(),
            )
        }
        writeAtomically(outputFile.get().asFile.toPath(), encoded)
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, content)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private sealed interface ArchitectureEffectScan {
    data class Scanned(val effects: Set<support.architecture.EffectObservation>) :
        ArchitectureEffectScan

    data class Rejected(val findings: List<ArchitectureReportFinding>) :
        ArchitectureEffectScan
}

private fun scanArchitectureEffects(
    policy: ValidatedArchitecturePolicy,
    rawDirectoryOwners: List<String>,
    rootDirectory: Path,
): ArchitectureEffectScan {
    val grouped = linkedMapOf<String, MutableList<Path>>()
    for (notation in rawDirectoryOwners) {
        val parts = notation.split(VerifyKastArchitectureTask.CLASS_DIRECTORY_SEPARATOR, limit = 2)
        if (parts.size != 2) {
            return ArchitectureEffectScan.Rejected(
                listOf(finding("INVALID_CLASS_DIRECTORY", notation)),
            )
        }
        grouped.getOrPut(parts[0]) { mutableListOf() }
            .add(rootDirectory.resolve(parts[1]))
    }
    val effects = linkedSetOf<support.architecture.EffectObservation>()
    for ((projectPath, directories) in grouped) {
        val module = policy.modules.values.singleOrNull { it.id.projectPath == projectPath }
            ?: return ArchitectureEffectScan.Rejected(
                listOf(finding("INVALID_CLASS_DIRECTORY_OWNER", projectPath)),
            )
        val classFiles = directories.flatMap(::classFiles)
        when (val scan = JvmEffectScanner.scan(module, classFiles)) {
            is BytecodeScanOutcome.Scanned -> effects += scan.effects()
            is BytecodeScanOutcome.Failed -> return ArchitectureEffectScan.Rejected(
                scan.failures().map(::renderScanFailure),
            )
        }
    }
    return ArchitectureEffectScan.Scanned(effects)
}

private fun classFiles(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.walk(directory).use { paths ->
        paths.filter(Files::isRegularFile)
            .filter { it.fileName.toString().endsWith(".class") }
            .sorted()
            .toList()
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

private fun finding(
    code: String,
    message: String,
    vararg attributes: Pair<String, String>,
): ArchitectureReportFinding = architectureFinding(code, message, *attributes)
