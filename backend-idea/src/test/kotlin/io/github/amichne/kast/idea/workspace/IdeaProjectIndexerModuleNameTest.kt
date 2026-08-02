package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.fields.RelationshipIndexingModulePriorityDepth
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleIdentity
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IdeaProjectIndexerModuleNameTest {
    private val workspaceRoot = Path.of("/workspace/kast")
    private val sourceFilePolicy = SourceIndexFilePolicy.forWorkspace(workspaceRoot)

    @Test
    fun `index module name uses Gradle project path and testFixtures source set`() {
        val filePath = "/workspace/kast/analysis-api/src/testFixtures/kotlin/io/github/FakeBackend.kt"

        val module = indexedModuleIdentityForFilePath(
            ideaModule = IdeaWorkspaceModuleIdentity.of("analysis-api.main"),
            filePath = sourcePath(filePath),
            sourceSet = GradleSourceSetName.parse("testFixtures"),
        )

        assertEquals(sourceModule(":analysis-api", "testFixtures"), module)
    }

    @Test
    fun `index module name supports nested Gradle projects`() {
        val filePath = "/workspace/kast/features/payments/src/main/kotlin/Payment.kt"

        val module = indexedModuleIdentityForFilePath(
            ideaModule = IdeaWorkspaceModuleIdentity.of("payments.main"),
            filePath = sourcePath(filePath),
            sourceSet = GradleSourceSetName.parse("main"),
        )

        assertEquals(sourceModule(":features:payments", "main"), module)
    }

    @Test
    fun `index module name falls back to IDEA module name outside Gradle layout`() {
        val filePath = "/workspace/kast/generated/Foo.kt"

        val module = indexedModuleIdentityForFilePath(
            ideaModule = IdeaWorkspaceModuleIdentity.of("scratch"),
            filePath = sourcePath(filePath),
            sourceSet = null,
        )

        assertEquals(sourceModule("scratch"), module)
    }

    @Test
    fun `module priority order merges duplicate indexed module specs before sorting`() {
        val specs = listOf(
            IdeaModuleSpec(":analysis-api", listOf(":build-logic")),
            IdeaModuleSpec(":analysis-api", listOf(":index-store")),
            IdeaModuleSpec(":build-logic", emptyList()),
            IdeaModuleSpec(":index-store", emptyList()),
        )

        val order = computeModulePriorityOrder(
            activeModule = null,
            moduleSpecs = specs,
            dependentModuleGraph = emptyMap(),
            depth = RelationshipIndexingModulePriorityDepth(2),
        )

        assertEquals(
            listOf(":build-logic", ":index-store", ":analysis-api"),
            order,
        )
    }

    @Test
    fun `module priority order ignores self dependencies introduced by duplicate source set modules`() {
        val specs = listOf(
            IdeaModuleSpec(":analysis-api", listOf(":analysis-api", ":index-store")),
            IdeaModuleSpec(":analysis-api", listOf(":build-logic")),
            IdeaModuleSpec(":build-logic", emptyList()),
            IdeaModuleSpec(":index-store", emptyList()),
        )

        val order = computeModulePriorityOrder(
            activeModule = null,
            moduleSpecs = specs,
            dependentModuleGraph = emptyMap(),
            depth = RelationshipIndexingModulePriorityDepth(2),
        )

        assertEquals(
            listOf(":build-logic", ":index-store", ":analysis-api"),
            order,
        )
    }

    @Test
    fun `persisted work prioritizes critical then source set then module then path`() {
        val critical = sourcePath("/workspace/kast/critical/src/test/CriticalTest.kt")
        val pathsByModule = listOf(
            IndexingPriorityEntry(sourcePath("/workspace/kast/slow/src/main/Slow.kt"), sourceModule(":slow", "main")),
            IndexingPriorityEntry(sourcePath("/workspace/kast/fast/src/test/FastTest.kt"), sourceModule(":fast", "test")),
            IndexingPriorityEntry(
                sourcePath("/workspace/kast/fast/src/testFixtures/Fixture.kt"),
                sourceModule(":fast", "testFixtures"),
            ),
            IndexingPriorityEntry(sourcePath("/workspace/kast/fast/src/main/Fast.kt"), sourceModule(":fast", "main")),
            IndexingPriorityEntry(sourcePath("/workspace/kast/misc/Other.kt"), sourceModule(":misc")),
            IndexingPriorityEntry(critical, sourceModule(":critical", "test")),
        )

        val ordered = prioritizeIndexingPaths(
            pathsByModule = pathsByModule,
            moduleOrder = listOf(SourceIndexModuleName.parse(":fast"), SourceIndexModuleName.parse(":slow")),
            criticalPaths = setOf(critical),
        )

        assertEquals(
            listOf(
                critical,
                sourcePath("/workspace/kast/fast/src/main/Fast.kt"),
                sourcePath("/workspace/kast/slow/src/main/Slow.kt"),
                sourcePath("/workspace/kast/fast/src/testFixtures/Fixture.kt"),
                sourcePath("/workspace/kast/fast/src/test/FastTest.kt"),
                sourcePath("/workspace/kast/misc/Other.kt"),
            ),
            ordered,
        )
    }

    private fun sourcePath(value: String): WorkspaceSourcePath =
        requireNotNull(sourceFilePolicy.sourcePath(Path.of(value)))

    private fun sourceModule(name: String, sourceSet: String? = null): SourceIndexModuleIdentity =
        SourceIndexModuleIdentity(
            name = SourceIndexModuleName.parse(name),
            sourceSet = sourceSet?.let(GradleSourceSetName::parse),
        )
}
