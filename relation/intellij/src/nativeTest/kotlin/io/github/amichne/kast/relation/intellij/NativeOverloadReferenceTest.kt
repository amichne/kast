package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import kotlinx.coroutines.runBlocking

/** An import names every overload; each call still resolves to exactly one of them. */
class NativeOverloadReferenceTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "overload-reference-jdk"

    override fun runInDispatchThread() = false

    fun testImportAndCallOfAnOverloadedExtensionReferenceTheSelectedOverload() {
        val fixture = fixture()
        val compilation = runBlocking { fixture.read(RelationMeaning.References) }
        assertTrue(
            "An overloaded import must not leave the reference read incomplete, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        assertEquals(
            listOf("import proof.target", "obs.target(\"x\")").map { CALLER_SOURCE.indexOf(it) + it.indexOf("target") },
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
            fixture = prepareReferenceFixture(SELECTED_OVERLOAD)
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.parent
                    .createChildData(this, "Overloads.kt")
                    .setBinaryContent(OTHER_OVERLOAD.toByteArray())
                fixture.callerFile.parent
                    .createChildData(this, "Other.kt")
                    .setBinaryContent(OTHER_PACKAGE.toByteArray())
                fixture.callerFile.setBinaryContent(CALLER_SOURCE.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }
}

private const val SELECTED_OVERLOAD =
    "package proof\ninline fun <V> Obs.target(element: String, crossinline action: () -> V): V = action()\n"

private const val OTHER_OVERLOAD =
    "package proof\nclass Obs\ninline fun <V> Obs.target(element: Int, crossinline action: () -> V): V = action()\n"

private const val OTHER_PACKAGE = "package other\nfun target(): Int = 0\n"

// The call selects the String overload. The Int call and the other package's import name different symbols.
private val CALLER_SOURCE =
    """
    package consumer
    import proof.Obs
    import proof.target
    import other.target as otherTarget
    fun caller(obs: Obs): Int = obs.target("x") { 1 } + obs.target(2) { 3 } + otherTarget()
    """
        .trimIndent()
