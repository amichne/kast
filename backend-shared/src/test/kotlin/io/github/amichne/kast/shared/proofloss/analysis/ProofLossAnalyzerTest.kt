package io.github.amichne.kast.shared.proofloss.analysis

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.shared.proofloss.ir.*
import io.github.amichne.kast.shared.proofloss.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProofLossAnalyzerTest {
    private val predicateId = PredicateId.valid("zip.five-digits")
    private val boundaryId = BoundaryId.valid("address.submit")
    private val predicateCall = key("example.isZip")
    private val boundaryCall = key("example.submit")
    private val materializerCall = key("example.Zip.parse")
    private val model = (ProofModel.build(
        listOf(PredicateDescriptor(predicateId, predicateCall, index(0), setOf(MaterializerDescriptor.Total(materializerCall)))),
        listOf(BoundaryDescriptor(boundaryId, boundaryCall, listOf(Obligation(index(0), predicateId)))),
    ) as ModelBuildResult.Valid).model
    private val path = NormalizedPath.ofAbsolute(Path.of("/workspace/Test.kt"))
    private val raw = value(10)
    private val alias = value(20)
    private val refined = value(30)

    @Test fun `positive guard followed by raw boundary reports complete witness`() {
        val finding = analyze(
            Statement.If(PredicateCondition(predicateId, raw, PredicatePolarity.POSITIVE, span(40)), Block(Statement.BoundaryCall(boundaryId, mapOf(index(0) to raw), span(50)))),
        ).single()
        assertEquals(raw, finding.subject)
        assertEquals(raw, finding.boundaryArgument)
        assertTrue(finding.proof is ProofEstablishment.PredicateGuard)
    }

    @Test fun `rejecting negative guard establishes fact after continuing branch`() {
        val finding = analyze(
            Statement.If(PredicateCondition(predicateId, raw, PredicatePolarity.NEGATED, span(40)), Block(Statement.Exit(ExitKind.RETURN, span(41)))),
            Statement.BoundaryCall(boundaryId, mapOf(index(0) to raw), span(50)),
        ).single()
        assertEquals(span(40), finding.proof.location)
    }

    @Test fun `non dominating guard produces no finding`() {
        assertTrue(analyze(Statement.If(PredicateCondition(predicateId, raw, PredicatePolarity.POSITIVE, span(40)), Block()), Statement.BoundaryCall(boundaryId, mapOf(index(0) to raw), span(50))).isEmpty())
    }

    @Test fun `materialized value carries proof while discarded materialization leaves raw finding`() {
        val binding = Statement.Let(refined, ValueExpression.Materialize(predicateId, raw, materializerCall), span(45))
        assertTrue(analyze(binding, Statement.BoundaryCall(boundaryId, mapOf(index(0) to refined), span(50))).isEmpty())
        val finding = analyze(binding, Statement.BoundaryCall(boundaryId, mapOf(index(0) to raw), span(50))).single()
        assertTrue(finding.proof is ProofEstablishment.MaterializationSuccess)
    }

    @Test fun `immutable alias retains origin and path`() {
        val finding = analyze(
            Statement.Let(alias, ValueExpression.Alias(raw), span(35)),
            Statement.If(PredicateCondition(predicateId, raw, PredicatePolarity.POSITIVE, span(40)), Block(Statement.BoundaryCall(boundaryId, mapOf(index(0) to alias), span(50)))),
        ).single()
        assertEquals(listOf(raw, alias), finding.valuePath)
    }

    @Test fun `boundary without prior proof is not a generic validation warning`() {
        assertTrue(analyze(Statement.BoundaryCall(boundaryId, mapOf(index(0) to raw), span(50))).isEmpty())
    }

    private fun analyze(vararg statements: Statement) = ProofLossAnalyzer(model).analyze(
        FunctionIr(function(1), setOf(raw), Block(*statements)),
    )
    private fun span(offset: Int) = SourceSpan(path, SourceOffset.valid(offset), SourceOffset.valid(offset + 1))
    private fun value(offset: Int) = TrackedValueId(path, SourceOffset.valid(offset))
    private fun function(offset: Int) = FunctionId(path, SourceOffset.valid(offset))
    private fun index(value: Int) = (ArgumentIndex.parse(value) as ArgumentIndexParseResult.Valid).value
    private fun key(id: String) = CallableKey(CallableIdKey.valid(id), CallableKind.FUNCTION, null, emptyList(), listOf(KotlinTypeKey.valid("kotlin.String")), 0)
}

private fun PredicateId.Companion.valid(raw: String) = (parse(raw) as TextParseResult.Valid).value
private fun BoundaryId.Companion.valid(raw: String) = (parse(raw) as TextParseResult.Valid).value
private fun CallableIdKey.Companion.valid(raw: String) = (parse(raw) as TextParseResult.Valid).value
private fun KotlinTypeKey.Companion.valid(raw: String) = (parse(raw) as TextParseResult.Valid).value
