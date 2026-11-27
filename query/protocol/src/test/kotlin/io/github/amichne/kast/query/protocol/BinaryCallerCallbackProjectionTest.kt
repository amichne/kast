package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackTargetDocument
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * A call to a compiled library function inside a source lambda is a callers observation whose target is that binary
 * subject. The source call site, callback and owner keep their source handles; the binary target has none to issue.
 */
internal class BinaryCallerCallbackProjectionTest {
    private val fixture = RelationPagingFixture.published()

    @Test
    fun `a callers observation of a binary subject projects its source sites`() {
        val projected = callersObservation().projectRelationObservation(fixture.references)
        assertNotNull(projected, "A binary callers subject must not erase its callback observation")
        val callback = projected!!.callbackObservations.values.single()
        val target = assertInstanceOf(QueryCallbackTargetDocument.Binary::class.java, callback.target)
        assertEquals(BINARY_FILE, target.compilerTarget.file.value)
        assertEquals("kotlinx.serialization.json.JsonPrimitive", target.compilerTarget.compilerEvidence.signature.let {
            (it as CompilerSignatureDocument.Function).qualifiedIdentity.value
        })
        // The call site, callback body and owner are source and keep their issued handles.
        assertEquals(CALLER_FILE, callback.occurrence.file.value)
        assertEquals(CALL_START, callback.occurrence.range.startInclusive.value)
        assertEquals(BODY_START, callback.callbackBody.range.startInclusive.value)
        assertEquals(CALLER_FILE, callback.lexicalOwner.declaration.file.value)
    }

    private fun callersObservation(): QueryRelationObservation {
        val subject =
            SymbolSelector.issue(
                fixture.authority,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
                binarySubject,
            )
        val request =
            RelationRequest.start(
                subject,
                RelationMeaning.Callers,
                fixture.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val callback =
            RelationCallbackObservation.fromNativeBoundary(
                    request,
                    occurrence(CALL_START, CALL_END),
                    binarySubject,
                    sourceOwner,
                    occurrence(BODY_START, BODY_END),
                    CallbackNamedCallPolicy.Excluded(
                        CallbackExclusionReason.NON_INLINE_ARGUMENT,
                        occurrence(BOUNDARY_START, BOUNDARY_END),
                    ),
                    CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                    CallbackResultProjection.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN),
                )
                .fixture()
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(callback.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .fixture(),
                    RelationWorkCount.parse(1).fixture(),
                    RelationResultCount.parse(0).fixture(),
                    callbackObservations = listOf(callback),
                )
                .fixture()
        return QueryRelationObservation.from(
            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        )
    }

    private val callerFile =
        SymbolDiscoveryFileIdentity.Workspace(
            CanonicalWorkspaceFilePath.fromCanonicalPath(
                    fixture.authority.workspaceRoot,
                    Path.of(fixture.authority.workspaceRoot.value).resolve("Caller.kt"),
                )
                .fixture()
        )

    private val sourceOwner =
        CompilerGroundedSymbolEvidence.fromBoundary(
                callerFile,
                OWNER_START,
                OWNER_END,
                "caller",
                "sample.caller",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("sample.caller", null, emptyList(), emptyList(), 0).fixture(),
            )
            .fixture()

    private val binarySubject =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(DetachedVirtualFileUrl.parse(BINARY_FILE).fixture()),
                2639,
                2752,
                "JsonPrimitive",
                "kotlinx.serialization.json.JsonPrimitive",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                        "kotlinx.serialization.json.JsonPrimitive",
                        null,
                        emptyList(),
                        listOf("kotlin/Boolean?"),
                        0,
                    )
                    .fixture(),
            )
            .fixture()

    private fun occurrence(start: Int, end: Int): RelationOccurrence =
        RelationOccurrence.fromBoundary(callerFile, start, end).fixture()
}

private const val BINARY_FILE =
    "jar:///libraries/kotlinx-serialization-json-jvm.jar!/kotlinx/serialization/json/JsonElementKt.class"
private const val CALLER_FILE = "/workspace/Caller.kt"
private const val OWNER_START = 0
private const val OWNER_END = 100
private const val BOUNDARY_START = 15
private const val BOUNDARY_END = 65
private const val BODY_START = 20
private const val BODY_END = 60
private const val CALL_START = 30
private const val CALL_END = 43

private fun <T, F> Refinement<T, F>.fixture(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid binary callers fixture: $failure")
    }
