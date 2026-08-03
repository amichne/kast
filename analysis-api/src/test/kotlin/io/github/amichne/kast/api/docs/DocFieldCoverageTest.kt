@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.compatibility.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.validation.WorkspaceFilesPublicPageToken

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.StructureKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ensures every non-optional property on registered schema classes carries
 * a [DocField] annotation with a non-blank description.
 *
 * This prevents new fields from being added to API models without documentation.
 * The serializer list mirrors [OpenApiDocument.registerSchemas] — any
 * class registered there must appear here.
 */
class DocFieldCoverageTest {

    /**
     * All serializers registered in [OpenApiDocument.registerSchemas].
     * Enums are excluded because they don't have documentable properties.
     */
    private val registeredSerializers: List<Pair<String, KSerializer<*>>> = listOf(
        // JSON-RPC error envelope
        "ApiErrorResponse" to ApiErrorResponse.serializer(),

        // System responses
        "HealthResponse" to HealthResponse.serializer(),
        "RuntimeStatusResponse" to RuntimeStatusResponse.serializer(),
        "RuntimeLifecycleResponse" to RuntimeLifecycleResponse.serializer(),
        "BackendCapabilities" to BackendCapabilities.serializer(),

        // Shared types
        "FilePosition" to FilePosition.serializer(),
        "Location" to Location.serializer(),
        "Symbol" to Symbol.serializer(),
        "SymbolIdentity" to SymbolIdentity.serializer(),
        "ParameterInfo" to ParameterInfo.serializer(),
        "PageInfo" to PageInfo.serializer(),
        "SearchScope" to SearchScope.serializer(),
        "DeclarationScope" to DeclarationScope.serializer(),
        "ServerLimits" to ServerLimits.serializer(),
        "TextEdit" to TextEdit.serializer(),
        "FileHash" to FileHash.serializer(),
        "OutlineSymbol" to OutlineSymbol.serializer(),
        "WorkspaceModule" to WorkspaceModule.serializer(),

        // Read queries & results
        "SymbolQuery" to SymbolQuery.serializer(),
        "SymbolResult" to SymbolResult.serializer(),
        "ReferencesQuery" to ReferencesQuery.serializer(),
        "KastExactSymbolSelector" to KastExactSymbolSelector.serializer(),
        "ReferenceOccurrence" to ReferenceOccurrence.serializer(),
        "ContainingSymbolEvidence" to ContainingSymbolEvidence.serializer(),
        "ContainingSymbolEvidence.Known" to ContainingSymbolEvidence.Known.serializer(),
        "ContainingSymbolEvidence.TopLevel" to ContainingSymbolEvidence.TopLevel.serializer(),
        "ContainingSymbolEvidence.Unavailable" to ContainingSymbolEvidence.Unavailable.serializer(),
        "EXACT" to ResultCardinality.Exact.serializer(),
        "KNOWN_MINIMUM" to ResultCardinality.KnownMinimum.serializer(),
        "RelationshipSearchCoverage.Complete" to RelationshipSearchCoverage.Complete.serializer(),
        "RelationshipSearchCoverage.Resumable" to RelationshipSearchCoverage.Resumable.serializer(),
        "RelationshipSearchCoverage.Limited" to RelationshipSearchCoverage.Limited.serializer(),
        "RelationshipResultEvidence.Complete" to RelationshipResultEvidence.Complete.serializer(),
        "RelationshipResultEvidence.Resumable" to RelationshipResultEvidence.Resumable.serializer(),
        "RelationshipResultEvidence.Limited" to RelationshipResultEvidence.Limited.serializer(),
        "ReferencesResult" to ReferencesResult.serializer(),
        "CallHierarchyQuery" to CallHierarchyQuery.serializer(),
        "CallHierarchyResult" to CallHierarchyResult.serializer(),
        "CallHierarchyStats" to CallHierarchyStats.serializer(),
        "CallNode" to CallNode.serializer(),
        "CallNodeTruncation" to CallNodeTruncation.serializer(),
        "TypeHierarchyQuery" to TypeHierarchyQuery.serializer(),
        "TypeHierarchyResult" to TypeHierarchyResult.serializer(),
        "TypeHierarchyNode" to TypeHierarchyNode.serializer(),
        "TypeHierarchyStats" to TypeHierarchyStats.serializer(),
        "TypeHierarchyTruncation" to TypeHierarchyTruncation.serializer(),
        "SemanticInsertionQuery" to SemanticInsertionQuery.serializer(),
        "SemanticInsertionResult" to SemanticInsertionResult.serializer(),
        "DiagnosticsQuery" to DiagnosticsQuery.serializer(),
        "DiagnosticsResult" to DiagnosticsResult.serializer(),
        "DiagnosticSeverityCounts" to DiagnosticSeverityCounts.serializer(),
        "FileAnalysisStatus" to FileAnalysisStatus.serializer(),
        "Diagnostic" to Diagnostic.serializer(),
        "FileOutlineQuery" to FileOutlineQuery.serializer(),
        "FileOutlineResult" to FileOutlineResult.serializer(),
        "WorkspaceSymbolQuery" to WorkspaceSymbolQuery.serializer(),
        "WorkspaceSymbolResult" to WorkspaceSymbolResult.serializer(),
        "WorkspaceSearchQuery" to WorkspaceSearchQuery.serializer(),
        "WorkspaceSearchResult" to WorkspaceSearchResult.serializer(),
        "SearchMatch" to SearchMatch.serializer(),
        "WorkspaceFilesQuery" to WorkspaceFilesQuery.serializer(),
        "WorkspaceFilesResult" to WorkspaceFilesResult.serializer(),
        "SemanticGraphPath" to SemanticGraphPath.serializer(),
        "SemanticGraphQuery" to SemanticGraphQuery.serializer(),
        "SemanticGraphSymbolKey" to SemanticGraphSymbolKey.serializer(),
        "SemanticGraphSourcePath" to SemanticGraphSourcePath.serializer(),
        "SemanticGraphSha256" to SemanticGraphSha256.serializer(),
        "SemanticGraphGeneration" to SemanticGraphGeneration.serializer(),
        "SemanticGraphSymbol" to SemanticGraphSymbol.serializer(),
        "SemanticGraphRelation" to SemanticGraphRelation.serializer(),
        "SemanticGraphDiagnosticEvidence" to SemanticGraphDiagnosticEvidence.serializer(),
        "SemanticGraphExternalBoundary" to SemanticGraphExternalBoundary.serializer(),
        "SemanticGraphFileCoverage" to SemanticGraphFileCoverage.serializer(),
        "SemanticGraphCoverage" to SemanticGraphCoverage.serializer(),
        "SemanticGraphResult" to SemanticGraphResult.serializer(),
        "WorkspaceFilesContinuationQuery" to WorkspaceFilesContinuationQuery.serializer(),
        "WorkspaceFilesContinuationQuery.Issue" to WorkspaceFilesContinuationQuery.serializer(),
        "WorkspaceFilesContinuationQuery.Consume" to WorkspaceFilesContinuationQuery.serializer(),
        "WorkspaceFilesPublicContinuationIdentity" to WorkspaceFilesPublicContinuationIdentity.serializer(),
        "WorkspaceRoot" to WorkspaceFilesPublicContinuationIdentity.WorkspaceRoot.serializer(),
        "BackendName" to WorkspaceFilesPublicContinuationIdentity.BackendName.serializer(),
        "NormalizedQuery" to WorkspaceFilesPublicContinuationIdentity.NormalizedQuery.serializer(),
        "Projection" to WorkspaceFilesPublicContinuationIdentity.Projection.serializer(),
        "Limit" to WorkspaceFilesPublicContinuationIdentity.Limit.serializer(),
        "WorkspaceFilesPublicContinuationState" to WorkspaceFilesPublicContinuationState.serializer(),
        "CompositionStampDigest" to WorkspaceFilesPublicContinuationState.CompositionStampDigest.serializer(),
        "LastRelativePath" to WorkspaceFilesPublicContinuationState.LastRelativePath.serializer(),
        "CumulativeReturnedCount" to WorkspaceFilesPublicContinuationState.CumulativeReturnedCount.serializer(),
        "WorkspaceFilesPublicContinuationProjection" to WorkspaceFilesPublicContinuationProjection.serializer(),
        "WorkspaceFilesContinuationResult.Issued" to WorkspaceFilesContinuationResult.Issued.serializer(),
        "WorkspaceFilesContinuationResult.Consumed" to WorkspaceFilesContinuationResult.Consumed.serializer(),
        "WorkspaceFilesPublicPageToken" to WorkspaceFilesPublicPageToken.serializer(),
        "ImplementationsQuery" to ImplementationsQuery.serializer(),
        "ImplementationsResult" to ImplementationsResult.serializer(),
        "CodeActionsQuery" to CodeActionsQuery.serializer(),
        "CodeActionsResult" to CodeActionsResult.serializer(),
        "CodeAction" to CodeAction.serializer(),
        "CompletionsQuery" to CompletionsQuery.serializer(),
        "CompletionsResult" to CompletionsResult.serializer(),
        "CompletionItem" to CompletionItem.serializer(),

        // Mutation queries & results
        "RenameQuery" to RenameQuery.serializer(),
        "RenameResult" to RenameResult.serializer(),
        "ExactRenameProof" to ExactRenameProof.serializer(),
        "ExactRenameOccurrence" to ExactRenameOccurrence.serializer(),
        "ExactFileImage" to ExactFileImage.serializer(),
        "ExactByteImage" to ExactByteImage.serializer(),
        "ReplacementPlanQuery" to ReplacementPlanQuery.serializer(),
        "ReplacementPlanResult" to ReplacementPlanResult.serializer(),
        "ExactReplacementProof" to ExactReplacementProof.serializer(),
        "ReplacementDeclarationSignature" to ReplacementDeclarationSignature.serializer(),
        "ReplacementDeclarationSignature.Function" to ReplacementFunctionSignature.serializer(),
        "ReplacementTypeParameterSignature" to ReplacementTypeParameterSignature.serializer(),
        "ReplacementValueParameterSignature" to ReplacementValueParameterSignature.serializer(),
        "ReplacementDeclarationSignature.Property" to ReplacementPropertySignature.serializer(),
        "ReplacementDeclarationSlice" to ReplacementDeclarationSlice.serializer(),
        "ReplacementOutboundEvidence.Complete" to ReplacementOutboundEvidence.Complete.serializer(),
        "ReplacementOutboundEvidence.Limited" to ReplacementOutboundEvidence.Limited.serializer(),
        "ExactReplacementOutboundReference" to ExactReplacementOutboundReference.serializer(),
        "ReplacementOutboundTarget" to ReplacementOutboundTarget.serializer(),
        "ReplacementOutboundTarget.Source" to ReplacementOutboundTarget.Source.serializer(),
        "ReplacementOutboundTarget.External" to ReplacementOutboundTarget.External.serializer(),
        "AddFilePlanQuery" to AddFilePlanQuery.serializer(),
        "AddFilePlanResult" to AddFilePlanResult.serializer(),
        "ExactAddFileProof" to ExactAddFileProof.serializer(),
        "AdditionSourceOwner" to AdditionSourceOwner.serializer(),
        "AdditionKotlinPackage" to AdditionKotlinPackage.serializer(),
        "AdditionKotlinPackage.Root" to AdditionKotlinPackage.Root.serializer(),
        "AdditionKotlinPackage.Named" to AdditionKotlinPackage.Named.serializer(),
        "AdditionTopLevelDeclaration" to AdditionTopLevelDeclaration.serializer(),
        "AdditionRelativeRange" to AdditionRelativeRange.serializer(),
        "ExactAdditionProofContext" to ExactAdditionProofContext.serializer(),
        "ExactAdditionContextFileHash" to ExactAdditionContextFileHash.serializer(),
        "ExactAdditionCollisionEvidence" to ExactAdditionCollisionEvidence.serializer(),
        "ExactAdditionOutboundEvidence" to ExactAdditionOutboundEvidence.serializer(),
        "ExactAdditionOutboundOccurrence" to ExactAdditionOutboundOccurrence.serializer(),
        "AdditionResolvedTarget" to AdditionResolvedTarget.serializer(),
        "AdditionResolvedTarget.Source" to AdditionResolvedTarget.Source.serializer(),
        "AdditionResolvedTarget.External" to AdditionResolvedTarget.External.serializer(),
        "ExactAdditionRebindingBaseline" to ExactAdditionRebindingBaseline.serializer(),
        "ExactAdditionRebindingOccurrence" to ExactAdditionRebindingOccurrence.serializer(),
        "AdditionWorkspaceRange" to AdditionWorkspaceRange.serializer(),
        "AdditionRebindingCurrentTarget" to AdditionRebindingCurrentTarget.serializer(),
        "AdditionRebindingCurrentTarget.Resolved" to AdditionRebindingCurrentTarget.Resolved.serializer(),
        "AdditionRebindingCurrentTarget.Unresolved" to AdditionRebindingCurrentTarget.Unresolved.serializer(),
        "AddDeclarationPlanQuery" to AddDeclarationPlanQuery.serializer(),
        "AddDeclarationPlanResult" to AddDeclarationPlanResult.serializer(),
        "ExactAddDeclarationProof" to ExactAddDeclarationProof.serializer(),
        "CompilerFileBottomInsertion" to CompilerFileBottomInsertion.serializer(),
        "MutationPostconditionQuery" to MutationPostconditionQuery.serializer(),
        "MutationPostconditionAuthority.Rename" to MutationPostconditionAuthority.Rename.serializer(),
        "MutationPostconditionAuthority.Replacement" to MutationPostconditionAuthority.Replacement.serializer(),
        "MutationPostconditionAuthority.AddFile" to MutationPostconditionAuthority.AddFile.serializer(),
        "MutationPostconditionAuthority.AddDeclaration" to MutationPostconditionAuthority.AddDeclaration.serializer(),
        "MutationPostconditionResult" to MutationPostconditionResult.serializer(),
        "VerifiedMutationPostimage" to VerifiedMutationPostimage.serializer(),
        "MutationPostconditionEvidence.Rename" to MutationPostconditionEvidence.Rename.serializer(),
        "MutationPostconditionEvidence.Replacement" to MutationPostconditionEvidence.Replacement.serializer(),
        "MutationPostconditionEvidence.AddFile" to MutationPostconditionEvidence.AddFile.serializer(),
        "MutationPostconditionEvidence.AddDeclaration" to MutationPostconditionEvidence.AddDeclaration.serializer(),
        "RawExactFileObservationQuery" to RawExactFileObservationQuery.serializer(),
        "RawExactFileObservationResult.Absent" to RawExactFileObservationResult.Absent.serializer(),
        "RawExactFileObservationResult.Present" to RawExactFileObservationResult.Present.serializer(),
        "ExactFileImageQuery" to ExactFileImageQuery.serializer(),
        "MutationScratchSet" to MutationScratchSet.serializer(),
        "ExactFileImageResult" to ExactFileImageResult.serializer(),
        "MutationScratchInspectQuery" to MutationScratchInspectQuery.serializer(),
        "MutationScratchInspectResult" to MutationScratchInspectResult.serializer(),
        "MutationScratchObservation" to MutationScratchObservation.serializer(),
        "MutationScratchRecoveryQuery" to MutationScratchRecoveryQuery.serializer(),
        "MutationScratchRecoveryPreimage.Absent" to MutationScratchRecoveryPreimage.Absent.serializer(),
        "MutationScratchRecoveryPreimage.Present" to MutationScratchRecoveryPreimage.Present.serializer(),
        "MutationScratchRecoveryResult" to MutationScratchRecoveryResult.serializer(),
        "ImportOptimizeQuery" to ImportOptimizeQuery.serializer(),
        "ImportOptimizeResult" to ImportOptimizeResult.serializer(),
        "ApplyEditsQuery" to ApplyEditsQuery.serializer(),
        "ApplyEditsResult" to ApplyEditsResult.serializer(),
        "RefreshQuery" to RefreshQuery.serializer(),
        "RefreshResult" to RefreshResult.serializer(),
        "RefreshExternalFailureOutcome" to RefreshExternalFailureOutcome.serializer(),
        "RefreshRelationshipFailure" to RefreshRelationshipFailure.serializer(),
        "SemanticAdmissionStatus" to SemanticAdmissionStatus.serializer(),

        // FileOperation sealed hierarchy subtypes
        "FileOperation.CreateFile" to FileOperation.CreateFile.serializer(),
        "FileOperation.DeleteFile" to FileOperation.DeleteFile.serializer(),
    )

    @Test
    fun `every registered schema property has a DocField annotation with non-blank description`() {
        val violations = mutableListOf<String>()

        for ((name, serializer) in registeredSerializers) {
            val descriptor = serializer.descriptor
            if (descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT) continue

            repeat(descriptor.elementsCount) { index ->
                val fieldName = descriptor.getElementName(index)
                val annotations = descriptor.getElementAnnotations(index)
                val docField = annotations.filterIsInstance<DocField>().firstOrNull()
                if (docField == null) {
                    violations += "$name.$fieldName: missing @DocField annotation"
                } else if (docField.description.isBlank()) {
                    violations += "$name.$fieldName: @DocField has blank description"
                }
            }
        }

        assertTrue(violations.isEmpty()) {
            "Found ${violations.size} undocumented properties:\n${violations.joinToString("\n") { "  • $it" }}"
        }
    }

    @Test
    fun `continuation ttl documents its fixed lifetime from issuance`() {
        val descriptor = ServerLimits.serializer().descriptor
        val ttlIndex = (0 until descriptor.elementsCount)
            .single { index -> descriptor.getElementName(index) == "continuationTtlMillis" }
        val description = descriptor.getElementAnnotations(ttlIndex)
            .filterIsInstance<DocField>()
            .single()
            .description

        assertTrue(description.contains("issuance"), description)
        assertFalse(description.contains("unused"), description)
    }

    @Test
    fun `registered serializers list matches OpenApiDocument schema count`() {
        // Verify this test covers the same schemas as the OpenAPI generator.
        // The OpenAPI generator registers enums inline so they don't need DocField.
        // Count only CLASS/OBJECT descriptors from the OpenAPI spec.
        val yaml = OpenApiDocument.renderYaml()
        val schemaDefRegex = Regex("""^ {4}([A-Za-z0-9_.]+):$""", RegexOption.MULTILINE)
        val allSchemaNames = schemaDefRegex.findAll(yaml).map { it.groupValues[1] }.toSet()

        // Filter to only schemas that have properties (i.e., object schemas, not enums)
        val objectSchemas = allSchemaNames.filter { name ->
            val schemaSection = extractSchemaSection(yaml, name)
            schemaSection.contains("\"type\": \"object\"") || schemaSection.contains("type: object")
        }.toSet()

        val testSchemaNames = registeredSerializers.map { it.first }.toSet()

        // Exclude wire-level JSON-RPC envelope types (not public API models)
        // and the top-level FileOperation (sealed interface; subtypes are tested individually)
        val wireTypes = setOf(
            "JsonRpcErrorObject", "JsonRpcErrorResponse",
            "JsonRpcRequest", "JsonRpcSuccessResponse",
            "FileOperation",
            "WorkspaceFilesContinuationResult",
        )
        val expected = objectSchemas - wireTypes

        val missing = expected - testSchemaNames
        assertTrue(missing.isEmpty()) {
            "Schemas in OpenAPI spec but not in DocFieldCoverageTest: $missing"
        }
    }

    private fun extractSchemaSection(yaml: String, schemaName: String): String {
        val lines = yaml.lines()
        val startIdx = lines.indexOfFirst { it.trimStart().startsWith("$schemaName:") && it.startsWith("    ") }
        if (startIdx == -1) return ""
        val endIdx = lines.drop(startIdx + 1).indexOfFirst {
            it.isNotBlank() && !it.startsWith("      ") && !it.startsWith("        ")
        }.let { if (it == -1) lines.size else startIdx + 1 + it }
        return lines.subList(startIdx, endIdx).joinToString("\n")
    }
}
