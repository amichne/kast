package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicToolContractTest {
    @Test
    fun `omitted null and explicit walk controls lower identically`() {
        val steps =
            listOf(
                PublicToolWalk(PublicToolRelation.CALLERS, strategy = PublicToolBoundedFanOutStrategy()),
                PublicToolWalk(PublicToolRelation.CALLERS, null, PublicToolBoundedFanOutStrategy(null)),
                PublicToolWalk(
                    PublicToolRelation.CALLERS,
                    null,
                    PublicToolBoundedFanOutStrategy(PublicToolDefaults.maximumEdgesPerNode),
                ),
            )
        val inputs = steps.mapIndexed { index, step ->
            val values = (BoundedProtocolList.create(listOf<PublicToolStep>(step)) as Refinement.Refined).value
            Json {
                    encodeDefaults = index != 0
                    explicitNulls = index != 0
                }
                .encodeToJsonElement(
                    PublicToolQuerySymbols.serializer(),
                    PublicToolQuerySymbols(PublicToolRunAction(PublicToolAllSource(), values)),
                )
        }
        val requests = inputs.map { input ->
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input)
            assertTrue(admitted is Refinement.Refined, admitted.toString())
            ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
        }
        assertEquals(requests[0], requests[1])
        assertEquals(requests[0], requests[2])
    }

    @Test
    fun `every authored example is admitted and every invalid example is rejected`() {
        PublicToolIdentity.entries.forEach { identity ->
            val examples = PublicToolContract.examples(identity)
            examples.examples.forEach { (name, example) ->
                val admitted = PublicToolContract.admit(identity, example.value)
                assertTrue(admitted is Refinement.Refined, "${identity.toolName}.$name: $admitted")
            }
            examples.invalidExamples.forEach { (name, example) ->
                val admitted = PublicToolContract.admit(identity, example.value)
                assertTrue(admitted is Refinement.Rejected, "${identity.toolName}.$name admitted")
            }
        }
    }

    @Test
    fun `replace body retains exact target and complete block text in a change intent`() {
        val target = (ProtocolText.parse("exact:v5:AAAAAAAAAAAAAAAAAAAAAQ") as Refinement.Refined).value
        val body = (ProtocolText.parse("{ return Unit }") as Refinement.Refined).value
        val raw = Json.encodeToJsonElement(PublicToolReplaceBody.serializer(), PublicToolReplaceBody(target, body))
        val admitted = PublicToolContract.admit(PublicToolIdentity.REPLACE_BODY, raw)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        assertEquals(
            ChangeRequest(ChangeIntentDocument.ReplaceBody(target, body)),
            ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Change).request,
        )
    }

    @Test
    fun `query symbols admits typed concatenation and structured filtering as ordered steps`() {
        val reference = (ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value
        val value = (ProtocolText.parse("class") as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val predicate =
            QueryPredicateDocument.Primitive(
                QueryPrimitiveFieldDocument.KIND,
                QueryPrimitiveOperatorDocument.EQUALS,
                value,
            )
        val steps =
            (BoundedProtocolList.create(
                    listOf<PublicToolStep>(
                        PublicToolConcat(PublicToolReferenceSource(refs)),
                        PublicToolWhere(
                            PublicToolPrimitivePredicate(PublicToolField.KIND, PublicToolOperator.EQUALS, value)
                        ),
                        PublicToolDistinctSymbols,
                    )
                ) as Refinement.Refined)
                .value
        val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolReferenceSource(refs), steps, null))
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val lowered =
            ((admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run).steps.values
        assertEquals(
            listOf(
                QueryStepDocument.Concat::class,
                QueryStepDocument.Where::class,
                QueryStepDocument.Distinct::class,
            ),
            lowered.map { it::class },
        )
        assertEquals(
            refs.values,
            ((lowered[0] as QueryStepDocument.Concat).input as QueryFromDocument.References).values.values.map {
                it.token
            },
        )
        assertEquals(predicate, (lowered[1] as QueryStepDocument.Where).predicate)
    }

    @Test
    fun `structured predicates preserve each legal primitive field and operator through lowering`() {
        val cases =
            listOf(
                Triple(QueryPrimitiveFieldDocument.NAME, QueryPrimitiveOperatorDocument.EQUALS, "PaymentService"),
                Triple(QueryPrimitiveFieldDocument.KIND, QueryPrimitiveOperatorDocument.NOT_EQUALS, "function"),
                Triple(QueryPrimitiveFieldDocument.FILE, QueryPrimitiveOperatorDocument.STARTS_WITH, "/workspace"),
                Triple(QueryPrimitiveFieldDocument.FILE, QueryPrimitiveOperatorDocument.ENDS_WITH, ".kt"),
                Triple(QueryPrimitiveFieldDocument.KIND, QueryPrimitiveOperatorDocument.STARTS_WITH, "func"),
            )
        cases.forEach { (field, operator, rawValue) ->
            val predicate =
                QueryPredicateDocument.Primitive(
                    field,
                    operator,
                    (ProtocolText.parse(rawValue) as Refinement.Refined).value,
                )
            val publicPredicate =
                PublicToolPrimitivePredicate(
                    PublicToolField.valueOf(field.name),
                    PublicToolOperator.valueOf(operator.name),
                    predicate.value,
                )
            val steps =
                (BoundedProtocolList.create(listOf<PublicToolStep>(PublicToolWhere(publicPredicate)))
                        as Refinement.Refined)
                    .value
            val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolAllSource(), steps, null))
            val admitted =
                PublicToolContract.admit(
                    PublicToolIdentity.QUERY_SYMBOLS,
                    Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
                ) as Refinement.Refined
            val lowered =
                ((admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run)
                    .steps
                    .values
                    .single()
            assertEquals(predicate, (lowered as QueryStepDocument.Where).predicate)
        }
    }

    @Test
    fun `query symbols source field lowers to canonical source projection`() {
        val refs =
            (BoundedProtocolList.create(listOf((ProtocolText.parse("NON_ISSUED") as Refinement.Refined).value))
                    as Refinement.Refined)
                .value
        val fields = (BoundedProtocolList.create(listOf(PublicToolFields.SOURCE)) as Refinement.Refined).value
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(
                    PublicToolQuerySymbols.serializer(),
                    PublicToolQuerySymbols(
                        PublicToolRunAction(
                            PublicToolReferenceSource(refs),
                            PublicToolDefaults.steps,
                            PublicToolSymbolsOutput(fields),
                        )
                    ),
                ),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(
            listOf(QuerySymbolFieldDocument.SOURCE),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
    }
}
