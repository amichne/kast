package support.architecture
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
internal data class RawHostedReadClassArtifact(val relativeName: String, val sha256: String)
internal class HostedReadClassArtifact private constructor(
    val relativeName: String,
    val sha256: String,
) {
    internal companion object {
        /**
         * Proof transition: `RawHostedReadClassArtifact -> HostedReadClassArtifactRefinement`.
         *
         * Establishes one canonical relative class identity and SHA-256 digest. Invalid names and
         * digests remain closed [HostedReadInventoryFailure] data. Raw strings enter only from the
         * compiled-class Gradle boundary.
         */
        fun refine(raw: RawHostedReadClassArtifact): HostedReadClassArtifactRefinement {
            val failures = buildList {
                if (!(
                        raw.relativeName.endsWith(".class") &&
                            !raw.relativeName.startsWith('/') &&
                            !raw.relativeName.contains('\\') &&
                            raw.relativeName.split('/').all { segment ->
                                segment.isNotEmpty() && segment != "." && segment != ".." &&
                                    segment.all {
                                        it.isLetterOrDigit() || it in setOf('_', '$', '.', '-')
                                    }
                            }
                        )
                ) {
                    add(HostedReadInventoryFailure.InvalidClassName(raw.relativeName))
                }
                if (!(raw.sha256.length == 64 && raw.sha256.all {
                        it in '0'..'9' || it in 'a'..'f'
                    })
                ) {
                    add(HostedReadInventoryFailure.InvalidClassDigest(
                        raw.relativeName,
                        raw.sha256,
                    ))
                }
            }
            return if (failures.isEmpty()) HostedReadClassArtifactRefinement.Refined(
                HostedReadClassArtifact(raw.relativeName, raw.sha256),
            ) else HostedReadClassArtifactRefinement.Rejected(
                failures.first(),
                failures.drop(1),
            )
        }
    }
}
internal sealed interface HostedReadClassArtifactRefinement {
    data class Refined(val artifact: HostedReadClassArtifact) :
        HostedReadClassArtifactRefinement
    data class Rejected(
        val first: HostedReadInventoryFailure,
        val additional: List<HostedReadInventoryFailure>,
    ) :
        HostedReadClassArtifactRefinement
}
@JvmInline
internal value class HostedReadClassSetDigest private constructor(val value: String) {
    internal companion object {
        /**
         * Proof transition: `List<HostedReadClassArtifact> -> HostedReadClassSetDigest`.
         *
         * Preserves the canonical identity and digest of every already refined class in one
         * deterministic SHA-256. Canonical bytes leave only at this digest boundary.
         */
        fun derive(classes: List<HostedReadClassArtifact>): HostedReadClassSetDigest {
            val canonicalBytes = classes.joinToString(separator = "") { artifact ->
                "${artifact.relativeName}:${artifact.sha256}\n"
            }.toByteArray(StandardCharsets.UTF_8)
            return HostedReadClassSetDigest(canonicalBytes.sha256())
        }
    }
}
internal class HostedReadClassInventory private constructor(
    classes: List<HostedReadClassArtifact>,
    val digest: HostedReadClassSetDigest,
) {
    private val admittedClasses = classes.toList()
    internal fun classes(): List<HostedReadClassArtifact> = admittedClasses.toList()
    internal companion object {
        /**
         * Proof transition: `(List<RawHostedReadClassArtifact>, Set<String>) ->
         * HostedReadInventoryRefinement`.
         *
         * Establishes a nonempty, uniquely named, complete required-class inventory with
         * canonical SHA-256 class digests and one deterministic class-set digest. Expected
         * malformed, duplicate, empty, and incomplete inputs remain closed
         * [HostedReadInventoryFailure] values. Raw paths and class bytes may be extracted only by
         * the Gradle task boundary before this transition.
         */
        fun refine(
            rawArtifacts: List<RawHostedReadClassArtifact>,
            requiredClassNames: Set<String>,
        ): HostedReadInventoryRefinement {
            val failures = mutableListOf<HostedReadInventoryFailure>()
            if (rawArtifacts.isEmpty()) failures += HostedReadInventoryFailure.Empty
            val artifacts = rawArtifacts.mapNotNull { raw ->
                when (val result = HostedReadClassArtifact.refine(raw)) {
                    is HostedReadClassArtifactRefinement.Refined -> result.artifact
                    is HostedReadClassArtifactRefinement.Rejected -> {
                        failures += listOf(result.first) + result.additional
                        null
                    }
                }
            }
            rawArtifacts.groupingBy(RawHostedReadClassArtifact::relativeName).eachCount()
                .filterValues { it != 1 }
                .keys
                .sorted()
                .forEach { failures += HostedReadInventoryFailure.DuplicateClass(it) }
            val observedNames = rawArtifacts.mapTo(
                linkedSetOf(),
                RawHostedReadClassArtifact::relativeName,
            )
            requiredClassNames.sorted().filterNot(observedNames::contains).forEach {
                failures += HostedReadInventoryFailure.MissingRequiredClass(it)
            }
            if (failures.isNotEmpty()) {
                return HostedReadInventoryRefinement.Rejected(failures.first(), failures.drop(1))
            }
            val classes = artifacts.sortedBy(HostedReadClassArtifact::relativeName)
            return HostedReadInventoryRefinement.Refined(
                HostedReadClassInventory(classes, HostedReadClassSetDigest.derive(classes)),
            )
        }
    }
}
internal sealed interface HostedReadInventoryFailure {
    data object Empty : HostedReadInventoryFailure
    data class InvalidClassName(val observed: String) : HostedReadInventoryFailure
    data class InvalidClassDigest(val className: String, val observed: String) : HostedReadInventoryFailure
    data class DuplicateClass(val className: String) : HostedReadInventoryFailure
    data class MissingRequiredClass(val className: String) : HostedReadInventoryFailure
}
internal sealed interface HostedReadInventoryRefinement {
    data class Refined(val inventory: HostedReadClassInventory) : HostedReadInventoryRefinement
    data class Rejected(
        val first: HostedReadInventoryFailure,
        val additional: List<HostedReadInventoryFailure>,
    ) : HostedReadInventoryRefinement
}
internal data class HostedReadEffectCount(
    val effect: ForbiddenEffect,
    val count: Int,
)
internal class VfsPassiveHostedModelCapture private constructor(
    val inventory: HostedReadClassInventory,
    val classpathScanPolicy: HostedReadClasspathScanPolicy,
    prohibitedEffectCounts: List<HostedReadEffectCount>,
    val allowedIntellijReferenceCount: Int,
    val projectClasspath: HostedReadProjectClasspath,
    val externalClasspath: HostedReadExternalClasspath,
) {
    private val admittedEffectCounts = prohibitedEffectCounts.toList()
    internal fun prohibitedEffectCounts(): List<HostedReadEffectCount> = admittedEffectCounts.toList()
    internal companion object {
        /**
         * Proof transition: `(ValidatedModulePolicy, HostedReadClassInventory,
         * Set<EffectObservation>, Set<ModuleId>, HostedReadProjectClasspath,
         * HostedReadExternalClasspath) -> HostedReadPathAdmission`.
         *
         * Establishes that the complete `:workspace:intellij-read` inventory exposes only the
         * generic IntelliJ platform read effect, carries zero observations for every stronger
         * finite effect, has the exact project closure, and retains the exact external-classpath
         * proof. Expected policy, path, and observation gaps remain closed [HostedReadPathFailure]
         * values. Raw JVM observations may leave only at the generated report boundary.
         */
        fun admit(
            module: ValidatedModulePolicy,
            inventory: HostedReadClassInventory,
            observations: Set<EffectObservation>,
            expectedRuntimeProjects: Set<ModuleId>,
            projectClasspath: HostedReadProjectClasspath,
            externalClasspath: HostedReadExternalClasspath,
        ): HostedReadPathAdmission {
            val failures = mutableListOf<HostedReadPathFailure>()
            if (module.id != ModuleId.WORKSPACE_INTELLIJ_READ) {
                failures += HostedReadPathFailure.ModuleMismatch(module.id)
            }
            if (module.role != ModuleRole.IDE_READ_ONLY) {
                failures += HostedReadPathFailure.RoleMismatch(module.role)
            }
            val expectedAllowed = setOf(ForbiddenEffect.INTELLIJ_PLATFORM)
            if (module.allowedEffects != expectedAllowed) {
                failures += HostedReadPathFailure.AllowedEffectsMismatch(module.allowedEffects)
            }
            if (projectClasspath.projectPaths().toSet() != expectedRuntimeProjects) {
                failures += HostedReadPathFailure.RuntimeProjectPathsMismatch(
                    projectClasspath.projectPaths().toSet(),
                )
            }
            val classpathScanPolicy = HostedReadClasspathScanPolicy.refine(
                module,
                projectClasspath.scanPolicy,
                externalClasspath.scanPolicy,
            )
            if (classpathScanPolicy is HostedReadClasspathPolicyRefinement.Rejected) {
                failures += HostedReadPathFailure.ClasspathScanPolicyMismatch(
                    classpathScanPolicy.failure,
                )
            }
            observations.filter { it.module != module.id }.forEach {
                failures += HostedReadPathFailure.ForeignObservation(it)
            }
            val prohibitedEffects = ForbiddenEffect.entries
                .filterNot { it == ForbiddenEffect.INTELLIJ_PLATFORM }
            observations.filter { it.effect in prohibitedEffects }.forEach {
                failures += HostedReadPathFailure.ForbiddenObservation(it)
            }
            if (failures.isNotEmpty()) {
                return HostedReadPathAdmission.Rejected(failures.first(), failures.drop(1))
            }
            val counts = prohibitedEffects.map { HostedReadEffectCount(it, 0) }
            return when (classpathScanPolicy) {
                is HostedReadClasspathPolicyRefinement.Admitted ->
                    HostedReadPathAdmission.Admitted(
                        VfsPassiveHostedModelCapture(
                            inventory,
                            classpathScanPolicy.proof,
                            counts,
                            observations.count { it.effect == ForbiddenEffect.INTELLIJ_PLATFORM },
                            projectClasspath,
                            externalClasspath,
                        ),
                    )
                is HostedReadClasspathPolicyRefinement.Rejected ->
                    HostedReadPathAdmission.Rejected(
                        HostedReadPathFailure.ClasspathScanPolicyMismatch(classpathScanPolicy.failure),
                        emptyList(),
                    )
            }
        }
    }
}
internal sealed interface HostedReadPathFailure {
    data class ModuleMismatch(val observed: ModuleId) : HostedReadPathFailure
    data class RoleMismatch(val observed: ModuleRole) : HostedReadPathFailure
    data class AllowedEffectsMismatch(val observed: Set<ForbiddenEffect>) : HostedReadPathFailure
    data class RuntimeProjectPathsMismatch(val observed: Set<ModuleId>) : HostedReadPathFailure
    data class ClasspathScanPolicyMismatch(val failure: HostedReadClasspathPolicyFailure) :
        HostedReadPathFailure
    data class ForeignObservation(val observation: EffectObservation) : HostedReadPathFailure
    data class ForbiddenObservation(val observation: EffectObservation) : HostedReadPathFailure
}
internal sealed interface HostedReadPathAdmission {
    data class Admitted(val proof: VfsPassiveHostedModelCapture) : HostedReadPathAdmission
    data class Rejected(
        val first: HostedReadPathFailure,
        val additional: List<HostedReadPathFailure>,
    ) : HostedReadPathAdmission
}
internal sealed interface HostedReadPathDerivation {
    data class Derived(val proof: VfsPassiveHostedModelCapture) : HostedReadPathDerivation
    sealed interface Rejected : HostedReadPathDerivation
    data class ModuleUnavailable(val module: ModuleId) : Rejected
    data class InventoryRejected(
        val first: HostedReadInventoryFailure,
        val additional: List<HostedReadInventoryFailure>,
    ) : Rejected
    data class ScanRejected(val failures: List<BytecodeScanFailure>) : Rejected
    data class ExternalClasspathRejected(
        val first: HostedReadExternalClasspathFailure,
        val additional: List<HostedReadExternalClasspathFailure>,
    ) : Rejected
    data class ProjectClasspathRejected(
        val first: HostedReadProjectClasspathFailure,
        val additional: List<HostedReadProjectClasspathFailure>,
    ) : Rejected
    data class AdmissionRejected(
        val first: HostedReadPathFailure,
        val additional: List<HostedReadPathFailure>,
    ) : Rejected
}
internal object HostedReadPathDeriver {
    /**
     * Proof transition: `(ValidatedArchitecturePolicy, List<HostedReadClassBytes>, Set<String>,
     * List<HostedReadProjectJarBytes>, List<HostedReadExternalJarBytes>) ->
     * HostedReadPathDerivation`.
     *
     * Establishes that one immutable byte inventory supplies both every class digest and every ASM
     * effect observation, that the resolved project classpath is exactly the canonical hosted
     * dependency closure, and that the separately admitted external runtime artifacts match their
     * exact byte identities with zero stronger architecture effects. Expected module, inventory,
     * bytecode, project-classpath, external-classpath, and policy gaps remain closed derivation
     * variants. Gradle class names, artifact identities, and bytes enter only here; admitted report
     * primitives may leave only at the report boundary.
     */
    fun derive(
        architecture: ValidatedArchitecturePolicy,
        classes: List<HostedReadClassBytes>,
        requiredClassNames: Set<String>,
        runtimeProjectJars: List<HostedReadProjectJarBytes>,
        runtimeExternalJars: List<HostedReadExternalJarBytes>,
    ): HostedReadPathDerivation {
        val module = architecture.modules[ModuleId.WORKSPACE_INTELLIJ_READ]
            ?: return HostedReadPathDerivation.ModuleUnavailable(ModuleId.WORKSPACE_INTELLIJ_READ)
        val rawArtifacts = classes.map { artifact ->
            RawHostedReadClassArtifact(artifact.relativeName, artifact.useBytes(ByteArray::sha256))
        }
        val inventory = when (val result = HostedReadClassInventory.refine(
            rawArtifacts,
            requiredClassNames,
        )) {
            is HostedReadInventoryRefinement.Refined -> result.inventory
            is HostedReadInventoryRefinement.Rejected ->
                return HostedReadPathDerivation.InventoryRejected(result.first, result.additional)
        }
        val observations = when (val result = JvmEffectScanner.scanBytes(module, classes)) {
            is BytecodeScanOutcome.Scanned -> result.effects()
            is BytecodeScanOutcome.Failed ->
                return HostedReadPathDerivation.ScanRejected(result.failures())
        }
        val expectedRuntimeProjects = architecture.transitiveProjects(module.id)
        val projectClasspath = when (val result = HostedReadProjectClasspath.refine(
            module,
            runtimeProjectJars,
            expectedRuntimeProjects,
        )) {
            is HostedReadProjectClasspathRefinement.Admitted -> result.classpath
            is HostedReadProjectClasspathRefinement.Rejected ->
                return HostedReadPathDerivation.ProjectClasspathRejected(
                    result.first,
                    result.additional,
                )
        }
        val externalClasspath = when (val result = HostedReadExternalClasspath.refine(
            module,
            runtimeExternalJars,
        )) {
            is HostedReadExternalClasspathRefinement.Admitted -> result.classpath
            is HostedReadExternalClasspathRefinement.Rejected ->
                return HostedReadPathDerivation.ExternalClasspathRejected(
                    result.first,
                    result.additional,
                )
        }
        return when (val result = HostedReadPathAdmissionPolicy.admit(
            module,
            inventory,
            observations,
            expectedRuntimeProjects,
            projectClasspath,
            externalClasspath,
        )) {
            is HostedReadPathAdmission.Admitted -> HostedReadPathDerivation.Derived(result.proof)
            is HostedReadPathAdmission.Rejected ->
                HostedReadPathDerivation.AdmissionRejected(result.first, result.additional)
        }
    }
}
internal object HostedReadPathAdmissionPolicy {
    /**
     * Proof transition: `(ValidatedModulePolicy, HostedReadClassInventory,
     * Set<EffectObservation>, Set<ModuleId>, HostedReadProjectClasspath,
     * HostedReadExternalClasspath) -> HostedReadPathAdmission`.
     *
     * Establishes that the complete `:workspace:intellij-read` inventory exposes only the generic
     * IntelliJ platform read effect and carries zero observations for every stronger finite effect.
     * Project paths refine to [ModuleId] and external artifacts retain their exact identity and
     * all-zero stronger-effect proof before entering the capture. Expected module, role,
     * dependency, effect-policy, foreign-observation, and forbidden-observation gaps remain closed
     * [HostedReadPathFailure] values. Raw paths and JVM references may be extracted only at the
     * Gradle boundary.
     */
    fun admit(
        module: ValidatedModulePolicy,
        inventory: HostedReadClassInventory,
        observations: Set<EffectObservation>,
        expectedRuntimeProjects: Set<ModuleId>,
        projectClasspath: HostedReadProjectClasspath,
        externalClasspath: HostedReadExternalClasspath,
    ): HostedReadPathAdmission = VfsPassiveHostedModelCapture.admit(
        module,
        inventory,
        observations,
        expectedRuntimeProjects,
        projectClasspath,
        externalClasspath,
    )
}
private fun ValidatedArchitecturePolicy.transitiveProjects(root: ModuleId): Set<ModuleId> {
    val admitted = linkedSetOf<ModuleId>()
    fun visit(module: ModuleId) {
        modules.getValue(module).allowedProjectDependencies.sortedBy(ModuleId::projectPath)
            .forEach { dependency -> if (admitted.add(dependency)) visit(dependency) }
    }
    visit(root)
    return admitted
}
private fun ByteArray.sha256(): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(this),
)
