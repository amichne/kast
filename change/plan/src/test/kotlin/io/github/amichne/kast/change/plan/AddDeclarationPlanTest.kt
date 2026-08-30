package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddDeclarationPlannedEdit
import io.github.amichne.kast.change.contract.AddDeclarationPlanningFailure
import io.github.amichne.kast.change.contract.HostedAddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.change.contract.matches
import io.github.amichne.kast.relation.contract.RelationReadResult
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
    fun `hosted plan survives canonical encode and decode`() {
        val planned = planner.plan(fixture.request()).planned()
        val encoded = HostedAddDeclarationPlanCodec.encode(planned)

        val reopened = HostedAddDeclarationPlanCodec.decode(
            encoded,
        ).let { decoded ->
            when (decoded) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> decoded.value
                is io.github.amichne.kast.kernel.Refinement.Rejected -> error(decoded.failure.toString())
            }
        }

        assertEquals(planned.planId, reopened.planId)
        assertEquals(planned.target.selector.fingerprint, reopened.target.selector.fingerprint)
        assertEquals(planned.writes.entries.single().source, reopened.writes.entries.single().source)
        assertEquals(planned.evidence, reopened.evidence)
        assertTrue(encoded.startsWith("{\"schemaVersion\":2,"))
    }

    @Test
    fun `base schema plan retains generation bound relation digest semantics`() {
        val request = fixture.request()
        val firstReopen = HostedAddDeclarationPlanCodec.decode(LEGACY_SCHEMA_ONE_PLAN).let { decoded ->
            when (decoded) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> decoded.value
                is io.github.amichne.kast.kernel.Refinement.Rejected -> error(decoded.failure.toString())
            }
        }
        val reencoded = HostedAddDeclarationPlanCodec.encode(firstReopen)
        val reopened = HostedAddDeclarationPlanCodec.decode(reencoded).let { decoded ->
            when (decoded) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> decoded.value
                is io.github.amichne.kast.kernel.Refinement.Rejected -> error(decoded.failure.toString())
            }
        }
        val observed = request.evidence.relations.map {
            it as RelationReadResult.Complete
        }

        assertEquals(LEGACY_SCHEMA_ONE_PLAN, reencoded)
        assertTrue(
            reopened.evidence.relations.all { expected ->
                observed.count { current -> reopened.evidence.matches(expected, current) } == 1
            },
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

/** Canonical document emitted by 4a2caab2c before relation-digest schema versioning. */
private const val LEGACY_SCHEMA_ONE_PLAN = """{"schemaVersion":1,"planId":"158fd0f608181114ddd568eb7a8d41c71ad41f554cc0bd33ad18c8e640011468","workspaceRoot":"/workspace","generation":11,"workspaceState":"state-11","sourcePath":"/workspace/app/src/main/kotlin/sample/Service.kt","sourceContent":"232a53c82115d3b23ca09253e6c4c04998f83c17c62fbdd221a0317040d9b631","sourceRootModule":"app","sourceRootBuildRoot":".","sourceRootProjectPath":":app","sourceRootSourceSet":"main","sourceRootLocation":"app/src/main/kotlin","scopeKind":"WORKSPACE","scopePrimary":null,"scopeSecondary":null,"scopeSourceKinds":"PRODUCTION_AND_TEST","scopeGeneratedSources":"INCLUDE","scopeLibraries":"EXCLUDE","selectorStart":10,"selectorEnd":17,"selectorName":"service","selectorQualifiedIdentity":"sample.Service.service","selectorKind":"FUNCTION","selectorCompilerIdentity":"FUNCTION|sample.Service.service","selectorFingerprint":"35876a3477a1df78b9aea06ce80cb92410da5180864d0449fcc57abdfccd8bde","declaration":"fun added(): Int = 1","expectedPackage":"sample","expectedName":"added","expectedKind":"FUNCTION","evidence":{"relations":[{"meaning":"REFERENCES","projection":"64:35876a3477a1df78b9aea06ce80cb92410da5180864d0449fcc57abdfccd8bde10:REFERENCES5:START1:81:84:10005:100001:01:01:0","stableDigest":"585951610886d32ed4863f6c3e62f14407c34ee6f112b060a49cd6ef964f866f"},{"meaning":"CALLERS","projection":"64:35876a3477a1df78b9aea06ce80cb92410da5180864d0449fcc57abdfccd8bde7:CALLERS5:START1:81:84:10005:100001:01:01:0","stableDigest":"1c6df44ea210186bf973a0241be4faf797d0bfd6d2e829ca57c4cc31facc3cc0"}],"traversals":["64:34fd03cb884a06429d80a1a9ef962b8d62081880c35622e0198f234309d87b105:START1:85:100001:84:10001:21:81:01:01:01:01:0","64:e242b9c1a3672bda1e54bdab3c100d23a3b9a1ec1b2d1193a35b41077fff994d5:START1:85:100001:84:10001:21:81:01:01:01:01:0"],"diagnostics":["10:/workspace2:1146:/workspace/app/src/main/kotlin/sample/Other.kt48:/workspace/app/src/main/kotlin/sample/Service.kt46:/workspace/app/src/main/kotlin/sample/Other.kt48:/workspace/app/src/main/kotlin/sample/Service.kt","10:/workspace2:1148:/workspace/app/src/main/kotlin/sample/Service.kt48:/workspace/app/src/main/kotlin/sample/Service.kt"],"fingerprint":"edd1530dcebfdb524a59d1a204dc966097338c38ed4df0d95feef12cdc8a13de"}}"""
