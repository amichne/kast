package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBranchCompletionDocument
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactIntArithmeticInputDocument
import io.github.amichne.kast.protocol.contract.ImpactIntArithmeticRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactIntBuiltinEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactJavaDivisionInput
import io.github.amichne.kast.protocol.contract.ImpactJavaDivisionOperands
import io.github.amichne.kast.protocol.contract.ImpactJavaDivisionType
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactPrimitiveSchemaTest {
    @Test
    fun `Java division records a normal dividend dependency in the selected frame`() {
        val document = independentImpactSchemaWithDefinitions(ImpactTransferEvidenceDocument.serializer())
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val f = Fixture()
        val owner = ImpactExecutionReferenceDocument(f.declaration)
        val caller = f.declaration.copy(file = text("/workspace/Caller.kt"))
        val call = ImpactInvocationReferenceDocument(ImpactSourceRangeDocument(offset(31), offset(50)), f.declaration)
        val frames = bounded(listOf(ImpactCallFrameDocument(caller, call, owner)))
        val source = f.site.copy(role = ImpactValueRoleDocument.LocalRead, callStack = frames)
        val target = f.site.copy(range = ImpactSourceRangeDocument(offset(10), offset(15)), callStack = frames)
        val proof =
            ImpactTransferEvidenceDocument.JavaIntDivision(
                inputRange = source.range,
                divisorRange = ImpactSourceRangeDocument(offset(14), offset(15)),
                resultRange = target.range,
                divisor = 4,
                execution = owner,
                callStack = frames,
                input = ImpactJavaDivisionInput.DIVIDEND,
                operandTypes = ImpactJavaDivisionOperands.INT_INT,
                resultType = ImpactJavaDivisionType.INT,
                completion = ImpactBranchCompletionDocument.NORMAL_COMPLETION,
            )
        val transfer =
            ImpactCompilerTransferDocument(source, target, ImpactTransferKindDocument.PRIMITIVE_DERIVATION, proof)
        assertTrue(transfer.admitsEvidence())
        assertTrue(!transfer.copy(evidence = proof.copy(callStack = bounded(emptyList()))).admitsEvidence())
        val raw = Json.encodeToString(ImpactTransferEvidenceDocument.serializer(), proof)
        assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
        for (field in listOf("DIVIDEND", "INT_INT", "NORMAL_COMPLETION", "JAVA_INT_DIVISION_V1")) {
            assertTrue(raw.contains(field))
            assertTrue(schema.validate(raw.replace(field, "UNKNOWN"), InputFormat.JSON).isNotEmpty())
        }
    }

    @Test
    fun `schema requires primitive operation evidence and rejects unknown operand slots`() {
        val document = independentImpactSchemaWithDefinitions(ImpactCompilerTransferDocument.serializer())
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val f = Fixture()
        val target = f.site.copy(range = ImpactSourceRangeDocument(offset(9), offset(20)))
        val signature =
            io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.function(
                    "kotlin.Int.minus",
                    null,
                    emptyList(),
                    listOf("kotlin/Int"),
                    0,
                )
                .refined()
        val identity = io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity.fromCanonicalSignature(signature)
        val proof =
            ImpactTransferEvidenceDocument.PrimitiveDerivation(
                rule = ImpactIntArithmeticRuleDocument.INT_MINUS_V1,
                compilerIdentity = text(identity.value),
                authority = ImpactIntBuiltinEvidenceDocument.K2_BUILTINS_METADATA,
                moduleName = text("fixture stdlib"),
                input = ImpactIntArithmeticInputDocument.ARGUMENT_0,
                inputRange = f.site.range,
                resultRange = target.range,
                basis = f.declaration.basis,
            )
        val transfer =
            ImpactCompilerTransferDocument(
                source = f.site,
                target = target,
                kind = ImpactTransferKindDocument.PRIMITIVE_DERIVATION,
                evidence = proof,
            )
        assertTrue(transfer.admitsEvidence())
        assertTrue(
            !transfer.copy(evidence = proof.copy(rule = ImpactIntArithmeticRuleDocument.INT_PLUS_V1)).admitsEvidence()
        )
        val raw = Json.encodeToString(ImpactCompilerTransferDocument.serializer(), transfer)
        assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
        for (malformed in
            listOf(
                raw.replace("\"input\":\"ARGUMENT_0\"", "\"input\":\"ARGUMENT_1\""),
                raw.replace("\"input\":\"ARGUMENT_0\",", ""),
                raw.replace("\"authority\":\"K2_BUILTINS_METADATA\"", "\"authority\":\"REVIEWED_LIBRARY\""),
            )) {
            assertTrue(schema.validate(malformed, InputFormat.JSON).isNotEmpty(), malformed)
        }
    }

    private inner class Fixture {
        val declaration =
            ImpactDeclarationReferenceDocument(
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).refined(),
                ),
                text("/workspace/File.kt"),
                ImpactSourceRangeDocument(offset(0), offset(100)),
                text("canonical-signature-sha256-v1|" + "a".repeat(64)),
            )
        val site =
            ImpactValueSiteReferenceDocument(
                declaration,
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.ExpressionResult,
            )
    }

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
