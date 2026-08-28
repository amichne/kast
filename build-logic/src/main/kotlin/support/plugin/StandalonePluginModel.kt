package support.plugin

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
        val projectService: RegistrationObservation,
        val startupActivity: RegistrationObservation,
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

internal enum class StandalonePluginFailure {
    MISSING_PAYLOAD,
    DUPLICATE_ARCHIVE_ENTRY,
    INVALID_ARCHIVE_ENTRY,
    PRIVATE_IDEA_HOME_LAYOUT,
    PLATFORM_CLASS_PRESENT,
    MISSING_DESCRIPTOR,
    MULTIPLE_DESCRIPTORS,
    PLUGIN_ID_MISMATCH,
    PROJECT_SERVICE_MISSING,
    STARTUP_ACTIVITY_MISSING,
    MALFORMED_JAR,
    MALFORMED_DESCRIPTOR,
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
        if (descriptor.projectService != RegistrationObservation.PRESENT) {
            return rejected(StandalonePluginFailure.PROJECT_SERVICE_MISSING)
        }
        if (descriptor.startupActivity != RegistrationObservation.PRESENT) {
            return rejected(StandalonePluginFailure.STARTUP_ACTIVITY_MISSING)
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
