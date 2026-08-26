package support.plugin

import java.nio.file.Path

@kotlinx.serialization.Serializable
@JvmInline
internal value class StandalonePluginId private constructor(val value: String) {
    internal companion object {
        val Kast = StandalonePluginId("io.github.amichne.kast.indexer")
    }
}

internal enum class RegistrationObservation { ABSENT, PRESENT }

internal sealed interface PluginDescriptorObservation {
    data object Absent : PluginDescriptorObservation
    data class Present(
        val pluginId: String,
        val applicationStarter: RegistrationObservation,
        val gradleResolver: RegistrationObservation,
    ) : PluginDescriptorObservation
}

internal data class PluginPayloadObservation(
    val archiveEntry: String,
    val classEntries: Set<String>,
    val descriptor: PluginDescriptorObservation,
)

@kotlinx.serialization.Serializable
@JvmInline
internal value class PluginArchiveEntry internal constructor(val value: String)

@kotlinx.serialization.Serializable
@JvmInline
internal value class RepositoryRelativeArtifactPath internal constructor(val value: String)

internal sealed interface RepositoryRelativeArtifactPathResult {
    data class Complete(val path: RepositoryRelativeArtifactPath) :
        RepositoryRelativeArtifactPathResult
    data class Rejected(val failure: StandalonePluginFailure) :
        RepositoryRelativeArtifactPathResult
}

internal enum class StandalonePluginFailure {
    MISSING_PAYLOAD,
    DUPLICATE_ARCHIVE_ENTRY,
    INVALID_ARCHIVE_ENTRY,
    PRIVATE_IDEA_HOME_LAYOUT,
    PLATFORM_CLASS_PRESENT,
    MISSING_DESCRIPTOR,
    MULTIPLE_DESCRIPTORS,
    PLUGIN_ID_MISMATCH,
    APPLICATION_STARTER_MISSING,
    GRADLE_RESOLVER_MISSING,
    MALFORMED_JAR,
    MALFORMED_DESCRIPTOR,
    ARTIFACT_OUTSIDE_REPOSITORY,
}

/**
 * Proof transition: `(Path, Path) -> RepositoryRelativeArtifactPathResult`.
 * Establishes that the artifact is a non-empty normalized descendant of the repository root.
 * Expected cross-root or escaping paths are finite
 * [StandalonePluginFailure.ARTIFACT_OUTSIDE_REPOSITORY]. Raw paths remain at the Gradle task
 * boundary.
 */
internal fun admitRepositoryRelativeArtifactPath(
    repositoryRoot: Path,
    artifact: Path,
): RepositoryRelativeArtifactPathResult {
    val relative = try {
        repositoryRoot.toAbsolutePath().normalize().relativize(artifact.toAbsolutePath().normalize())
    } catch (_: IllegalArgumentException) {
        return RepositoryRelativeArtifactPathResult.Rejected(
            StandalonePluginFailure.ARTIFACT_OUTSIDE_REPOSITORY,
        )
    }
    if (relative.nameCount == 0 || relative.startsWith("..")) {
        return RepositoryRelativeArtifactPathResult.Rejected(
            StandalonePluginFailure.ARTIFACT_OUTSIDE_REPOSITORY,
        )
    }
    return RepositoryRelativeArtifactPathResult.Complete(
        RepositoryRelativeArtifactPath(relative.joinToString("/")),
    )
}

internal class ValidatedStandalonePluginPayload internal constructor(
    val jars: List<PluginArchiveEntry>,
    val descriptorJarEntry: PluginArchiveEntry,
)

internal sealed interface StandalonePluginPayloadResult {
    data class Complete(val payload: ValidatedStandalonePluginPayload) :
        StandalonePluginPayloadResult
    data class Rejected(val failure: StandalonePluginFailure) : StandalonePluginPayloadResult
}

internal data class StandalonePluginNegativeProof(
    val failures: List<StandalonePluginFailure>,
)

internal enum class StandalonePluginNegativeProofFailure { EXPECTED_REJECTION_MISSING }

internal sealed interface StandalonePluginNegativeProofResult {
    data class Complete(val proof: StandalonePluginNegativeProof) :
        StandalonePluginNegativeProofResult
    data class Rejected(val failure: StandalonePluginNegativeProofFailure) :
        StandalonePluginNegativeProofResult
}

/**
 * Proof transition: fixed KVP-010 rejection fixtures -> `StandalonePluginNegativeProofResult`.
 * Establishes that missing payload, private IDEA-home layout, and platform-owned classes remain
 * independently rejected by their exact finite [StandalonePluginFailure]. Expected proof drift is
 * [StandalonePluginNegativeProofFailure]; fixture primitives do not leave this build boundary.
 */
internal fun deriveStandalonePluginNegativeProof(): StandalonePluginNegativeProofResult {
    val descriptor = PluginDescriptorObservation.Present(
        KastStandalonePlugin.id.value,
        RegistrationObservation.PRESENT,
        RegistrationObservation.PRESENT,
    )
    val cases = listOf(
        emptyList<PluginPayloadObservation>() to StandalonePluginFailure.MISSING_PAYLOAD,
        listOf(PluginPayloadObservation("idea-home/lib/payload.jar", emptySet(), descriptor)) to
            StandalonePluginFailure.PRIVATE_IDEA_HOME_LAYOUT,
        listOf(
            PluginPayloadObservation(
                "${KastStandalonePlugin.root}/lib/platform.jar",
                setOf("com/intellij/idea/Main.class"),
                descriptor,
            ),
        ) to StandalonePluginFailure.PLATFORM_CLASS_PRESENT,
    )
    if (cases.any { (input, expected) ->
            KastStandalonePlugin.admit(input) != StandalonePluginPayloadResult.Rejected(expected)
        }
    ) {
        return StandalonePluginNegativeProofResult.Rejected(
            StandalonePluginNegativeProofFailure.EXPECTED_REJECTION_MISSING,
        )
    }
    return StandalonePluginNegativeProofResult.Complete(
        StandalonePluginNegativeProof(cases.map { it.second }),
    )
}

internal object KastStandalonePlugin {
    val id = StandalonePluginId.Kast
    const val root: String = "kast-indexer"

    private val platformClassEntries = setOf(
        "com/intellij/idea/Main.class",
        "org/gradle/launcher/GradleMain.class",
        "org/jetbrains/kotlin/jps/build/KotlinBuilder.class",
    )

    /**
     * Proof transition: `List<PluginPayloadObservation> -> StandalonePluginPayloadResult`.
     * Establishes one standalone plugin root, unique JAR entries, absence of private IDEA-home and
     * directly observed platform classes, and one exact descriptor with both required registrations.
     * Expected invalid payloads are finite [StandalonePluginFailure]. Raw paths and JAR entries may
     * be extracted only at the Gradle packaging boundary.
     */
    fun admit(observations: List<PluginPayloadObservation>): StandalonePluginPayloadResult {
        if (observations.isEmpty()) return rejected(StandalonePluginFailure.MISSING_PAYLOAD)
        val archiveEntries = observations.map { it.archiveEntry }
        if (archiveEntries.distinct().size != archiveEntries.size) {
            return rejected(StandalonePluginFailure.DUPLICATE_ARCHIVE_ENTRY)
        }
        if (archiveEntries.any { it.startsWith("idea-home/") || "/idea-home/" in it }) {
            return rejected(StandalonePluginFailure.PRIVATE_IDEA_HOME_LAYOUT)
        }
        if (archiveEntries.any { !it.startsWith("$root/lib/") || !it.endsWith(".jar") }) {
            return rejected(StandalonePluginFailure.INVALID_ARCHIVE_ENTRY)
        }
        if (observations.any { observation ->
                observation.classEntries.any(platformClassEntries::contains)
            }
        ) return rejected(StandalonePluginFailure.PLATFORM_CLASS_PRESENT)
        val descriptors = buildList {
            observations.forEach { observation ->
                when (val descriptor = observation.descriptor) {
                    PluginDescriptorObservation.Absent -> Unit
                    is PluginDescriptorObservation.Present -> add(
                        PluginArchiveEntry(observation.archiveEntry) to descriptor,
                    )
                }
            }
        }
        if (descriptors.isEmpty()) return rejected(StandalonePluginFailure.MISSING_DESCRIPTOR)
        if (descriptors.size != 1) return rejected(StandalonePluginFailure.MULTIPLE_DESCRIPTORS)
        val (descriptorEntry, descriptor) = descriptors.single()
        if (descriptor.pluginId != id.value) {
            return rejected(StandalonePluginFailure.PLUGIN_ID_MISMATCH)
        }
        if (descriptor.applicationStarter != RegistrationObservation.PRESENT) {
            return rejected(StandalonePluginFailure.APPLICATION_STARTER_MISSING)
        }
        if (descriptor.gradleResolver != RegistrationObservation.PRESENT) {
            return rejected(StandalonePluginFailure.GRADLE_RESOLVER_MISSING)
        }
        return StandalonePluginPayloadResult.Complete(
            ValidatedStandalonePluginPayload(
                observations.map { PluginArchiveEntry(it.archiveEntry) }.sortedBy { it.value },
                descriptorEntry,
            ),
        )
    }

    private fun rejected(failure: StandalonePluginFailure) =
        StandalonePluginPayloadResult.Rejected(failure)
}
