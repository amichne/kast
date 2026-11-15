package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import kotlinx.coroutines.runBlocking

/** A `return@name` label targets a lambda body, never the declaration whose name it spells. */
class NativeLabelReferenceTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "label-reference-jdk"

    override fun runInDispatchThread() = false

    fun testSamConstructorLabelIsNotAReferenceToTheInterface() {
        val fixture = fixture()
        val compilation = runBlocking { fixture.read(RelationMeaning.References) }
        assertTrue(
            "A same-named label must not leave the reference read incomplete, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        assertEquals(
            listOf(": proof.target", "proof.target {").map { LABEL_SOURCE.indexOf(it) + it.indexOf("target") },
            (compilation as RelationCompilation.Complete)
                .batch
                .referenceOccurrences
                .map { it.occurrence.range.startInclusive }
                .sorted(),
        )
    }

    private fun fixture(): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture("package proof\nfun interface target { fun open(): Int }\n")
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(LABEL_SOURCE.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }
}

private val LABEL_SOURCE =
    """
    package consumer
    fun caller(flag: Boolean): proof.target = proof.target { if (flag) return@target 1; 2 }
    """
        .trimIndent()
