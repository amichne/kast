package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceRefreshScopeTest {
    private val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/w")) as Refinement.Refined).value

    /** A Gradle-shaped checkout with a nested foreign checkout and dependency trees that own no read input. */
    private val checkout =
        setOf(
            "/w/cli",
            "/w/cli/src/main",
            "/w/cli/src/main/kotlin",
            "/w/cli/src/main/kotlin/io",
            "/w/cli/src/main/kotlin-gen",
            "/w/cli/src/test/kotlin",
            "/w/build/generated/src",
            "/w/libs/local.jar",
            "/w/gradle",
            "/w/gradle/wrapper",
            "/w/.amp/in/other-checkout/src/main/kotlin",
            "/w/node_modules/package",
        )

    private val imported =
        WorkspaceRefreshInputs(
            sourceRoots =
                listOf(
                    "/w/cli/src/main/kotlin",
                    "/w/cli/src/main/kotlin/io",
                    "/w/cli/src/main/kotlin-gen",
                    "/w/cli/src/main/java",
                    "/w/cli/src/test/kotlin",
                    "/w/build/generated/src",
                    "/w/fresh/src/main/kotlin",
                    "/w-other/src/main/kotlin",
                ),
            libraryRoots = listOf("/w/libs/local.jar", "/home/.gradle/caches/stdlib.jar"),
            modelDirectories = listOf("/w", "/w/cli"),
            modelFiles =
                listOf(
                    "/w/gradle.properties",
                    "/w/gradle/libs.versions.toml",
                    "/w/gradle/wrapper/gradle-wrapper.properties",
                    "/home/.gradle/init.gradle",
                ),
        )

    @Test
    fun `imported inputs bound the refresh and unowned trees stay outside it`() {
        assertEquals(
            WorkspaceRefreshScope.Imported(
                recursive =
                    listOf(
                        "/w/build/generated/src",
                        "/w/cli/src/main/kotlin",
                        "/w/cli/src/main/kotlin-gen",
                        "/w/cli/src/test/kotlin",
                        "/w/libs/local.jar",
                    ),
                flat = listOf("/w", "/w/cli", "/w/cli/src/main", "/w/gradle", "/w/gradle/wrapper"),
            ),
            planWorkspaceRefresh(root, imported) { it in checkout },
        )
    }

    @Test
    fun `directories inside a recursive root are not listed again`() {
        val existing = setOf("/w/src", "/w/src/module")
        val inputs =
            WorkspaceRefreshInputs(
                sourceRoots = listOf("/w/src", "/w/src/gen/kotlin"),
                libraryRoots = emptyList(),
                modelDirectories = listOf("/w/src/module"),
                modelFiles = listOf("/w/src/module/build.gradle.kts"),
            )

        assertEquals(
            WorkspaceRefreshScope.Imported(recursive = listOf("/w/src"), flat = listOf("/w")),
            planWorkspaceRefresh(root, inputs) { it in existing },
        )
    }

    @Test
    fun `without an imported source root the whole workspace stays covered`() {
        val inputs =
            WorkspaceRefreshInputs(
                sourceRoots = listOf("/w-other/src/main/kotlin"),
                libraryRoots = listOf("/w/libs/local.jar"),
                modelDirectories = listOf("/w"),
                modelFiles = listOf("/w/settings.gradle.kts"),
            )

        assertEquals(WorkspaceRefreshScope.WholeWorkspace, planWorkspaceRefresh(root, inputs) { true })
    }
}
