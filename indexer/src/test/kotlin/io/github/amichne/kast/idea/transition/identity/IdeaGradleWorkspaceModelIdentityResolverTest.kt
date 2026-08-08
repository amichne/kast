package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge
import io.github.amichne.kast.idea.authoredGradleSourceRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class IdeaGradleWorkspaceModelIdentityResolverTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `model collection order does not affect identity`() {
        assertEquals(
            IdeaGradleWorkspaceModelIdentityResolver.resolve(model(reverseCollections = false)),
            IdeaGradleWorkspaceModelIdentityResolver.resolve(model(reverseCollections = true)),
        )
    }

    @Test
    fun `Gradle project ownership affects identity`() {
        assertNotEquals(
            IdeaGradleWorkspaceModelIdentityResolver.resolve(model(appProjectPath = ":app")),
            IdeaGradleWorkspaceModelIdentityResolver.resolve(model(appProjectPath = ":renamed")),
        )
    }

    @Test
    fun `Gradle source set ownership affects identity`() {
        assertNotEquals(
            IdeaGradleWorkspaceModelIdentityResolver.resolve(model(appSourceSetName = "main")),
            IdeaGradleWorkspaceModelIdentityResolver.resolve(model(appSourceSetName = "integrationTest")),
        )
    }

    private fun model(
        reverseCollections: Boolean = false,
        appProjectPath: String = ":app",
        appSourceSetName: String = "main",
    ): IdeaGradleProjectLoadBridge.GradleWorkspaceModel {
        val appIdentity = IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, appProjectPath)
        val libIdentity = IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, ":lib")
        val appSourceRootPaths = ordered(
            listOf(workspaceRoot.resolve("app/src/main/kotlin"), workspaceRoot.resolve("app/src/main/java")),
            reverseCollections,
        )
        val appSourceRoots = appSourceRootPaths.map(::authoredGradleSourceRoot)
        val associations = ordered(
            listOf(
                IdeaGradleProjectLoadBridge.GradleModuleAssociation(
                    "app",
                    workspaceRoot,
                    workspaceRoot.resolve("app"),
                    appProjectPath,
                    false,
                    false,
                    ordered(
                        listOf(
                            IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(appSourceSetName, appSourceRoots),
                            IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(
                                "test",
                                listOf(authoredGradleSourceRoot(workspaceRoot.resolve("app/src/test/kotlin"))),
                            ),
                        ),
                        reverseCollections,
                    ),
                ),
                IdeaGradleProjectLoadBridge.GradleModuleAssociation(
                    "lib",
                    workspaceRoot,
                    workspaceRoot.resolve("lib"),
                    ":lib",
                    false,
                    false,
                    listOf(
                        IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(
                            "main",
                            listOf(authoredGradleSourceRoot(workspaceRoot.resolve("lib/src/main/kotlin"))),
                        ),
                    ),
                ),
            ),
            reverseCollections,
        )
        return IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            ordered(listOf(workspaceRoot, workspaceRoot.resolve("included")), reverseCollections),
            true,
            ordered(listOf(appIdentity, libIdentity), reverseCollections),
            ordered(
                listOf(
                    IdeaGradleProjectLoadBridge.LoadedGradleModule("app", appIdentity),
                    IdeaGradleProjectLoadBridge.LoadedGradleModule("lib", libIdentity),
                ),
                reverseCollections,
            ),
            ordered(
                appSourceRoots + authoredGradleSourceRoot(workspaceRoot.resolve("lib/src/main/kotlin")),
                reverseCollections,
            ),
            associations,
        )
    }

    private fun <T> ordered(values: List<T>, reverse: Boolean): List<T> =
        if (reverse) values.reversed() else values
}
