package support.plugin

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class StandalonePluginModelTest {
    @Test
    fun `payload root and archive entries reject before descriptor admission`() {
        assertRejected(emptyList(), StandalonePluginFailure.MISSING_PAYLOAD)
        assertRejected(
            listOf(
                payload("kast-indexer/lib/kast.jar", descriptor()),
                payload("kast-indexer/lib/kast.jar", PluginDescriptorObservation.Absent),
            ),
            StandalonePluginFailure.DUPLICATE_ARCHIVE_ENTRY,
        )
        assertRejected(
            listOf(payload("wrong-root/lib/kast.jar", descriptor())),
            StandalonePluginFailure.INVALID_ARCHIVE_ENTRY,
        )
    }

    @Test
    fun `exact descriptor and private payload refine to standalone plugin`() {
        val result = assertInstanceOf<StandalonePluginPayloadResult.Complete>(
            KastStandalonePlugin.admit(listOf(payload("kast-indexer/lib/kast.jar", descriptor()))),
        )

        assertEquals("kast-indexer/lib/kast.jar", result.payload.descriptorJarEntry.value)
        assertEquals(1, result.payload.jars.size)
    }

    @Test
    fun `private idea home and platform classes reject`() {
        assertRejected(
            listOf(payload("idea-home/plugins/kast/lib/kast.jar", descriptor())),
            StandalonePluginFailure.PRIVATE_IDEA_HOME_LAYOUT,
        )
        assertRejected(
            listOf(
                PluginPayloadObservation(
                    "kast-indexer/lib/platform.jar",
                    setOf("com/intellij/idea/Main.class"),
                    descriptor(),
                ),
            ),
            StandalonePluginFailure.PLATFORM_CLASS_PRESENT,
        )
    }

    @Test
    fun `descriptor identity cardinality and registrations reject`() {
        assertRejected(
            listOf(payload("kast-indexer/lib/kast.jar", PluginDescriptorObservation.Absent)),
            StandalonePluginFailure.MISSING_DESCRIPTOR,
        )
        assertRejected(
            listOf(
                payload("kast-indexer/lib/a.jar", descriptor()),
                payload("kast-indexer/lib/b.jar", descriptor()),
            ),
            StandalonePluginFailure.MULTIPLE_DESCRIPTORS,
        )
        assertRejected(
            listOf(
                payload(
                    "kast-indexer/lib/kast.jar",
                    PluginDescriptorObservation.Present(
                        "wrong.plugin",
                        RegistrationObservation.PRESENT,
                        RegistrationObservation.PRESENT,
                    ),
                ),
            ),
            StandalonePluginFailure.PLUGIN_ID_MISMATCH,
        )
        assertRejected(
            listOf(
                payload(
                    "kast-indexer/lib/kast.jar",
                    PluginDescriptorObservation.Present(
                        KastStandalonePlugin.id.value,
                        RegistrationObservation.ABSENT,
                        RegistrationObservation.PRESENT,
                    ),
                ),
            ),
            StandalonePluginFailure.PROJECT_SERVICE_MISSING,
        )
        assertRejected(
            listOf(
                payload(
                    "kast-indexer/lib/kast.jar",
                    PluginDescriptorObservation.Present(
                        KastStandalonePlugin.id.value,
                        RegistrationObservation.PRESENT,
                        RegistrationObservation.ABSENT,
                    ),
                ),
            ),
            StandalonePluginFailure.STARTUP_ACTIVITY_MISSING,
        )
    }

    @Test
    fun `artifact path refines only beneath repository root`(@TempDir root: Path) {
        val admitted = assertInstanceOf<RepositoryRelativeArtifactPathResult.Complete>(
            admitRepositoryRelativeArtifactPath(root, root.resolve("ide-plugin/build/plugin.zip")),
        )
        assertEquals("ide-plugin/build/plugin.zip", admitted.path.value)

        assertEquals(
            RepositoryRelativeArtifactPathResult.Rejected(
                StandalonePluginFailure.ARTIFACT_OUTSIDE_REPOSITORY,
            ),
            admitRepositoryRelativeArtifactPath(root, root.resolveSibling("plugin.zip")),
        )
    }

    private fun descriptor() = PluginDescriptorObservation.Present(
        KastStandalonePlugin.id.value,
        RegistrationObservation.PRESENT,
        RegistrationObservation.PRESENT,
    )

    private fun payload(entry: String, descriptor: PluginDescriptorObservation) =
        PluginPayloadObservation(entry, emptySet(), descriptor)

    private fun assertRejected(
        input: List<PluginPayloadObservation>,
        expected: StandalonePluginFailure,
    ) {
        assertEquals(
            StandalonePluginPayloadResult.Rejected(expected),
            KastStandalonePlugin.admit(input),
        )
    }
}
