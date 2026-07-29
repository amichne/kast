package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.shared.hierarchy.EdgeDiscoveryBudget
import io.github.amichne.kast.shared.hierarchy.EdgeDiscoveryCompletion
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TestApplication
internal class IdeaHierarchyProviderBudgetTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private const val callSource = """
            package demo.calls

            fun target(): Int = 1
            fun first(): Int = target()
            fun second(): Int = target()
        """

        private const val typeSource = """
            package demo.types

            interface Root
            class First : Root
            class Second : Root
            class Third : Root
        """
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val callFileFixture = sourceRootFixture.psiFileFixture("Calls.kt", callSource)
    private val typeFileFixture = sourceRootFixture.psiFileFixture("Types.kt", typeSource)

    @Test
    fun `incoming call provider stops at the candidate budget`() {
        val project = projectFixture.get()
        val file = callFileFixture.get()
        waitUntilIndexesAreReady(project)
        val target = declaration<KtNamedFunction>(file, "target")
        val budget = EdgeDiscoveryBudget(maxCandidates = 1)

        val edges = IdeaCallEdgeResolver(
            project = project,
            workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(Path.of(file.virtualFile.path).parent),
        ).incomingEdges(
            target = target,
            budget = budget,
            onFileVisited = {},
        )

        assertEquals(1, edges.size)
        assertEquals(EdgeDiscoveryCompletion.CANDIDATE_LIMIT_REACHED, budget.completion)
    }

    @Test
    fun `subtype provider stops searching after the candidate budget rejects further work`() {
        val project = projectFixture.get()
        val file = typeFileFixture.get()
        waitUntilIndexesAreReady(project)
        val target = declaration<KtClassOrObject>(file, "Root")
        var visitedCandidates = 0
        val budget = EdgeDiscoveryBudget(
            maxCandidates = 1,
            timeoutCheck = {
                visitedCandidates += 1
                check(visitedCandidates <= 2) {
                    "Subtype search visited another candidate after the budget rejected further work"
                }
                false
            },
        )

        val edges = IdeaTypeEdgeResolver(project).subtypeEdges(target, budget)

        assertEquals(1, edges.size)
        assertEquals(2, visitedCandidates)
        assertEquals(EdgeDiscoveryCompletion.CANDIDATE_LIMIT_REACHED, budget.completion)
    }

    private inline fun <reified T : com.intellij.psi.PsiElement> declaration(
        file: PsiFile,
        name: String,
    ): T = ApplicationManager.getApplication().runReadAction<T> {
        PsiTreeUtil
            .findChildrenOfType(file, T::class.java)
            .single { declaration -> (declaration as? org.jetbrains.kotlin.psi.KtNamedDeclaration)?.name == name }
    }
}
