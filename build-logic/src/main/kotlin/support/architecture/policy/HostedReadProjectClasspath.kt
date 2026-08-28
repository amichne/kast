package support.architecture

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarInputStream

internal class HostedReadProjectJarBytes private constructor(
    val projectPath: String,
    val artifactName: String,
    bytes: ByteArray,
) {
    private val snapshot = bytes.copyOf()

    internal fun <T> useBytes(block: (ByteArray) -> T): T = block(snapshot.copyOf())

    internal companion object {
        /**
         * Proof transition: `(String, String, ByteArray) -> HostedReadProjectJarBytes`.
         *
         * Captures an immutable project-artifact observation. Mutable bytes are copied at entry
         * and leave only as a copy at archive, digest, or scanner boundaries.
         */
        fun capture(projectPath: String, artifactName: String, bytes: ByteArray) =
            HostedReadProjectJarBytes(projectPath, artifactName, bytes)
    }
}

internal sealed interface HostedReadProjectClasspathFailure {
    data class ArtifactSetMismatch(val observed: List<String>) :
        HostedReadProjectClasspathFailure
    data class UnknownProject(val observed: String) : HostedReadProjectClasspathFailure
    data class DuplicateProject(val observed: String) : HostedReadProjectClasspathFailure
    data class MalformedArchive(val projectPath: String) : HostedReadProjectClasspathFailure
    data class EmptyArchive(val projectPath: String) : HostedReadProjectClasspathFailure
    data class DuplicateClass(val projectPath: String, val className: String) :
        HostedReadProjectClasspathFailure
    data class MalformedClass(val failures: List<BytecodeScanFailure>) :
        HostedReadProjectClasspathFailure
    data class ForbiddenObservation(val observation: EffectObservation) :
        HostedReadProjectClasspathFailure
}

internal sealed interface HostedReadProjectClasspathRefinement {
    data class Admitted(val classpath: HostedReadProjectClasspath) :
        HostedReadProjectClasspathRefinement
    data class Rejected(
        val first: HostedReadProjectClasspathFailure,
        val additional: List<HostedReadProjectClasspathFailure>,
    ) : HostedReadProjectClasspathRefinement
}

internal data class HostedReadProjectArtifactObservation(
    val project: ModuleId,
    val artifactName: String,
    val sha256: String,
    val classCount: Int,
)

internal class HostedReadProjectClasspath private constructor(
    val scanPolicy: HostedReadClasspathScanPolicy,
    artifacts: List<Artifact>,
    val artifactSetSha256: String,
    prohibitedEffectCounts: List<HostedReadEffectCount>,
) {
    private val admittedArtifacts = artifacts.toList()
    private val admittedEffectCounts = prohibitedEffectCounts.toList()

    val classCount: Int = admittedArtifacts.sumOf(Artifact::classCount)

    internal fun artifacts(): List<HostedReadProjectArtifactObservation> = admittedArtifacts.map {
        HostedReadProjectArtifactObservation(it.project, it.artifactName, it.sha256, it.classCount)
    }

    internal fun projectPaths(): List<ModuleId> = admittedArtifacts.map(Artifact::project)

    internal fun prohibitedEffectCounts(): List<HostedReadEffectCount> =
        admittedEffectCounts.toList()

    private data class Artifact(
        val project: ModuleId,
        val artifactName: String,
        val sha256: String,
        val classCount: Int,
    )

    internal companion object {
        /**
         * Proof transition: `(ValidatedModulePolicy, List<HostedReadProjectJarBytes>,
         * Set<ModuleId>) -> HostedReadProjectClasspathRefinement`.
         *
         * Establishes exactly one immutable compiled JAR for every transitive runtime project,
         * one deterministic artifact-set digest, a nonempty class inventory, and zero
         * KVP-018/REQ-014/REQ-016 forbidden effects across the hosted read classpath.
         * Unknown, duplicate, missing, malformed, or forbidden inputs remain closed
         * [HostedReadProjectClasspathFailure] data. Raw project paths and JAR bytes enter only at
         * this classpath boundary.
         */
        fun refine(
            module: ValidatedModulePolicy,
            jars: List<HostedReadProjectJarBytes>,
            expectedProjects: Set<ModuleId>,
        ): HostedReadProjectClasspathRefinement {
            val modulesByPath = ModuleId.entries.associateBy(ModuleId::projectPath)
            val failures = mutableListOf<HostedReadProjectClasspathFailure>()
            val observedProjects = jars.mapNotNull { jar ->
                modulesByPath[jar.projectPath] ?: run {
                    failures += HostedReadProjectClasspathFailure.UnknownProject(jar.projectPath)
                    null
                }
            }
            observedProjects.groupingBy { it }.eachCount().filterValues { it != 1 }.keys.forEach {
                failures += HostedReadProjectClasspathFailure.DuplicateProject(it.projectPath)
            }
            if (observedProjects.toSet() != expectedProjects) {
                failures += HostedReadProjectClasspathFailure.ArtifactSetMismatch(
                    jars.map { "${it.projectPath}|${it.artifactName}" }.sorted(),
                )
            }
            if (failures.isNotEmpty()) return rejected(failures)

            val artifacts = mutableListOf<Artifact>()
            val classes = mutableListOf<HostedReadClassBytes>()
            jars.sortedBy(HostedReadProjectJarBytes::projectPath).forEach { jar ->
                val extracted = when (val result = jar.extractClasses()) {
                    is ProjectClassExtraction.Loaded -> result.classes
                    is ProjectClassExtraction.Rejected -> {
                        failures += result.failure
                        return@forEach
                    }
                }
                artifacts += Artifact(
                    modulesByPath.getValue(jar.projectPath),
                    jar.artifactName,
                    jar.useBytes(ByteArray::sha256),
                    extracted.size,
                )
                classes += extracted
            }
            if (failures.isNotEmpty()) return rejected(failures)
            val observations = when (val result = JvmEffectScanner.scanBytes(module, classes)) {
                is BytecodeScanOutcome.Scanned -> result.effects()
                is BytecodeScanOutcome.Failed -> return rejected(
                    listOf(HostedReadProjectClasspathFailure.MalformedClass(result.failures())),
                )
            }
            observations.filter { it.effect in projectClasspathProhibitedEffects }.forEach {
                failures += HostedReadProjectClasspathFailure.ForbiddenObservation(it)
            }
            if (failures.isNotEmpty()) return rejected(failures)
            val canonical = artifacts.joinToString(separator = "") { artifact ->
                "${artifact.project.projectPath}|${artifact.artifactName}|" +
                    "${artifact.sha256}|${artifact.classCount}\n"
            }.toByteArray(StandardCharsets.UTF_8)
            return HostedReadProjectClasspathRefinement.Admitted(
                HostedReadProjectClasspath(
                    HostedReadClasspathScanPolicy.capture(module),
                    artifacts,
                    canonical.sha256(),
                    projectClasspathProhibitedEffects.map { HostedReadEffectCount(it, 0) },
                ),
            )
        }

        private fun rejected(failures: List<HostedReadProjectClasspathFailure>) =
            HostedReadProjectClasspathRefinement.Rejected(failures.first(), failures.drop(1))
    }
}

private sealed interface ProjectClassExtraction {
    data class Loaded(val classes: List<HostedReadClassBytes>) : ProjectClassExtraction
    data class Rejected(val failure: HostedReadProjectClasspathFailure) : ProjectClassExtraction
}

/**
 * Proof transition: `HostedReadProjectJarBytes -> ProjectClassExtraction`.
 *
 * Establishes a nonempty, uniquely named immutable class-byte inventory for one project JAR.
 * Malformed, empty, or duplicate archives remain closed [HostedReadProjectClasspathFailure] data.
 * Raw ZIP entry bytes are extracted only inside this archive boundary.
 */
private fun HostedReadProjectJarBytes.extractClasses(): ProjectClassExtraction = try {
    useBytes { bytes ->
        val classes = mutableListOf<HostedReadClassBytes>()
        val names = linkedSetOf<String>()
        JarInputStream(ByteArrayInputStream(bytes)).use { jar ->
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".class")) {
                    if (!names.add(entry.name)) {
                        return@useBytes ProjectClassExtraction.Rejected(
                            HostedReadProjectClasspathFailure.DuplicateClass(
                                projectPath,
                                entry.name,
                            ),
                        )
                    }
                    classes += HostedReadClassBytes.capture(
                        "$artifactName!/${entry.name}",
                        jar.readBytes(),
                    )
                }
            }
        }
        if (classes.isEmpty()) {
            ProjectClassExtraction.Rejected(
                HostedReadProjectClasspathFailure.EmptyArchive(projectPath),
            )
        } else {
            ProjectClassExtraction.Loaded(classes)
        }
    }
} catch (_: IOException) {
    ProjectClassExtraction.Rejected(
        HostedReadProjectClasspathFailure.MalformedArchive(projectPath),
    )
} catch (_: SecurityException) {
    ProjectClassExtraction.Rejected(
        HostedReadProjectClasspathFailure.MalformedArchive(projectPath),
    )
}

internal class HostedReadClasspathScanPolicy private constructor(
    val moduleId: ModuleId,
    val role: ModuleRole,
) {
    internal companion object {
        /**
         * Proof transition: `ValidatedModulePolicy -> HostedReadClasspathScanPolicy`.
         *
         * Retains the exact module identity and role used for one classpath scan. Raw policy fields
         * may leave only in a classpath-policy rejection or the admitted hosted capture.
         */
        fun capture(module: ValidatedModulePolicy) = HostedReadClasspathScanPolicy(
            module.id,
            module.role,
        )

        /**
         * Proof transition: `(ValidatedModulePolicy, HostedReadClasspathScanPolicy,
         * HostedReadClasspathScanPolicy) -> HostedReadClasspathPolicyRefinement`.
         *
         * Establishes that both runtime classpath proofs were scanned under the exact module and
         * role being admitted. Cross-module or cross-role substitution remains closed
         * [HostedReadClasspathPolicyFailure] data. Module and role values may leave only in that
         * failure or the admitted policy proof.
         */
        fun refine(
            module: ValidatedModulePolicy,
            project: HostedReadClasspathScanPolicy,
            external: HostedReadClasspathScanPolicy,
        ): HostedReadClasspathPolicyRefinement {
            val expected = capture(module)
            return if (project == expected && external == expected) {
                HostedReadClasspathPolicyRefinement.Admitted(expected)
            } else {
                HostedReadClasspathPolicyRefinement.Rejected(
                    HostedReadClasspathPolicyFailure(expected, project, external),
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is HostedReadClasspathScanPolicy && moduleId == other.moduleId && role == other.role

    override fun hashCode(): Int = 31 * moduleId.hashCode() + role.hashCode()
}

internal data class HostedReadClasspathPolicyFailure(
    val expected: HostedReadClasspathScanPolicy,
    val project: HostedReadClasspathScanPolicy,
    val external: HostedReadClasspathScanPolicy,
)

internal sealed interface HostedReadClasspathPolicyRefinement {
    data class Admitted(val proof: HostedReadClasspathScanPolicy) :
        HostedReadClasspathPolicyRefinement
    data class Rejected(val failure: HostedReadClasspathPolicyFailure) :
        HostedReadClasspathPolicyRefinement
}

private val projectClasspathProhibitedEffects =
    ForbiddenEffect.entries.filterNot { it == ForbiddenEffect.INTELLIJ_PLATFORM }

private fun ByteArray.sha256(): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(this),
)
