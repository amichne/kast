package io.github.amichne.kast.runtime.hosted.workspace

import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.project.Project
import com.intellij.util.ThreeState
import java.lang.reflect.Proxy
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
        val callback = object : ExternalProjectRefreshCallback {}
        val spec = quietWorkspaceImport(project, callback).build()
        assertEquals(ProgressExecutionMode.IN_BACKGROUND_ASYNC, spec.progressExecutionMode)
        assertTrue(spec.shouldImportProjectData())
        assertFalse(spec.isActivateBuildToolWindowOnStart)
        assertFalse(spec.isActivateBuildToolWindowOnFailure)
        assertEquals(ThreeState.NO, spec.isNavigateToError)
        assertNotNull(spec.callback)
    }
}
