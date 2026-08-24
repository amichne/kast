package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path

class ExactTopologyProjectResolutionTest {
    @Test
    fun `malformed project base path is closed project unavailable data`(@TempDir root: Path) {
        val resolution = resolveExactTopologyProject(
            canonicalRoot(root),
            listOf(project("\u0000")),
        )

        val rejected = assertInstanceOf(
            ExactTopologyProjectResolution.Rejected::class.java,
            resolution,
        )
        assertEquals(TopologyExtractionFailure.PROJECT_UNAVAILABLE, rejected.failure)
    }

    @Test
    fun `one live project at the canonical root is selected`(@TempDir root: Path) {
        val canonical = root.toRealPath()
        val project = project(canonical.toString())

        val found = assertInstanceOf(
            ExactTopologyProjectResolution.Found::class.java,
            resolveExactTopologyProject(canonicalRoot(canonical), listOf(project)),
        )

        assertSame(project, found.project)
    }

    private fun canonicalRoot(path: Path): CanonicalWorkspaceRoot = when (
        val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(path.toRealPath())
    ) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error(admitted.failure)
    }

    private fun project(basePath: String): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "getBasePath" -> basePath
            "isDisposed" -> false
            "toString" -> "project($basePath)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.singleOrNull()
            else -> null
        }
    } as Project
}
