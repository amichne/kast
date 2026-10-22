package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import kotlinx.coroutines.runBlocking

class NativeRunnerControlledTypeProofTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "runner-type-proof-jdk"

    override fun runInDispatchThread() = false

    fun testRunnerControlledCallerProofTraversesAValidTypeDeeperThanTheLegacyCap() {
        lateinit var fixture: NativeReferenceFixture
        val nestedType = "Box<".repeat(40) + "Leaf" + ">".repeat(40)
        val source =
            "package consumer\nclass Leaf\nclass Box<T>\nfun caller() {\n" +
                "fun nested(value: $nestedType) { proof.target() }\n}\n"
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(source.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        val stops = mutableListOf<IntellijReadCounter>()
        val observation =
            object : IntellijReadObservation by IntellijReadObservation.None {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    repeat(amount) { stops += counter }
                }
            }
        val bounded = runBlocking { fixture.read(RelationMeaning.Callers, observation) }
        assertFalse("The legacy type-depth cap must reject this control.", bounded is RelationCompilation.Complete)
        assertTrue(stops.contains(IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_WORK_LIMIT))
        stops.clear()
        val runner = runBlocking {
            fixture.read(
                RelationMeaning.Callers,
                observation,
                budget =
                    RelationBudget(
                        ResourceBudget.RunnerControlled,
                        fixture.request.budget.returnedBytes,
                        (ResultLimit.parse(10) as Refinement.Refined).value,
                    ),
            )
        }
        assertTrue("Expected complete caller proof, received $runner", runner is RelationCompilation.Complete)
        assertFalse(stops.contains(IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_WORK_LIMIT))
        val facts = (runner as RelationCompilation.Complete).batch.facts
        assertEquals(1, facts.size)
        assertEquals(source.indexOf("target()"), facts.single().occurrence.range.startInclusive)
    }
}
