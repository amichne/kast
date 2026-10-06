package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterIdentityFailure
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableDisposition
import io.github.amichne.kast.relation.contract.SourceLessCallableFailure
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached compiler facts exercise production admission/accounting; these fixtures are not native resolution. */
class CallableObservationTest {
    private val fixture = RelationReadTest()

    @Test
    fun `known library boundary retains compiler identity without source PSI and completes enumeration`() {
        val request = fixture.request(RelationMeaning.Callees)
        val value = boundary(request)
        val collector = IntellijRelationCollector(request, { 0L })
        collector.beginProviderItem(fixture.providerItem("library"))
        assertTrue(collector.acceptCallableObservation(value))
        val complete =
            assertInstanceOf(
                RelationCompilation.Complete::class.java,
                collector.finish(IntellijRelationTermination.Terminal),
            )
        assertEquals(listOf(value), complete.batch.callableObservations)
        assertEquals(emptyList<RelationFact>(), complete.batch.facts)
        assertEquals(emptyList<RelationOmissionEvidence>(), complete.batch.omissions)
        assertEquals(
            value.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong(),
            complete.batch.encodedBytes.value,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (complete.batch.callableObservations as MutableList<RelationCallableObservation>).clear()
        }
    }

    @Test
    fun `callable evidence shares the result allowance without manufacturing named rows`() {
        val request = fixture.request(RelationMeaning.Callees, resultLimit = 1)
        val collector = IntellijRelationCollector(request, { 0L })
        collector.beginProviderItem(fixture.providerItem("first"))
        assertTrue(collector.acceptCallableObservation(boundary(request)))
        assertEquals(
            IntellijRelationProviderItemAdmission.HALTED,
            collector.beginProviderItem(fixture.providerItem("second")),
        )
    }

    @Test
    fun `foreign subject and source origin cannot become a known library boundary`() {
        val request = fixture.request(RelationMeaning.Callees)
        assertEquals(
            Refinement.Rejected(SourceLessCallableFailure.UNSUPPORTED_ORIGIN),
            SourceLessCallable.fromCompiler(
                signature(),
                CompilerSymbolKind.FUNCTION,
                SourceLessCallableOrigin.SOURCE,
                SourceLessCallableModuleKind.LIBRARY,
                SourceLessCallableModuleName.parse("test-library").refined(),
            ),
        )
        val differentMeaning = fixture.request(RelationMeaning.Callers)
        val collector = IntellijRelationCollector(differentMeaning, { 0L })
        collector.beginProviderItem(fixture.providerItem("foreign"))
        assertFalse(collector.acceptCallableObservation(boundary(request)))
        assertInstanceOf(
            RelationCompilation.Rejected::class.java,
            collector.finish(IntellijRelationTermination.Terminal),
        )
    }

    @Test
    fun `source-less callable rejects signature kind mismatches`() {
        val function = signature()
        val property =
            CanonicalCompilerSignature.property("library.property", null, emptyList(), "kotlin.Unit").refined()
        val alias = CanonicalCompilerSignature.typeAlias("library.Alias").refined()
        val classLike = CanonicalCompilerSignature.classLike("library.Class").refined()
        val name = SourceLessCallableModuleName.parse("test-library").refined()
        listOf(
                function to CompilerSymbolKind.PROPERTY,
                property to CompilerSymbolKind.FUNCTION,
                property to CompilerSymbolKind.CONSTRUCTOR,
                alias to CompilerSymbolKind.FUNCTION,
                classLike to CompilerSymbolKind.CONSTRUCTOR,
            )
            .forEach { (signature, kind) ->
                assertEquals(
                    Refinement.Rejected(SourceLessCallableFailure.NOT_CALLABLE),
                    SourceLessCallable.fromCompiler(
                        signature,
                        kind,
                        SourceLessCallableOrigin.LIBRARY,
                        SourceLessCallableModuleKind.LIBRARY,
                        name,
                    ),
                )
            }
    }

    @Test
    fun `full source-less signature consumes the encoded byte allowance`() {
        val request = fixture.request(RelationMeaning.Callees)
        val short = boundary(request)
        val longSignature =
            CanonicalCompilerSignature.function(
                    "library.consume",
                    null,
                    emptyList(),
                    listOf("library." + "LargeType".repeat(13_000)),
                    0,
                )
                .refined()
        val large = boundary(request, longSignature)
        val signatureBytes = longSignature.canonicalEncoding().value.toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(large.canonicalProjection().toByteArray(Charsets.UTF_8).size >= signatureBytes)
        assertTrue(large.retainedBytes >= short.retainedBytes + signatureBytes)
        val collector = IntellijRelationCollector(request, { 0L })
        collector.beginProviderItem(fixture.providerItem("large-signature"))
        assertFalse(collector.acceptCallableObservation(large))
        val qualified =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                collector.finish(IntellijRelationTermination.Resumable(setOf(RelationLimitation.BYTE_LIMIT_REACHED))),
            )
        assertEquals(emptyList<RelationCallableObservation>(), qualified.batch.callableObservations)
        assertTrue(RelationLimitation.BYTE_LIMIT_REACHED in qualified.coverage.limitations)
    }

    @Test
    fun `anonymous body and exact formal signatures remain in byte accounting`() {
        val request = fixture.request(RelationMeaning.Callees)
        val owner = owner(request)
        val longType = "library." + "LargeType".repeat(13_000)
        val formal = fixture.fact(request, identity = longType).target
        val identity =
            CallbackParameterIdentity.fromCompiler(
                    formal,
                    ValueArgumentPosition.parse(0).refined(),
                    RelationOccurrence.fromBoundary(formal.file, 72, 73).refined(),
                )
                .refined()
        val range = RelationOccurrence.fromBoundary(request.subject.file, 42, 45).refined().range
        val anonymousSignature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(request.subject.file, range),
                    null,
                    emptyList(),
                    listOf(longType),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        val body =
            RelationCallableBody.Anonymous.fromCompiler(request.subject.file, range, anonymousSignature).refined()
        val value =
            RelationCallableObservation.fromNativeBoundary(
                    request,
                    RelationOccurrence.fromBoundary(request.subject.file, 43, 44).refined(),
                    owner,
                    body,
                    RelationCallableTarget.ParameterInvocation(identity),
                )
                .refined()
        assertTrue(value.canonicalProjection().contains(owner.signature.canonicalEncoding().value))
        assertTrue(value.canonicalProjection().contains(formal.signature.canonicalEncoding().value))
        assertTrue(value.canonicalProjection().contains(anonymousSignature.canonicalEncoding().value))
        assertTrue(
            value.retainedBytes >=
                formal.signature.canonicalEncoding().value.length + anonymousSignature.canonicalEncoding().value.length
        )
        val collector = IntellijRelationCollector(request, { 0L })
        collector.beginProviderItem(fixture.providerItem("large-formal-and-body"))
        assertFalse(collector.acceptCallableObservation(value))
        val qualified =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                collector.finish(IntellijRelationTermination.Resumable(setOf(RelationLimitation.BYTE_LIMIT_REACHED))),
            )
        assertTrue(RelationLimitation.BYTE_LIMIT_REACHED in qualified.coverage.limitations)
    }

    @Test
    fun `exact parameter identity retains formal position and invocation body`() {
        val request = fixture.request(RelationMeaning.Callees)
        val target = fixture.fact(request).target
        val parameter = RelationOccurrence.fromBoundary(target.file, 72, 73).refined()
        val identity =
            CallbackParameterIdentity.fromCompiler(target, ValueArgumentPosition.parse(0).refined(), parameter)
                .refined()
        val occurrence = RelationOccurrence.fromBoundary(request.subject.file, 43, 44).refined()
        val owner = owner(request)
        val body = RelationCallableBody.Named.fromCompiler(owner).refined()
        val value =
            RelationCallableObservation.fromNativeBoundary(
                    request,
                    occurrence,
                    owner,
                    body,
                    RelationCallableTarget.ParameterInvocation(identity),
                )
                .refined()
        val collector = IntellijRelationCollector(request, { 0L })
        collector.beginProviderItem(fixture.providerItem("parameter"))
        assertTrue(collector.acceptCallableObservation(value))
        val complete =
            assertInstanceOf(
                RelationCompilation.Complete::class.java,
                collector.finish(IntellijRelationTermination.Terminal),
            )
        assertEquals(listOf(value), complete.batch.callableObservations)
        assertEquals(0, complete.batch.resultCount.value)
        assertTrue(value.canonicalProjection().contains(owner.signature.canonicalEncoding().value))
        assertTrue(value.canonicalProjection().contains(target.signature.canonicalEncoding().value))
        assertEquals(
            Refinement.Rejected(CallbackParameterIdentityFailure.INVALID_PARAMETER_POSITION),
            CallbackParameterIdentity.fromCompiler(target, ValueArgumentPosition.parse(1).refined(), parameter),
        )
        assertEquals(
            Refinement.Rejected(CallbackParameterIdentityFailure.PARAMETER_OUTSIDE_CALLABLE),
            CallbackParameterIdentity.fromCompiler(target, ValueArgumentPosition.parse(0).refined(), occurrence),
        )
    }

    private fun boundary(
        request: RelationRequest,
        signature: CanonicalCompilerSignature = signature(),
    ): RelationCallableObservation =
        RelationCallableObservation.fromNativeBoundary(
                request,
                RelationOccurrence.fromBoundary(request.subject.file, 43, 44).refined(),
                owner(request),
                RelationCallableBody.Named.fromCompiler(owner(request)).refined(),
                RelationCallableTarget.SourceLess(
                    SourceLessCallable.fromCompiler(
                            signature,
                            CompilerSymbolKind.FUNCTION,
                            SourceLessCallableOrigin.LIBRARY,
                            SourceLessCallableModuleKind.LIBRARY,
                            SourceLessCallableModuleName.parse("test-library").refined(),
                        )
                        .refined(),
                    SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED,
                ),
            )
            .refined()

    private fun owner(request: RelationRequest): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                request.subject.file,
                request.subject.range.startInclusive,
                request.subject.range.endExclusive,
                request.subject.name.value,
                "sample.Subject.run",
                request.subject.kind,
                request.subject.signature,
            )
            .refined()

    private fun signature() =
        CanonicalCompilerSignature.function("library.consume", null, emptyList(), emptyList(), 0).refined()

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value
}
