package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetachedClasspathUrlRefinementTest {
    @Test
    fun `classpath URL refinement matches the detached-model boundary contract`() {
        DetachedClasspathUrlRefinementContract.verify()
    }
}

internal object DetachedClasspathUrlRefinementContract {
    fun verify() {
        captureRetainsCanonicalIntellijClassRootProtocolsExactly()
        captureRejectsUnsupportedOpaqueAndAliasedUrlSpellings()
        oversizedBoundaryTextIsRejectedBeforeSemanticScans()
        exactObservedRootIsPrivateProofConsumedByModelConstruction()
    }

    private fun captureRetainsCanonicalIntellijClassRootProtocolsExactly() {
        val urls = listOf(
            "file:///workspace/kast/.fixture/classes %?#",
            "jar:///workspace/kast/.fixture/dependency %?#.jar!/classes %?#",
            "jrt:///Library/Java/JavaVirtualMachines/zulu %?#.jdk/Contents/Home!/java.base%?#",
        )

        val captured = assertInstanceOf(
            DetachedModelCapture.Captured::class.java,
            capture(urls),
        )

        assertEquals(urls.sorted(), captured.model.modules.single().classpath.map { it.url.value })
    }

    private fun captureRejectsUnsupportedOpaqueAndAliasedUrlSpellings() {
        val invalidUrls = listOf(
            "jar:file:///workspace/kast/.fixture/dependency.jar!/",
            "jar:///workspace/kast/.fixture/dependency.jar",
            "jrt:///Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home!/",
            "custom:///workspace/kast/.fixture/dependency.jar",
            "file:///workspace/kast/.fixture/../dependency.jar",
            "FILE:///workspace/kast/.fixture/dependency.jar",
            "file:/workspace/kast/.fixture/dependency.jar",
            "file:////workspace/kast/.fixture/dependency.jar",
            "file:///workspace/kast//.fixture/dependency.jar",
            "file://localhost/workspace/kast/.fixture/dependency.jar",
            "file:///workspace/kast/.fixture/classes/",
            "jar:///workspace/kast/.fixture/dependency.jar!/classes/",
            "jrt:///Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home!/java.base/",
        )

        invalidUrls.forEach { url ->
            val rejected = assertInstanceOf(
                DetachedModelCapture.Rejected::class.java,
                capture(listOf(url)),
                url,
            )
            assertEquals(
                setOf(DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY),
                rejected.failures,
                url,
            )
        }
    }

    private fun oversizedBoundaryTextIsRejectedBeforeSemanticScans() {
        assertEquals(
            TextFailure.TOO_LONG,
            rejectedFailure(refineIdentity(" ".repeat(DetachedModelLimits.MAX_IDENTITY_CHARS + 1))),
        )
        assertEquals(
            DetachedModelCaptureFailure.PATH_IDENTITY_TOO_LONG,
            rejectedFailure(
                ExactObservedWorkspaceRoot.refineObservedRoot(
                    " ".repeat(DetachedModelLimits.MAX_PATH_CHARS + 1),
                    FIXTURE_ROOT,
                ),
            ),
        )
        assertEquals(
            DetachedModelCaptureFailure.CLASSPATH_IDENTITY_TOO_LONG,
            rejectedFailure(
                refineClasspathUrl(
                    "\u0000" + "x".repeat(DetachedModelLimits.MAX_CLASSPATH_URL_CHARS),
                ),
            ),
        )
    }

    private fun exactObservedRootIsPrivateProofConsumedByModelConstruction() {
        val rootProof = refinedValue(
            ExactObservedWorkspaceRoot.refineObservedRoot(FIXTURE_ROOT.value, FIXTURE_ROOT),
        )
        val proofClass = ExactObservedWorkspaceRoot::class.java

        assertEquals(proofClass, rootProof.javaClass)
        assertTrue(proofClass.declaredConstructors.filterNot { it.isSynthetic }.all { constructor ->
            Modifier.isPrivate(constructor.modifiers)
        })
        val capturedMethods = DetachedIdeWorkspaceModel.Companion::class.java.declaredMethods
            .filter { method -> method.name.startsWith("captured") }
        assertTrue(capturedMethods.any { method -> method.parameterTypes.first() == proofClass })
        assertFalse(
            capturedMethods.any { method ->
                method.parameterTypes.first() in setOf(
                    String::class.java,
                    FIXTURE_ROOT::class.java,
                )
            },
        )
    }

    private fun <Value, Failure> rejectedFailure(
        refinement: Refinement<Value, Failure>,
    ): Failure = when (refinement) {
        is Refinement.Refined -> error("Expected rejection, observed ${refinement.value}")
        is Refinement.Rejected -> refinement.failure
    }

    private fun <Value, Failure> refinedValue(
        refinement: Refinement<Value, Failure>,
    ): Value = when (refinement) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> error("Expected refinement, observed ${refinement.failure}")
    }

    private fun capture(urls: List<String>): DetachedModelCapture = captureDetachedFixture(
        DetachedModelObservation.Observed(
            detachedModelBoundary(
                modules = listOf(
                    detachedModuleBoundary(
                        classpath = urls.map(::DetachedClasspathBoundary),
                    ),
                ),
            ),
        ),
    )
}
