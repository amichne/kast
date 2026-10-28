package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.project.Project
import com.intellij.util.ThreeState
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuietWorkspaceImportTest {
    @Test
    fun `native import spec retains data callback and suppresses activation and navigation`() {
        val project =
            Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
                when (method.name) {
                    "isDisposed" -> false
                    "toString" -> "quiet-import-fixture"
                    else -> null
                }
            } as Project
        val completion = CompletableFuture<Void>()
        val spec = quietWorkspaceImport(project, completion).build()
        assertEquals(ProgressExecutionMode.IN_BACKGROUND_ASYNC, spec.progressExecutionMode)
        assertTrue(spec.shouldImportProjectData())
        assertFalse(spec.isActivateBuildToolWindowOnStart)
        assertFalse(spec.isActivateBuildToolWindowOnFailure)
        assertEquals(ThreeState.NO, spec.isNavigateToError)
        assertNotNull(spec.callback)
        assertEquals(
            com.intellij.openapi.externalSystem.importing.ImportSpecBuilder::class.java.protectionDomain.codeSource,
            spec.callback!!.javaClass.protectionDomain.codeSource,
            "Build history must keep a platform-owned callback, without a Kast class reference",
        )
        var callbacks = 0
        completion.whenComplete { _, _ -> callbacks++ }
        assertEquals(1, completion.numberOfDependents)
        spec.callback!!.onSuccess(null)
        assertEquals(1, callbacks)
        assertEquals(0, completion.numberOfDependents)
        spec.callback!!.onFailure("late failure", null)
        assertEquals(1, callbacks)
    }
}
