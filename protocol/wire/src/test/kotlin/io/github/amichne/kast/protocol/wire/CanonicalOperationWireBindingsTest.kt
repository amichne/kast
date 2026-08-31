package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildDigest
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.protocol.contract.TopologyExtractionRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions
import io.github.amichne.kast.protocol.registry.HostedVariants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalOperationWireBindingsTest {
    @Test
    fun `production serializer table covers the exact canonical operation set`() {
        assertEquals(
            CanonicalOperation.entries,
            CanonicalOperationWireBindings.table.bindings.map { it.operation },
        )
    }

    @Test
    fun `generated registry document preserves the typed definition order`() {
        val operations = CanonicalOperationDefinitions.registry.definitions.joinToString(",") {
            val intents = when (val variants = it.hostedVariants) {
                is HostedVariants.Intents -> variants.intents.joinToString(",") { intent ->
                    "\"${intent.identity}\""
                }
                HostedVariants.None -> ""
            }
            "{\"operationId\":\"${it.id.value}\",\"hostedExposure\":" +
                "\"${it.hostedExposure.name.lowercase()}\",\"intents\":[$intents]}"
        }

        assertEquals(
            "{\"schemaVersion\":2,\"operations\":[$operations]}\n",
            CanonicalOperationWireBindings.operationRegistryDocument,
        )
    }

    @Test
    fun `every traversal and change prerequisite rejection round trips`() {
        assertRejections(
            CanonicalOperationWireBindings.traversalRun,
            TraversalRunRejection.entries,
        )
        assertRejections(
            CanonicalOperationWireBindings.changePlan,
            ChangePlanRejection.entries,
        )
    }

    @Test
    fun `all production bindings round trip requests and every outcome variant`() {
        assertRoundTrips(
            CanonicalOperationWireBindings.workspaceInspect,
            WorkspaceInspectRequest,
            WorkspaceInspectResult(text("/fixture"), WorkspaceStateDocument.READY),
            WorkspaceInspectQualification.RECONCILING,
            WorkspaceInspectRejection.RUNTIME_BLOCKED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.symbolDiscover,
            discoverRequest("Target", 20),
            SymbolDiscoverResult(
                BoundedProtocolList.create(
                    listOf<SymbolDiscoveryDocument>(
                        SymbolDiscoveryDocument.Declaration(
                            text("candidate:v1:Target"),
                            SymbolDiscoveryKindDocument.SYMBOL,
                            text("Target"),
                            text("src/Target.kt"),
                            offset(7),
                        ),
                    ),
                ).refinedValue(),
            ),
            SymbolDiscoverQualification.from(setOf(SymbolDiscoverLimitation.RESULT_LIMIT)).refinedValue(),
            SymbolDiscoverRejection.QUERY_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.symbolResolve,
            SymbolResolveRequest(text("candidate:Target")),
            SymbolResolveResult(text("exact:Target")),
            SymbolResolveQualification.EVIDENCE_INCOMPLETE,
            SymbolResolveRejection.AMBIGUOUS,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.symbolDescribe,
            SymbolDescribeRequest(text("exact:Target")),
            SymbolDescribeResult(symbol("exact:v1:Target", "Target")),
            SymbolDescribeQualification.EVIDENCE_INCOMPLETE,
            SymbolDescribeRejection.SELECTOR_STALE,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.relationRead,
            RelationReadRequest(text("exact:Target"), RelationKindDocument.CALLERS, count(50)),
            RelationReadResult(
                BoundedProtocolList.create(
                    listOf(
                        relation(
                            RelationKindDocument.CALLERS,
                            symbol("exact:v1:Caller", "Caller"),
                            symbol("exact:v1:Target", "Target"),
                        ),
                    ),
                ).refinedValue(),
            ),
            relationQualification(),
            RelationReadRejection.RELATION_UNSUPPORTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.traversalRun,
            TraversalRunRequest(
                text("exact:Target"),
                RelationKindDocument.CALLERS,
                count(3),
                count(100),
            ),
            TraversalRunResult(
                BoundedProtocolList.create(
                    listOf(
                        traversal(
                            1,
                            relation(
                                RelationKindDocument.CALLERS,
                                symbol("exact:v1:Caller", "Caller"),
                                symbol("exact:v1:Target", "Target"),
                            ),
                        ),
                        traversal(
                            2,
                            relation(
                                RelationKindDocument.CALLERS,
                                symbol("exact:v1:Root", "Root"),
                                symbol("exact:v1:Caller", "Caller"),
                            ),
                        ),
                    ),
                ).refinedValue(),
            ),
            traversalQualification(),
            TraversalRunRejection.PLAN_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.diagnosticCheck,
            DiagnosticCheckRequest(text("project:fixture"), count(100)),
            DiagnosticCheckResult(
                BoundedProtocolList.create(
                    listOf(
                        DiagnosticDocument(
                            DiagnosticSeverityDocument.WARNING,
                            text("UNUSED"),
                            text("unused"),
                            DiagnosticLocationDocument(
                                text("src/Target.kt"),
                                DiagnosticRangeDocument.create(offset(3), offset(3)).refinedValue(),
                            ),
                        ),
                    ),
                ).refinedValue(),
            ),
            diagnosticQualification(),
            DiagnosticCheckRejection.SCOPE_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.topologyBuild,
            TopologyBuildRequest,
            TopologyBuildResult(
                TopologyBuildStatus.PUBLISHED,
                EvidenceGeneration.parse(17).refinedValue(),
                TopologyBuildDigest.parse("a".repeat(64)).refinedValue(),
            ),
            TopologyBuildQualification.PROGRESS_UNAVAILABLE,
            TopologyBuildRejection.ExtractionFailed(
                text("topology/intellij/src/main/kotlin/TopologyK2Projection.kt"),
                TopologyExtractionRejection.SOURCE_CONTENT_MOVED,
            ),
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changePlan,
            ChangePlanRequest(
                ChangeIntentDocument.AddDeclaration(
                    text("exact:Target"),
                    text("fun added() = Unit"),
                ),
            ),
            ChangePlanResult(text("plan:1")),
            ChangePlanQualification.OPTIONAL_EVIDENCE_INCOMPLETE,
            ChangePlanRejection.RELATION_READ_REQUIRED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changeApply,
            ChangeApplyRequest(text("plan:1")),
            ChangeApplyResult(text("application:1")),
            ChangeApplyQualification.RECOVERY_REQUIRED,
            ChangeApplyRejection.CONTENT_CHANGED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changeVerify,
            ChangeVerifyRequest(text("application:1")),
            ChangeVerifyResult(text("receipt:1")),
            ChangeVerifyQualification.PROOF_INCOMPLETE,
            ChangeVerifyRejection.SEMANTIC_DELTA_REJECTED,
        )
        assertRoundTrips(
            CanonicalOperationWireBindings.changeRecover,
            ChangeRecoverRequest(text("plan:1")),
            ChangeRecoverResult(ChangeRecoveryDocumentState.ROLLED_BACK),
            ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED,
            ChangeRecoverRejection.RECOVERY_FAILED,
        )
    }

    @Test
    fun `all four closed change intents use the one production plan binding`() {
        val intents = listOf(
            ChangeIntentDocument.AddFile(text("src/New.kt"), text("class New")),
            ChangeIntentDocument.AddDeclaration(text("exact:Target"), text("fun added() = Unit")),
            ChangeIntentDocument.ReplaceDeclaration(text("exact:Target"), text("class Target")),
            ChangeIntentDocument.RenameSymbol(text("exact:Target"), text("Renamed")),
        )

        intents.forEach { intent ->
            val request = ChangePlanRequest(intent)
            val encoded = CanonicalOperationWireBindings.changePlan.encodeRequest(request)
                .encodedDocument()
            val admitted = WireRequestEnvelope.admit(encoded).admittedRequest()
            assertEquals(
                WireDecoding.Decoded(request),
                CanonicalOperationWireBindings.changePlan.decodeRequest(admitted),
            )
        }
    }

    @Test
    fun `production binding rejects unknown operation schema and invalid refined payload`() {
        val binding = CanonicalOperationWireBindings.symbolDiscover
        val encoded = binding.encodeRequest(discoverRequest("Target", 20))
            .encodedDocument()
        val unknownSchema = SchemaIdentity.parse("kast.unknown.v1").refinedValue()
        val unknownOperation = OperationId.parse("symbol.missing").refinedValue()

        val admittedUnknownSchema = WireRequestEnvelope.admit(
            encoded.replace(binding.schema.value, unknownSchema.value),
        ).admittedRequest()
        assertEquals(
            WireDecoding.Rejected(WireFailure.UnknownSchema(unknownSchema)),
            binding.decodeRequest(admittedUnknownSchema),
        )
        assertEquals(
            WireRequestAdmission.Rejected(WireFailure.UnknownOperation(unknownOperation)),
            WireRequestEnvelope.admit(
                encoded.replace(
                    "\"operation\":\"${binding.operation.id.value}\"",
                    "\"operation\":\"${unknownOperation.value}\"",
                ),
            ),
        )

        val invalidPayload = encoded.replace("\"Target\"", "\"\"")
        assertEquals(
            WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
            binding.decodeRequest(WireRequestEnvelope.admit(invalidPayload).admittedRequest()),
        )
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
        > assertRoundTrips(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        request: Request,
        result: Result,
        qualification: Qualification,
        rejection: Rejection,
    ) {
        val requestDocument = binding.encodeRequest(request).encodedDocument()
        val admittedRequest = WireRequestEnvelope.admit(requestDocument).admittedRequest()
        assertEquals(WireDecoding.Decoded(request), binding.decodeRequest(admittedRequest))

        val evidence = EvidenceEnvelope(
            operation = binding.operation.id,
            generation = EvidenceGeneration.parse(17).refinedValue(),
            payload = result,
        )
        listOf(
            OperationOutcome.Complete(evidence),
            OperationOutcome.Qualified(evidence, qualification),
            OperationOutcome.Rejected(rejection),
        ).forEach { outcome ->
            val document = binding.encodeOutcome(outcome).encodedDocument()
            assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(document))
        }
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
        > assertRejections(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        rejections: Iterable<Rejection>,
    ) {
        rejections.forEach { rejection ->
            val outcome = OperationOutcome.Rejected(rejection)
            val document = binding.encodeOutcome(outcome).encodedDocument()
            assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(document))
        }
    }

    private fun discoverRequest(raw: String, limit: Int): SymbolDiscoverRequest =
        SymbolDiscoverRequest(
            SymbolDiscoverTargetDocument.Name(
                text(raw),
                SymbolNameKindDocument.SYMBOL,
                SymbolDiscoveryMatchDocument.FUZZY,
            ),
            count(limit),
        )

    private fun symbol(selector: String, name: String): SymbolDocument {
        val qualifiedIdentity = text("sample.$name")
        val signature = CompilerSignatureDocument.ClassLike(qualifiedIdentity)
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refinedValue()
        return SymbolDocument.create(
            selector = text(selector),
            kind = SymbolKindDocument.CLASSLIKE,
            name = text(name),
            qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(qualifiedIdentity),
            file = text("src/$name.kt"),
            range = SourceRangeDocument.create(offset(0), offset(name.length)).refinedValue(),
            compilerEvidence = evidence,
        ).refinedValue()
    }

    private fun relation(
        meaning: RelationKindDocument,
        source: SymbolDocument,
        target: SymbolDocument,
    ): RelationFactDocument = RelationFactDocument(
        meaning,
        source,
        target,
        RelationOccurrenceDocument(source.file, source.range),
        RelationProvenanceDocument.K2_AUTHORED_SOURCE,
        RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
    )

    private fun traversal(depth: Int, relation: RelationFactDocument): TraversalRecordDocument =
        TraversalRecordDocument(TraversalDepthDocument.parse(depth).refinedValue(), relation)

    private fun relationQualification(): RelationReadQualification =
        RelationReadQualification.create(
            RelationKnownMinimumDocument.parse(1).refinedValue(),
            listOf(
                RelationLimitationDocument.RESULT_LIMIT_REACHED,
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
            ),
            RelationContinuationDocument.parse("a".repeat(64)).refinedValue(),
        ).refinedValue()

    private fun traversalQualification(): TraversalRunQualification =
        TraversalRunQualification.create(
            listOf(
                TraversalLimitationDocument.DEPTH_LIMIT_REACHED,
                TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
            ),
            listOf(RelationLimitationDocument.PROVIDER_INCOMPLETE),
            TraversalContinuationDocument.parse("b".repeat(64)).refinedValue(),
        ).refinedValue()

    private fun diagnosticQualification(): DiagnosticCheckQualification =
        DiagnosticCheckQualification.create(
            DiagnosticKnownCountDocument.parse(1).refinedValue(),
            resultLimitReached = true,
            analyzedFiles = listOf(text("src/Target.kt")),
            limitations = listOf(
                DiagnosticLimitationDocument(
                    text("src/Other.kt"),
                    DiagnosticLimitationReasonDocument.INDEXING,
                ),
            ),
        ).refinedValue()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refinedValue()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest = when (this) {
        is WireRequestAdmission.Admitted -> request
        is WireRequestAdmission.Rejected -> error("Expected admitted request, got $failure")
    }
}
