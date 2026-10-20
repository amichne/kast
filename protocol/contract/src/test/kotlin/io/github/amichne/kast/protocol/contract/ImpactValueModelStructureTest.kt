package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactValueModelStructureTest {
    @Test
    fun `consistent exact source context and artifact fields admit output structure`() {
        val f = ValueModelStructureCase()
        assertTrue(f.application.admitsValueModel(f.basis))
        assertFalse(
            f.application
                .copy(
                    source =
                        f.source.copy(
                            role =
                                ImpactValueRoleDocument.Receiver(
                                    f.call,
                                    ImpactReceiverKindDocument.DISPATCH,
                                )
                        )
                )
                .admitsValueModel(f.basis)
        )
        val other = f.call.copy(range = f.range(45, 71))
        assertFalse(
            f.application
                .copy(
                    source =
                        f.source.copy(
                            role =
                                ImpactValueRoleDocument.Receiver(
                                    other,
                                    ImpactReceiverKindDocument.EXTENSION,
                                )
                        )
                )
                .admitsValueModel(f.basis)
        )
        val wrongCondition = f.condition.copy(result = f.application.target.copy(range = f.range(80, 90)))
        assertFalse(f.application.copy(condition = wrongCondition).admitsValueModel(f.basis))
    }

    @Test
    fun `actual artifact review and semantic basis cannot contradict one another`() {
        val f = ValueModelStructureCase()
        val changedDigest = ImpactLibraryDigestDocument.parse("f".repeat(64)).modelRefined()
        val wrongArtifact = f.binding.artifact.copy(binaryDigest = changedDigest)
        assertFalse(f.application.copy(binding = f.binding.copy(artifact = wrongArtifact)).admitsValueModel(f.basis))
        val wrongReview = f.model.copy(artifact = f.model.artifact.copy(sourceDigest = changedDigest))
        assertFalse(f.application.copy(binding = f.binding.copy(model = wrongReview)).admitsValueModel(f.basis))
        val foreign =
            (f.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = ImpactEvidenceRevisionDocument.parse(8).modelRefined()
            )
        assertFalse(
            f.application
                .copy(binding = f.binding.copy(artifact = f.binding.artifact.copy(basis = foreign)))
                .admitsValueModel(f.basis)
        )
        assertFalse(
            f.application
                .copy(
                    binding =
                        f.binding.copy(artifact = f.binding.artifact.copy(origin = ImpactLibraryOriginDocument.SOURCE))
                )
                .admitsValueModel(f.basis)
        )
    }

    @Test
    fun `self-consistent output structure grants no claim of registry membership`() {
        val f = ValueModelStructureCase()
        val digest = ImpactLibraryDigestDocument.parse("f".repeat(64)).modelRefined()
        val binding =
            f.binding.copy(
                artifact = f.binding.artifact.copy(binaryDigest = digest),
                model = f.model.copy(artifact = f.model.artifact.copy(binaryDigest = digest)),
            )
        assertTrue(f.application.copy(binding = binding).admitsValueModel(f.basis))
        val output = Json.encodeToString(ImpactValueModelApplicationDocument.serializer(), f.application)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactModelDocument.serializer(), output)
        }
    }

    @Test
    fun `let review output encodes exact kind and rejects an unknown kind`() {
        val f = ValueModelStructureCase()
        val model = f.letApplication().binding.model
        val encoded = Json.encodeToJsonElement(ImpactReviewedValueModelDocument.serializer(), model).jsonObject
        assertEquals(setOf("type", "reference", "artifact", "callable"), encoded.keys)
        assertEquals("LET", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(
            CompilerSignatureWireDocument.Function(
                "kotlin.let",
                CompilerReceiverWireDocument.Present("T"),
                emptyList(),
                listOf("(T)->R"),
                2,
            ),
            model.callable,
        )
        assertEquals("scope.let.invoke-block-value", model.reference.rule.value)
        assertEquals(f.model.reference.model, model.reference.model)
        assertEquals(f.model.artifact, model.artifact)
        val text = Json.encodeToString(ImpactReviewedValueModelDocument.serializer(), model)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(
                ImpactReviewedValueModelDocument.serializer(),
                text.replace("\"LET\"", "\"OPAQUE_RULE\""),
            )
        }
    }

    @Test
    fun `collection selection rejects a structurally consistent let review binding`() {
        val f = ValueModelStructureCase()
        assertTrue(f.application.admitsValueModel(f.basis))
        assertFalse(f.letApplication().admitsValueModel(f.basis))
    }

    @Test
    fun `scalar failures keep exact finite presentation names`() {
        assertEquals(
            "\"UNPROVEN_MODELED_VALUE_STEP\"",
            Json.encodeToString(
                ImpactExecutionLedgerCause.serializer(),
                ImpactExecutionLedgerCause.UNPROVEN_MODELED_VALUE_STEP,
            ),
        )
        assertEquals(
            Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_DIGEST),
            ImpactLibraryDigestDocument.parse("F".repeat(64)),
        )
        assertEquals(
            Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_ARTIFACT_VERSION),
            ImpactLibraryVersionDocument.parse(" "),
        )
        assertEquals(
            Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_MODULE_NAME),
            ImpactLibraryModuleDocument.parse("x\u0000y"),
        )
        assertEquals(
            Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_SELECTED_ELEMENT),
            ImpactSelectedElementDocument.parse(1),
        )
        for (cause in ImpactValueModelPrimitiveFailure.entries) {
            val encoded =
                Json.encodeToJsonElement(
                        ImpactPresentationFailureDocument.serializer(),
                        ImpactPresentationFailureDocument.ValueModel(cause),
                    )
                    .jsonObject
            assertEquals(setOf("type", "cause"), encoded.keys)
            assertEquals("VALUE_MODEL", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(cause.name, encoded.getValue("cause").jsonPrimitive.content)
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactValueModelPrimitiveFailure.serializer(), "\"UNCLASSIFIED\"")
        }
    }
}

internal class ValueModelStructureCase {
    private val values = ImpactFindingFixture()
    val basis = values.declaration.basis
    private val enclosing = endpoint("fixture.owner", "owner", "/workspace/File.kt", 0, 200, null, 'a')
    private val callable =
        endpoint(
            "kotlin.collections.firstOrNull",
            "firstOrNull",
            ROOT + "kotlin/collections/CollectionsKt.class",
            22846,
            22929,
            "kotlin/collections/List<T>",
            'b',
        )
    val call = ImpactInvocationReferenceDocument(range(44, 70), callable.declaration)
    val source =
        ImpactValueSiteReferenceDocument(
            enclosing.declaration,
            range(46, 50),
            ImpactValueRoleDocument.Receiver(call, ImpactReceiverKindDocument.EXTENSION),
        )
    private val target = source.copy(range = call.range, role = ImpactValueRoleDocument.ExpressionResult)
    val model =
        ImpactReviewedValueModelDocument.FirstOrNull(
            ImpactRuleReferenceDocument(
                ImpactModelIdentityDocument(
                    values.id("kotlin-stdlib-value-flow"),
                    ImpactModelVersionDocument.parse(1).modelRefined(),
                    values.id("source-sha256:$SOURCE_SHA"),
                ),
                values.id("collections.list.firstOrNull.select-zero"),
            ),
            ImpactReviewedArtifactDocument(
                ImpactLibraryDigestDocument.parse(BINARY_SHA).modelRefined(),
                ImpactLibraryDigestDocument.parse(SOURCE_SHA).modelRefined(),
                ImpactLibraryVersionDocument.parse(VERSION).modelRefined(),
            ),
            callable.compilerTarget.compilerEvidence.signature,
        )
    val binding =
        ImpactReviewedLibraryBindingDocument(
            enclosing,
            ImpactCallOccurrenceDocument.Declared(call),
            callable,
            ImpactModelExecutionDocument.Declaration(enclosing),
            model,
            ImpactLibraryArtifactDocument(
                basis,
                values.text(ROOT),
                ImpactLibraryModuleDocument.parse("packet-stdlib").modelRefined(),
                ImpactLibraryDigestDocument.parse(BINARY_SHA).modelRefined(),
                ImpactLibraryVersionDocument.parse(VERSION).modelRefined(),
                ImpactLibraryOriginDocument.LIBRARY,
            ),
        )
    val condition =
        ImpactValueModelConditionDocument.SelectedElement(
            source,
            target,
            ImpactSelectedElementDocument.parse(0).modelRefined(),
        )
    val application = ImpactValueModelApplicationDocument.CollectionSelection(source, target, binding, condition)

    fun letApplication(): ImpactValueModelApplicationDocument.CollectionSelection {
        val callable = letEndpoint()
        val call = call.copy(callable = callable.declaration)
        val source = source.copy(role = ImpactValueRoleDocument.Receiver(call, ImpactReceiverKindDocument.EXTENSION))
        val model =
            ImpactReviewedValueModelDocument.Let(
                model.reference.copy(rule = values.id("scope.let.invoke-block-value")),
                model.artifact,
                callable.compilerTarget.compilerEvidence.signature,
            )
        val binding =
            binding.copy(invocation = ImpactCallOccurrenceDocument.Declared(call), callable = callable, model = model)
        val condition = condition.copy(receiver = source)
        return application.copy(source = source, binding = binding, condition = condition)
    }

    fun range(start: Int, end: Int) = ImpactSourceRangeDocument(values.offset(start), values.offset(end))

    private fun endpoint(
        qualified: String,
        name: String,
        file: String,
        start: Int,
        end: Int,
        receiver: String?,
        fingerprint: Char,
    ): ImpactModelEndpointDocument {
        val signature =
            CanonicalCompilerSignature.function(
                    qualified,
                    receiver,
                    emptyList(),
                    emptyList(),
                    if (receiver == null) 0 else 1,
                )
                .modelRefined()
        val identity = CompilerSymbolIdentity.fromCanonicalSignature(signature).value
        val wire =
            CompilerSignatureWireDocument.Function(
                qualified,
                if (receiver == null) CompilerReceiverWireDocument.Absent
                else CompilerReceiverWireDocument.Present(receiver),
                emptyList(),
                emptyList(),
                if (receiver == null) 0 else 1,
            )
        val declaration =
            ImpactDeclarationReferenceDocument(basis, values.text(file), range(start, end), values.text(identity))
        return ImpactModelEndpointDocument(
            declaration,
            QueryExcludedCompilerTargetWireDocument(
                file,
                SourceRangeWireDocument(start, end),
                name,
                SymbolKindWireDocument.FUNCTION,
                CompilerSymbolEvidenceWireDocument(identity, wire),
            ),
            values.domain,
            values.text(fingerprint.toString().repeat(64)),
        )
    }

    private fun letEndpoint(): ImpactModelEndpointDocument {
        val base = endpoint("kotlin.let", "let", ROOT + "kotlin/StandardKt.class", 100, 200, "T", 'c')
        val signature =
            CanonicalCompilerSignature.function("kotlin.let", "T", emptyList(), listOf("(T)->R"), 2).modelRefined()
        val identity = CompilerSymbolIdentity.fromCanonicalSignature(signature).value
        val wire =
            CompilerSignatureWireDocument.Function(
                "kotlin.let",
                CompilerReceiverWireDocument.Present("T"),
                emptyList(),
                listOf("(T)->R"),
                2,
            )
        return base.copy(
            declaration = base.declaration.copy(compilerIdentity = values.text(identity)),
            compilerTarget =
                base.compilerTarget.copy(compilerEvidence = CompilerSymbolEvidenceWireDocument(identity, wire)),
        )
    }

    private companion object {
        const val ROOT = "jar:///fixture/kotlin-stdlib.jar!/"
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
        const val SOURCE_SHA = "5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"
        const val VERSION = "2.3.20-release-208"
    }
}

private fun <V, F> Refinement<V, F>.modelRefined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Model structure prerequisite rejected: $failure")
    }
