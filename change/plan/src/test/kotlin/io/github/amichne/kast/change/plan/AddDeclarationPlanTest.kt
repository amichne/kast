package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddDeclarationPlannedEdit
import io.github.amichne.kast.change.contract.AddDeclarationPlanningFailure
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddDeclarationPlanTest {
    private val fixture = AddDeclarationPlanFixture()
    private val planner = PureAddDeclarationPlanningService()

    @Test
    fun `equivalent evidence enumeration produces one complete deterministic plan`() {
        val firstRequest = fixture.request(reverseEvidence = false)
        val secondRequest = fixture.request(reverseEvidence = true)

        val first = planner.plan(firstRequest).planned()
        val second = planner.plan(secondRequest).planned()

        assertEquals(first.planId, second.planId)
        assertEquals(first.evidence.fingerprint, second.evidence.fingerprint)
        assertSame(firstRequest.target, first.target)
        assertEquals(firstRequest.target.lease, first.sourceSnapshot.lease)
        assertEquals(firstRequest.target.workspaceState, first.sourceSnapshot.workspaceState)
        assertEquals(firstRequest.target.file, first.sourceSnapshot.file)
        assertEquals(firstRequest.target.content, first.sourceSnapshot.content)
        assertEquals(firstRequest.expectedSemanticDelta, first.expectedSemanticDelta)
        assertEquals(AddDeclarationObligation.entries, first.requiredVerification.obligations)
        val edit = first.plannedEdits.single() as AddDeclarationPlannedEdit.InsertAfterDeclaration
        assertEquals(firstRequest.target.file, edit.file)
        assertEquals(firstRequest.target.range, edit.anchor)
        assertEquals(firstRequest.declaration, edit.declaration)
        assertEquals(2, first.evidence.relations.size)
        assertEquals(2, first.evidence.traversals.size)
        assertEquals(2, first.evidence.diagnostics.size)
    }

    @Test
    fun `classlike target plans an insertion inside its body`() {
        val plan = PureAddDeclarationPlanningService().plan(
            AddDeclarationPlanFixture(symbolKind = CompilerSymbolKind.CLASSLIKE).request(),
        ).planned()

        assertInstanceOf(
            AddDeclarationPlannedEdit.InsertIntoClassBody::class.java,
            plan.plannedEdits.single(),
        )
        assertInstanceOf(
            SourceTextMutation.InsertIntoClassBody::class.java,
            plan.writes.entries.single().mutations.single(),
        )
    }

    @Test
    fun `required incomplete evidence fails closed before plan construction`() {
        val complete = fixture.request()
        val cases = listOf(
            complete.copy(
                evidence = complete.evidence.copy(
                    relations = listOf(fixture.qualifiedRelation()),
                ),
            ) to AddDeclarationPlanningFailure.RELATION_EVIDENCE_INCOMPLETE,
            complete.copy(
                evidence = complete.evidence.copy(
                    traversals = listOf(fixture.rejectedTraversal()),
                ),
            ) to AddDeclarationPlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE,
            complete.copy(
                evidence = complete.evidence.copy(
                    diagnostics = listOf(fixture.qualifiedDiagnostic()),
                ),
            ) to AddDeclarationPlanningFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE,
        )

        cases.forEach { (request, expected) ->
            assertEquals(expected, planner.plan(request).rejected(), expected.name)
        }
    }

    @Test
    fun `planning surface has no mutation operation or IntelliJ write classpath`() {
        val forbiddenMethodNames = setOf("apply", "mutate", "write", "persist", "verify", "recover")

        assertTrue(
            AddDeclarationChangePlan::class.java.methods.none { method ->
                method.name in forbiddenMethodNames
            },
        )
        assertTrue(
            AddDeclarationPlanRequest::class.java.methods.none { method ->
                method.name in forbiddenMethodNames
            },
        )
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.intellij.openapi.command.WriteCommandAction")
        }
    }

    private fun AddDeclarationPlanResult.planned(): AddDeclarationChangePlan = when (this) {
        is AddDeclarationPlanResult.Planned -> plan
        is AddDeclarationPlanResult.Rejected -> error(failure.toString())
    }

    private fun AddDeclarationPlanResult.rejected(): AddDeclarationPlanningFailure = when (this) {
        is AddDeclarationPlanResult.Planned -> error("expected rejection")
        is AddDeclarationPlanResult.Rejected -> failure
    }
}
