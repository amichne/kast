package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.gradle.GradleDistributionVersion
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class InstalledGradleImportDiagnosticTest {
    @Test
    fun `callback retains incompatible payload major and never logs exception payload`() {
        val observed = mutableListOf<InstalledGradleImportOutcome>()
        val future = CompletableFuture<Void>()
        val outcome = future.closedImportOutcome { observed += it }
        future.completeExceptionally(RuntimeException("Unsupported class file major version 69 private payload"))
        val incompatible = outcome.join() as InstalledGradleImportOutcome.IncompatiblePayload
        assertEquals(69, incompatible.major.value)
        assertEquals(listOf(incompatible), observed)
        val log = identity().logFields(incompatible).toString()
        assertTrue(log.contains("incompatible-tooling-payload"))
        assertTrue(log.contains("\"clientJava\":25"))
        assertTrue(log.contains("\"projectJava\":17"))
        assertFalse(log.contains("private payload"))
        assertFalse(log.contains("/private"))
    }

    @Test
    fun `complete and unknown rejection remain distinct finite terminal events`() {
        val complete = CompletableFuture<Void>()
        val observed = mutableListOf<InstalledGradleImportOutcome>()
        val result = complete.closedImportOutcome { observed += it }
        complete.complete(null)
        assertEquals(InstalledGradleImportOutcome.Completed, result.join())
        assertEquals(listOf(InstalledGradleImportOutcome.Completed), observed)
        for (message in listOf("private token", "Unsupported class file major version 999", "x".repeat(4097))) {
            assertEquals(GradlePayloadCompatibility.Unclassified,
                GradlePayloadClassFileMajor.observe(IllegalArgumentException(message)))
        }
        val unsupported = GradlePayloadClassFileMajor.observe(
            UnsupportedClassVersionError("type (class file version 69.0), runtime supports 61.0"),
        ) as GradlePayloadCompatibility.Unsupported
        assertEquals(69, unsupported.major.value)
    }

    private fun identity() = InstalledGradleImportExecutionIdentity(
        GradleDistributionVersion.observed("7.6.4"), JavaFeature.of(25),
        GradleImportEnvironmentIdentity.digest("/private/client"), JavaFeature.of(17),
        GradleImportEnvironmentIdentity.digest("/private/project"),
    )
}
