package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueInvocationSelection
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Scripted native SOURCE/VAL facts prove producer conservation, not real K2 origin admission. */
internal class SourceFunctionProducerAdmissionTest : QueryImpactSourceAdmissionCase() {
    private val property = property("adapt", 260, 290)

    @Test
    fun `source acquisition keeps the property origin and emits the actual builtin invocation proof`() = runTest {
        var calls = 0
        lateinit var supplied: ValueProducerSeed
        val compiler = compiler { request ->
            assertEquals(0, calls++)
            seed(request, property).also { supplied = it }
        }
        val admitted = source().admitImpact(owner.lease, references, compiler, budget).refined()
        val producer = admitted.source.producers.single()
        val origin = producer.origin as ValueProducerOrigin.SourceFunction
        assertSame(supplied.origin, origin)
        assertEquals(property.compilerIdentity, origin.property.endpoint.compilerIdentity)
        assertEquals("kotlin.Function0.invoke", origin.invocation.signature.qualifiedIdentity.value)
        assertEquals(producer.invocation.resultSite(), producer.site)
        val output = producer.sourceFunctionDocument(origin, references).refined()
        assertOutput(output, origin)
        assertEquals(1, calls)
    }

    @Test
    fun `a supplied property or selector proof from another request cannot replace the source seed`() = runTest {
        val other = property("otherAdapt", 300, 330)
        for ((expected, substitute) in
            listOf(
                QueryImpactSourceFailureCode.CALLABLE_MISMATCH to other,
                QueryImpactSourceFailureCode.ANCHOR_MISMATCH to property,
            )) {
            var calls = 0
            val compiler = compiler { request ->
                assertEquals(0, calls++)
                val anchor =
                    if (expected == QueryImpactSourceFailureCode.ANCHOR_MISMATCH) range(25, 35) else request.anchor
                val replacement =
                    ValueProducerSeedRequest.create(
                            request.enclosing,
                            anchor,
                            SymbolSelector.issue(substitute.lease, substitute.scope, substitute.evidence),
                            request.budget,
                            request.boundary,
                        )
                        .refined()
                seed(replacement, substitute)
            }
            assertEquals(
                expected,
                source().admitImpact(owner.lease, references, compiler, budget).failure().admissionCode(),
            )
            assertEquals(1, calls)
        }
    }

    private fun assertOutput(
        output: ImpactWitnessDocument.ProducerSourceFunction,
        origin: ValueProducerOrigin.SourceFunction,
    ) {
        val encoded = Json.encodeToJsonElement(ImpactWitnessDocument.serializer(), output).jsonObject
        assertEquals(
            setOf("type", "site", "invocation", "enclosing", "execution", "property", "reference"),
            encoded.keys,
        )
        assertEquals("PRODUCER_SOURCE_FUNCTION", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("declaration", "compilerTarget", "domain", "fingerprint"),
            encoded.getValue("property").jsonObject.keys,
        )
        assertEquals(property.compilerIdentity.value, output.property.compilerTarget.compilerEvidence.identity)
        assertEquals(property.fingerprint.value, output.property.fingerprint.value)
        assertEquals(origin.reference.range.startInclusive, output.reference.range.start.value)
        assertEquals(origin.reference.range.endExclusive, output.reference.range.end.value)
        assertEquals(origin.reference.file.stableValue, output.reference.file.value)
        assertEquals(
            "kotlin.Function0.invoke",
            (output.invocation.callable.compilerEvidence.signature
                    as io.github.amichne.kast.protocol.contract.CompilerSignatureWireDocument.Function)
                .qualifiedIdentity,
        )
    }

    private fun source() =
        document()
            .copy(seeds = bounded(listOf(QueryImpactProducerDocument(token(owner), token(property), anchor(20, 35)))))

    private fun seed(request: ValueProducerSeedRequest, property: RelationEndpoint.Resolved): ValueProducerSeed {
        val call =
            ValueFunctionInvocation.fromCompiler(RelationEndpoint.subject(request.enclosing), range(20, 35), builtin())
                .refined()
        val proof =
            RevalidatedRelationEndpoint.validate(RelationEndpoint.subject(request.expectedCallable), property.evidence)
                .refined()
        val reference = RelationOccurrence.fromBoundary(call.enclosing.file, 20, 25).refined()
        val origin = ValueProducerOrigin.SourceFunction.fromCompiler(call, proof, reference).refined()
        val selection = ValueInvocationSelection.fromCompiler(call, request.anchor).refined()
        return ValueProducerSeed.fromSourceFunction(request, call.resultSite(), origin, selection).refined()
    }

    private fun compiler(supply: (ValueProducerSeedRequest) -> ValueProducerSeed) =
        object : ValueProducerSeedCompilerPort {
            override suspend fun seed(request: ValueProducerSeedRequest) =
                ValueProducerSeedRead.Seeded(supply(request), work(3))

            override suspend fun revalidate(
                selector: SymbolSelector,
                budget: RelationBudget,
            ): ValueModelDeclarationRead = error("unexpected declaration")
        }

    private fun property(name: String, start: Int, end: Int): RelationEndpoint.Resolved {
        val signature = CanonicalCompilerSignature.property("fixture.$name", null, emptyList(), "()->String").refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    owner.file,
                    start,
                    end,
                    name,
                    "fixture.$name",
                    CompilerSymbolKind.PROPERTY,
                    signature,
                )
                .refined()
        return RelationEndpoint.resolve(owner.lease, owner.scope, evidence, owner.constraints).refined()
    }

    private fun builtin() =
        SourceLessCallable.fromCompiler(
                CanonicalCompilerSignature.function("kotlin.Function0.invoke", null, emptyList(), emptyList(), 0)
                    .refined(),
                CompilerSymbolKind.FUNCTION,
                SourceLessCallableOrigin.LIBRARY,
                SourceLessCallableModuleKind.BUILTINS,
                SourceLessCallableModuleName.parse("kotlin-stdlib").refined(),
            )
            .refined()
}
