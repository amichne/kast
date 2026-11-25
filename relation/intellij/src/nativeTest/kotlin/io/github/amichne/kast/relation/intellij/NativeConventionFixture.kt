package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * One authored source file supplies every subject; K2 supplies each subject's identity before the full adapter runs.
 */
abstract class NativeConventionFixture : NativeReferenceFixtureTest() {
    override fun runInDispatchThread() = false

    protected fun conventionFixture(source: String): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture(callerSource = source)
            val errors = PsiTreeUtil.collectElementsOfType(fixture.source(), PsiErrorElement::class.java)
            assertEmpty("The fixture must parse", errors.toList())
        }
        return fixture
    }

    /** [name] is relative to the fixture package, so a member needs its class name. */
    protected fun NativeReferenceFixture.relation(name: String, meaning: RelationMeaning): RelationCompilation =
        runBlocking {
            val selected = readAction { relationRequest(name, meaning) }
            IntellijRelationCompilerQuery()
                .read(
                    project = project,
                    currentLease = selected.subject.lease,
                    request = selected,
                    modelCompilation = model,
                )
        }

    protected fun NativeReferenceFixture.complete(name: String, meaning: RelationMeaning): RelationBatch {
        val read = relation(name, meaning)
        assertTrue("Expected a complete $meaning read of $name, received $read", read is RelationCompilation.Complete)
        return (read as RelationCompilation.Complete).batch
    }

    private fun NativeReferenceFixture.relationRequest(name: String, meaning: RelationMeaning): RelationRequest {
        val function =
            PsiTreeUtil.collectElementsOfType(source(), KtNamedFunction::class.java).single {
                it.fqName?.asString() == "consumer.$name"
            }
        val projection = IntellijK2RelationProjection(project, request.subject.lease.workspaceRoot)
        val evidence = (projection.project(function) as IntellijRelationDeclarationProjection.Projected).evidence
        val subject =
            RelationEndpoint.resolve(
                lease = request.subject.lease,
                scope = request.searchScope,
                evidence = evidence,
                constraints = request.searchConstraints,
            )
        return RelationRequest.start(subject.nativeRefined(), meaning, request.budget)
    }

    private fun NativeReferenceFixture.source(): KtFile = psiManager.findFile(callerFile) as KtFile
}
