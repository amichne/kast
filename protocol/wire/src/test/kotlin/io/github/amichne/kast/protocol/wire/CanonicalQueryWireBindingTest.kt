package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalQueryWireBindingTest {
    @Test
    fun `query request round trip retains explicit enumeration and typed stages`() {
        val request = QueryRunRequest(
            from = QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(
                        bounded(listOf(QuerySourceSetDocument.MAIN)),
                        QueryDirectoryScopeDocument(text("services/payments"), QueryContainmentDocument.DESCENDANTS),
                        QueryPackageScopeDocument(text("com.acme.payments"), QueryContainmentDocument.DESCENDANTS),
                    ),
                    bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                ),
            ),
            steps = bounded(listOf(
                QueryStepDocument.Where(
                    QueryPredicateDocument.Visibility(bounded(listOf(QueryVisibilityDocument.PUBLIC))),
                ),
                QueryStepDocument.Related(RelationKindDocument.INHERITORS),
                QueryStepDocument.Distinct,
            )),
            output = QueryOutputDocument.Symbols(
                bounded(listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION)),
            ),
            execution = QueryExecutionDocument(
                QueryExecutionKindDocument.EXHAUSTIVE,
                QueryExecutionBudgetDocument.INTERACTIVE,
            ),
        )

        val encoded = CanonicalOperationWireBindings.queryRun.encodeRequest(request)
        assertTrue(encoded is WireEncoding.Encoded)
        val decoded = CanonicalOperationWireBindings.queryRun.decodeRequest(
            WireRequestEnvelope.admit((encoded as WireEncoding.Encoded).document).admittedRequest(),
        )
        assertEquals(request, (decoded as WireDecoding.Decoded).value)
    }

    @Test
    fun `query fragments reject blank name but admit all without dummy text`() {
        val scope = """{"sourceSets":["main"],"directory":null,"packageName":null}"""
        assertTrue(
            CanonicalQueryRequestFragments.admit(
                """{"type":"symbols","match":{"type":"all"},"scope":$scope,"declarationKinds":["class"]}""",
                "[]",
                """{"type":"symbols","fields":["name"]}""",
                """{"kind":"exhaustive","budget":"interactive"}""",
            ) is QueryRequestFragmentAdmission.Admitted,
        )
        assertTrue(
            CanonicalQueryRequestFragments.admit(
                """{"type":"references","values":[{"kind":"exact-symbol","token":"exact:v2:opaque"}]}""",
                "[]",
                """{"type":"symbols","fields":["name"]}""",
                """{"kind":"exhaustive","budget":"interactive"}""",
            ) is QueryRequestFragmentAdmission.Admitted,
        )
        assertTrue(
            CanonicalQueryRequestFragments.admit(
                """{"type":"references","values":[{"kind":"declaration-candidate","token":"candidate:v2:opaque"}]}""",
                """[{"type":"inspect"}]""",
                """{"type":"symbols","fields":["name"]}""",
                """{"kind":"exhaustive","budget":"interactive"}""",
            ) is QueryRequestFragmentAdmission.Admitted,
        )
        assertEquals(
            QueryRequestFragmentAdmission.Rejected,
            CanonicalQueryRequestFragments.admit(
                """{"type":"symbols","match":{"type":"name","text":"   ","matching":"fuzzy"},"scope":$scope,"declarationKinds":["class"]}""",
                "[]",
                """{"type":"symbols","fields":["name"]}""",
                """{"kind":"exhaustive","budget":"interactive"}""",
            ),
        )
    }

    private fun text(raw: String): ProtocolText = when (val value = ProtocolText.parse(raw)) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error(value.failure)
    }

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        when (val value = BoundedProtocolList.create(values)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error(value.failure)
        }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest =
        when (this) {
            is WireRequestAdmission.Admitted -> request
            is WireRequestAdmission.Rejected -> error(failure)
        }
}
