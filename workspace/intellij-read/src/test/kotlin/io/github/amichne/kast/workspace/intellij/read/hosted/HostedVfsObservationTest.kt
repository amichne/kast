package io.github.amichne.kast.workspace.intellij.read.hosted

import com.google.gson.JsonParser
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedVfsObservationTest {
    private val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value

    @Test
    fun `root observations retain event origin and finite categories without paths`() {
        val events =
            listOf(
                event("/workspace/.idea/workspace.xml"),
                event("/workspace/module/.gradle/cache.bin"),
                event("/workspace/build/output.kt"),
                event("/workspace/module/build.gradle.kts"),
                event("/workspace/src/PrivateName.kt"),
                event("/workspace/src/PrivateName.kt"),
                event("/workspace/src/PrivateName.java"),
                event("/elsewhere/secret.kt"),
            )
        val result = observeHostedVfsBatch(root, events, ReadLimits.Default)
        assertTrue(result is HostedVfsBatchEvidence.Observed)
        val counts =
            (result as HostedVfsBatchEvidence.Observed).counts.associate { it.coordinate.category to it.paths.value }
        assertEquals(
            mapOf(
                HostedVfsPathCategory.IDE_SETTINGS_PATH to 1,
                HostedVfsPathCategory.GRADLE_CACHE_PATH to 1,
                HostedVfsPathCategory.BUILD_PATH to 1,
                HostedVfsPathCategory.GRADLE_SCRIPT to 1,
                HostedVfsPathCategory.KOTLIN_FILE to 2,
                HostedVfsPathCategory.JAVA_FILE to 1,
            ),
            counts,
        )
        assertTrue(
            result.counts.all {
                it.coordinate.kind == HostedVfsEventKind.CONTENT && it.coordinate.origin == HostedVfsEventOrigin.REFRESH
            }
        )
    }

    @Test
    fun `unproven paths reject observation instead of producing zero evidence`() {
        assertEquals(
            HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.PATH_UNPROVEN),
            observeHostedVfsBatch(root, listOf(event("/workspace/../unknown")), ReadLimits.Default),
        )
    }

    @Test
    fun `outside root and empty batches produce no event`() {
        assertEquals(HostedVfsBatchEvidence.OutsideRoot, observeHostedVfsBatch(root, emptyList(), ReadLimits.Default))
        assertEquals(
            HostedVfsBatchEvidence.OutsideRoot,
            observeHostedVfsBatch(root, listOf(event("/workspace-other/file.kt")), ReadLimits.Default),
        )
    }

    @Test
    fun `bounds reject before collecting event paths`() {
        val limits = (ReadLimits.resolve(mapOf("KAST_READ_EPOCH_VFS_EVENTS" to "1")) as Refinement.Refined).value
        assertEquals(
            HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.BATCH_LIMIT),
            observeHostedVfsBatch(root, listOf(event("/workspace/a.kt"), event("/workspace/b.kt")), limits),
        )
    }

    @Test
    fun `receipt serializes only bounded labels counts and host correlation`() {
        val host = IdeReadHostLifetime.fromBoundary(UUID.randomUUID())
        val published = mutableListOf<String>()
        val evidence = observeHostedVfsBatch(root, listOf(event("/workspace/src/PrivateName.kt")), ReadLimits.Default)
        publishHostedVfsEvidence(host, evidence, published::add)
        val document = JsonParser.parseString(published.single()).asJsonObject
        assertEquals("kast_hosted_vfs", document["event"].asString)
        assertEquals(host.value.toString(), document["host"].asString)
        assertEquals("observed", document["outcome"].asString)
        assertEquals(1, document["counts"].asJsonArray.single().asJsonObject["paths"].asInt)
        assertFalse(published.single().contains("PrivateName"))
        assertFalse(published.single().contains("/workspace"))
        published.clear()
        publishHostedVfsEvidence(
            host,
            HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.PATH_UNPROVEN),
            published::add,
        )
        assertEquals("PATH_UNPROVEN", JsonParser.parseString(published.single()).asJsonObject["failure"].asString)
        published.clear()
        publishHostedVfsEvidence(host, HostedVfsBatchEvidence.OutsideRoot, published::add)
        assertTrue(published.isEmpty())
    }

    private fun event(path: String) =
        HostedVfsEventBoundary(HostedVfsEventKind.CONTENT, HostedVfsEventOrigin.REFRESH, listOf(path))
}
