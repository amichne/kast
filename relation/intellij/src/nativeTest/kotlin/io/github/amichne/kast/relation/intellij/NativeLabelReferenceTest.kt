package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import kotlinx.coroutines.runBlocking

/** A jump label such as `return@name` targets a lambda body; a `this@name` receiver label names its class. */
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

    fun testQualifiedThisLabelRemainsAReferenceToTheClass() {
        val fixture = fixture(RECEIVER_TARGET_SOURCE, RECEIVER_CALLER_SOURCE)
        val compilation = runBlocking { fixture.read(RelationMeaning.References) }
        assertTrue("received $compilation", compilation is RelationCompilation.Complete)
        assertEquals(
            "`this@target` names the enclosing class and must stay a confirmed reference",
            listOf(RECEIVER_CALLER_SOURCE.indexOf("target"), RECEIVER_TARGET_SOURCE.indexOf("@target") + 1).sorted(),
            (compilation as RelationCompilation.Complete)
                .batch
                .referenceOccurrences
                .map { it.occurrence.range.startInclusive }
                .sorted(),
        )
    }

    private fun fixture(
        targetSource: String = "package proof\nfun interface target { fun open(): Int }\n",
        callerSource: String = LABEL_SOURCE,
    ): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture(targetSource)
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(callerSource.toByteArray())
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

private const val RECEIVER_TARGET_SOURCE =
    "package proof\nclass target { val size = 1\n    inner class Inner { fun outer() = this@target.size }\n}\n"

private const val RECEIVER_CALLER_SOURCE = "package consumer\nfun use(value: proof.target) = value\n"
