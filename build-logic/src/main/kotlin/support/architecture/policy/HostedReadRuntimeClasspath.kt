package support.architecture

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarInputStream

internal class HostedReadExternalJarBytes private constructor(
    val componentId: String,
    val artifactName: String,
    bytes: ByteArray,
) {
    private val snapshot = bytes.copyOf()

    internal fun <T> useBytes(block: (ByteArray) -> T): T = block(snapshot.copyOf())

    internal companion object {
        /**
         * Proof transition: `(String, String, ByteArray) -> HostedReadExternalJarBytes`.
         *
         * Captures one immutable external artifact observation. Mutable bytes are copied at entry
         * and can leave only as a copy at the archive, digest, or scanner boundary.
         */
        fun capture(
            componentId: String,
            artifactName: String,
            bytes: ByteArray,
        ): HostedReadExternalJarBytes = HostedReadExternalJarBytes(
            componentId,
            artifactName,
            bytes,
        )
    }
}

internal sealed interface HostedReadExternalClasspathFailure {
    data class ArtifactSetMismatch(val observed: List<String>) :
        HostedReadExternalClasspathFailure
    data class ArtifactDigestMismatch(val componentId: String, val observed: String) :
        HostedReadExternalClasspathFailure
    data class MalformedArchive(val componentId: String) : HostedReadExternalClasspathFailure
    data class EmptyArchive(val componentId: String) : HostedReadExternalClasspathFailure
    data class DuplicateClass(val componentId: String, val className: String) :
        HostedReadExternalClasspathFailure
    data class MalformedClass(
        val componentId: String,
        val failures: List<BytecodeScanFailure>,
    ) : HostedReadExternalClasspathFailure
    data class ForbiddenObservation(val observation: EffectObservation) :
        HostedReadExternalClasspathFailure
}

internal sealed interface HostedReadExternalClasspathRefinement {
    data class Admitted(val classpath: HostedReadExternalClasspath) :
        HostedReadExternalClasspathRefinement
    data class Rejected(
        val first: HostedReadExternalClasspathFailure,
        val additional: List<HostedReadExternalClasspathFailure>,
    ) : HostedReadExternalClasspathRefinement
}

internal enum class HostedReadExternalClasspathAuthority { PRODUCTION_RUNTIME }

internal data class HostedReadExternalArtifactObservation(
    val componentId: String,
    val artifactName: String,
    val sha256: String,
    val classCount: Int,
)

internal class HostedReadExternalClasspath private constructor(
    val scanPolicy: HostedReadClasspathScanPolicy,
    artifacts: List<Artifact>,
    val artifactSetSha256: String,
    prohibitedEffectCounts: List<HostedReadEffectCount>,
    val genericFilesystemPrimitiveReferenceCount: Int,
) {
    val authority: HostedReadExternalClasspathAuthority =
        HostedReadExternalClasspathAuthority.PRODUCTION_RUNTIME
    private val admittedArtifacts = artifacts.toList()
    private val admittedEffectCounts = prohibitedEffectCounts.toList()

    internal fun artifacts(): List<HostedReadExternalArtifactObservation> = admittedArtifacts.map {
        HostedReadExternalArtifactObservation(it.componentId, it.artifactName, it.sha256, it.classCount)
    }

    internal fun prohibitedEffectCounts(): List<HostedReadEffectCount> =
        admittedEffectCounts.toList()

    private data class Artifact(
        val componentId: String,
        val artifactName: String,
        val sha256: String,
        val classCount: Int,
    )

    val classCount: Int = admittedArtifacts.sumOf(Artifact::classCount)

    internal companion object {
        /**
         * Proof transition: `(ValidatedModulePolicy, List<HostedReadExternalJarBytes>) ->
         * HostedReadExternalClasspathRefinement`.
         *
         * Establishes the exact coordinate, artifact, SHA-256, nonempty class inventory, and
         * all-zero architecture-specific stronger effects for every resolved external runtime
         * artifact. Unknown, changed, malformed, duplicate, or forbidden artifacts remain closed
         * [HostedReadExternalClasspathFailure] data. Raw ZIP and class bytes leave only at this
         * classpath refinement boundary.
         */
        fun refine(
            module: ValidatedModulePolicy,
            jars: List<HostedReadExternalJarBytes>,
        ): HostedReadExternalClasspathRefinement {
            val expectedArtifacts = expectedExternalArtifacts()
            val expectedIdentities = expectedArtifacts.map { it.identity }
            val observedIdentities = jars.map { "${it.componentId}|${it.artifactName}" }.sorted()
            if (observedIdentities != expectedIdentities) {
                return rejected(
                    HostedReadExternalClasspathFailure.ArtifactSetMismatch(observedIdentities),
                )
            }
            val failures = mutableListOf<HostedReadExternalClasspathFailure>()
            val admitted = mutableListOf<Artifact>()
            val allClasses = mutableListOf<HostedReadClassBytes>()
            jars.sortedBy { "${it.componentId}|${it.artifactName}" }.forEach { jar ->
                val expected = expectedArtifacts.single {
                    it.identity == "${jar.componentId}|${jar.artifactName}"
                }
                val digest = jar.useBytes(ByteArray::sha256)
                if (digest != expected.sha256) {
                    failures += HostedReadExternalClasspathFailure.ArtifactDigestMismatch(
                        jar.componentId,
                        digest,
                    )
                    return@forEach
                }
                val classes = when (val extraction = jar.extractClasses()) {
                    is ExternalClassExtraction.Loaded -> extraction.classes
                    is ExternalClassExtraction.Rejected -> {
                        failures += extraction.failure
                        return@forEach
                    }
                }
                admitted += Artifact(jar.componentId, jar.artifactName, digest, classes.size)
                allClasses += classes
            }
            if (failures.isNotEmpty()) return rejected(failures.first(), failures.drop(1))
            val observations = when (val scan = JvmEffectScanner.scanBytes(module, allClasses)) {
                is BytecodeScanOutcome.Scanned -> scan.effects()
                is BytecodeScanOutcome.Failed -> return rejected(
                    HostedReadExternalClasspathFailure.MalformedClass("runtimeClasspath", scan.failures()),
                )
            }
            observations.filter { it.effect in externalClasspathProhibitedEffects }.forEach {
                failures += HostedReadExternalClasspathFailure.ForbiddenObservation(it)
            }
            if (failures.isNotEmpty()) return rejected(failures.first(), failures.drop(1))
            val canonical = admitted.joinToString(separator = "") { artifact ->
                "${artifact.componentId}|${artifact.artifactName}|${artifact.sha256}|${artifact.classCount}\n"
            }.toByteArray(StandardCharsets.UTF_8)
            return HostedReadExternalClasspathRefinement.Admitted(
                HostedReadExternalClasspath(
                    HostedReadClasspathScanPolicy.capture(module),
                    admitted,
                    canonical.sha256(),
                    externalClasspathProhibitedEffects.map { HostedReadEffectCount(it, 0) },
                    observations.count { it.effect == ForbiddenEffect.FILESYSTEM_WRITE },
                ),
            )
        }

        private fun rejected(
            first: HostedReadExternalClasspathFailure,
            additional: List<HostedReadExternalClasspathFailure> = emptyList(),
        ) = HostedReadExternalClasspathRefinement.Rejected(first, additional)
    }
}

private sealed interface ExternalClassExtraction {
    data class Loaded(val classes: List<HostedReadClassBytes>) : ExternalClassExtraction
    data class Rejected(val failure: HostedReadExternalClasspathFailure) : ExternalClassExtraction
}

/**
 * Proof transition: `HostedReadExternalJarBytes -> ExternalClassExtraction`.
 *
 * Establishes a nonempty, uniquely named immutable class-byte inventory for one exact JAR.
 * Malformed, empty, or duplicate archives remain closed [HostedReadExternalClasspathFailure]
 * data. Raw ZIP entry bytes are extracted only inside this archive boundary.
 */
private fun HostedReadExternalJarBytes.extractClasses(): ExternalClassExtraction = try {
    useBytes { bytes ->
        val classes = mutableListOf<HostedReadClassBytes>()
        val names = linkedSetOf<String>()
        JarInputStream(ByteArrayInputStream(bytes)).use { jar ->
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".class")) {
                    if (!names.add(entry.name)) {
                        return@useBytes ExternalClassExtraction.Rejected(
                            HostedReadExternalClasspathFailure.DuplicateClass(
                                componentId,
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
            ExternalClassExtraction.Rejected(
                HostedReadExternalClasspathFailure.EmptyArchive(componentId),
            )
        } else {
            ExternalClassExtraction.Loaded(classes)
        }
    }
} catch (_: IOException) {
    ExternalClassExtraction.Rejected(
        HostedReadExternalClasspathFailure.MalformedArchive(componentId),
    )
} catch (_: SecurityException) {
    ExternalClassExtraction.Rejected(
        HostedReadExternalClasspathFailure.MalformedArchive(componentId),
    )
}

private data class ExpectedExternalArtifact(
    val componentId: String,
    val artifactName: String,
    val sha256: String,
) {
    val identity: String = "$componentId|$artifactName"
}

private fun expectedExternalArtifacts(): List<ExpectedExternalArtifact> = listOf(
        ExpectedExternalArtifact(
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
            "kotlin-stdlib-2.4.10.jar",
            "4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df",
        ),
        ExpectedExternalArtifact(
            "org.jetbrains:annotations:13.0",
            "annotations-13.0.jar",
            "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478",
        ),
    ).sortedBy(ExpectedExternalArtifact::identity)

private val externalClasspathProhibitedEffects = listOf(
    ForbiddenEffect.PROJECT_OPEN,
    ForbiddenEffect.GRADLE_IMPORT,
    ForbiddenEffect.RECURSIVE_VFS_REFRESH,
    ForbiddenEffect.INDEXING_CYCLE,
    ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
    ForbiddenEffect.JDBC,
    ForbiddenEffect.ISOLATED_RUNTIME,
    ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY,
)

private fun ByteArray.sha256(): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(this),
)
