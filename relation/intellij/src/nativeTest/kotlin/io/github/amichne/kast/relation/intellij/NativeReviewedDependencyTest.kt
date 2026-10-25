package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking

internal class NativeReviewedDependencyTest : ReviewedCallbackNativeTestBase() {
    fun testE7RejectsChangedSourceArtifactBeforeCallbackResultAdmission() = runBlocking {
        val fixture = fixture("E0", "babe9037f525c8c3102305bd5d34ce4d5455faccb37f32a52c87d335302780d2")
        var changed = false
        val steps =
            trace(
                fixture,
                beforeRead = { site ->
                    if (
                        !changed &&
                            site.role == ValueRole.Return &&
                            site.executionOwner.context is ValueExecutionContext.ReviewedCallback
                    ) {
                        changed = true
                        Files.write(fixture.sourceArtifact, byteArrayOf(0), StandardOpenOption.APPEND)
                        ApplicationManager.getApplication().invokeAndWait {
                            checkNotNull(
                                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fixture.sourceArtifact)
                                )
                                .refresh(false, false)
                        }
                    }
                },
            )
        assertTrue("The source artifact must change after owner admission", changed)
        assertTrue(IntellijReadTermination.VALUE_REVIEWED_CALLBACK_ADMITTED in observation.terminations)
        assertTrue(IntellijReadTermination.VALUE_REVIEWED_ARTIFACT_MISMATCH in observation.terminations)
        assertTrue(steps.flatMap { it.reviewedTransfers }.isEmpty())
        assertTrue(steps.flatMap { it.obligations }.any { it.cause == ValueFlowUnsupportedCause.NESTED_EXECUTION })
    }

    fun testE8ExcludesMainSourceOutputFromTheExactTestDomain() = runBlocking {
        val fixture = fixture("E8", "8a78ed8339ef529c33e723f53f7d39a3c1bf49e751c1a8ab8ac5df7b770217bf")
        val steps =
            trace(fixture, reject = { rejection -> assertEquals(ValueFlowRejection.OUTSIDE_DOMAIN, rejection.cause) })
        val sites = steps.flatMap { step ->
            listOf(step.source) + step.transfers.map { it.target } + step.reviewedTransfers.map { it.target }
        }
        assertTrue(sites.all { it.enclosing.file.stableValue.contains("/src/test/kotlin/") })
        assertTrue(steps.flatMap { it.reviewedTransfers }.isEmpty())
        assertTrue(sites.none { it.enclosing.name.value == "unrelated" })
    }
}
