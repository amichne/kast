package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class IdeaWorkspaceFileInventoryTest {
    @Test
    fun `generation fingerprints path identity and module membership rather than cardinality`() {
        val original = snapshot(
            sourcePaths = listOf("/workspace/app/src/main/kotlin/App.kt"),
        )
        val equalCardinalityReplacement = snapshot(
            sourcePaths = listOf("/workspace/app/src/main/kotlin/Renamed.kt"),
        )
        val moduleAdded = IdeaWorkspaceFileInventorySnapshot.create(
            kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
            modules = original.modules + module(
                name = "lib",
                sourcePaths = listOf("/workspace/lib/src/main/kotlin/Lib.kt"),
            ),
        )
        val moduleRemoved = IdeaWorkspaceFileInventorySnapshot.create(
            kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
            modules = emptyList(),
        )

        assertNotEquals(original.generation, equalCardinalityReplacement.generation)
        assertNotEquals(original.generation, moduleAdded.generation)
        assertNotEquals(original.generation, moduleRemoved.generation)
    }

    @Test
    fun `generation is isolated to the requested file kind domain`() {
        val before = module(
            sourcePaths = listOf("/workspace/app/src/main/kotlin/App.kt"),
            scriptPaths = listOf("/workspace/build.gradle.kts"),
        )
        val sourceChanged = module(
            sourcePaths = listOf("/workspace/app/src/main/kotlin/Renamed.kt"),
            scriptPaths = listOf("/workspace/build.gradle.kts"),
        )
        val scriptChanged = module(
            sourcePaths = listOf("/workspace/app/src/main/kotlin/App.kt"),
            scriptPaths = listOf("/workspace/settings.gradle.kts"),
        )

        val scriptBefore = snapshot(WorkspaceFileKindDomain.SCRIPT_ONLY, before)
        val scriptAfterSourceChange = snapshot(WorkspaceFileKindDomain.SCRIPT_ONLY, sourceChanged)
        val scriptAfterScriptChange = snapshot(WorkspaceFileKindDomain.SCRIPT_ONLY, scriptChanged)
        val sourceBefore = snapshot(WorkspaceFileKindDomain.SOURCE_ONLY, before)
        val sourceAfterScriptChange = snapshot(WorkspaceFileKindDomain.SOURCE_ONLY, scriptChanged)

        assertEquals(scriptBefore.generation, scriptAfterSourceChange.generation)
        assertNotEquals(scriptBefore.generation, scriptAfterScriptChange.generation)
        assertEquals(sourceBefore.generation, sourceAfterScriptChange.generation)
    }

    @Test
    fun `snapshot preserves every owner while canonicalizing module evidence`() {
        val shared = "/workspace/shared/Shared.kt"
        val snapshot = IdeaWorkspaceFileInventorySnapshot.create(
            kindDomain = WorkspaceFileKindDomain.MIXED,
            modules = listOf(
                module(
                    name = "secondary",
                    contentRoots = listOf("/workspace/shared", "/workspace/shared"),
                    dependencyNames = listOf("main", "main"),
                    sourcePaths = listOf(shared),
                ),
                module(
                    name = "main",
                    contentRoots = listOf("/workspace", "/workspace/shared"),
                    sourcePaths = listOf(shared),
                ),
            ),
        )

        assertEquals(listOf("main", "secondary"), snapshot.modules.map { it.identity.value })
        assertEquals(
            listOf(shared),
            snapshot.module(IdeaWorkspaceModuleIdentity.of("main")).filePaths(WorkspaceFileKindDomain.MIXED),
        )
        assertEquals(
            listOf(shared),
            snapshot.module(IdeaWorkspaceModuleIdentity.of("secondary")).filePaths(WorkspaceFileKindDomain.MIXED),
        )
        assertEquals(listOf("/workspace/shared"), snapshot.modules.last().contentRoots)
        assertEquals(listOf("main"), snapshot.modules.last().dependencyModuleNames)
    }

    @Test
    fun `inventory returns one immutable snapshot instance from its reader`() {
        val expected = snapshot(sourcePaths = listOf("/workspace/App.kt"))
        val inventory = IdeaWorkspaceFileInventory { kindDomain ->
            assertEquals(WorkspaceFileKindDomain.SOURCE_ONLY, kindDomain)
            expected
        }

        val actual = inventory.snapshot(WorkspaceFileKindDomain.SOURCE_ONLY)

        assertSame(expected, actual)
    }

    private fun snapshot(
        kindDomain: WorkspaceFileKindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
        module: IdeaWorkspaceModuleSnapshot = module(),
    ): IdeaWorkspaceFileInventorySnapshot = IdeaWorkspaceFileInventorySnapshot.create(
        kindDomain = kindDomain,
        modules = listOf(module),
    )

    private fun snapshot(
        sourcePaths: List<String>,
    ): IdeaWorkspaceFileInventorySnapshot = snapshot(
        module = module(sourcePaths = sourcePaths),
    )

    private fun module(
        name: String = "main",
        sourceRoots: List<String> = listOf("/workspace/app/src/main/kotlin"),
        contentRoots: List<String> = listOf("/workspace/app"),
        dependencyNames: List<String> = emptyList(),
        sourcePaths: List<String> = emptyList(),
        scriptPaths: List<String> = emptyList(),
    ): IdeaWorkspaceModuleSnapshot = IdeaWorkspaceModuleSnapshot.create(
        identity = IdeaWorkspaceModuleIdentity.of(name),
        sourceRoots = sourceRoots,
        contentRoots = contentRoots,
        dependencyModuleNames = dependencyNames,
        sourceFilePaths = sourcePaths,
        scriptFilePaths = scriptPaths,
    )
}
