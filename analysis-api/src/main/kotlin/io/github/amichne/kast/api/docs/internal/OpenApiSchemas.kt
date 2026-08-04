package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.compatibility.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.*

internal fun registerOpenApiSchemas(registry: SchemaRegistry) {
    // JSON-RPC error envelope
    registry.register("JsonRpcErrorObject", JsonRpcErrorObject.serializer())
    registry.register("ApiErrorResponse", ApiErrorResponse.serializer())

    // System responses
    registry.register("HealthResponse", HealthResponse.serializer())
    registry.register("RuntimeStatusResponse", RuntimeStatusResponse.serializer())
    registry.register("PublishedWorkspaceGenerationStatus", PublishedWorkspaceGenerationStatus.serializer())
    registry.register("RuntimeLifecycleResponse", RuntimeLifecycleResponse.serializer())
    registry.register("BackendCapabilities", BackendCapabilities.serializer())

    // Shared types
    registry.register("FilePosition", FilePosition.serializer())
    registry.register("Location", Location.serializer())
    registry.register("Symbol", Symbol.serializer())
    registry.register("ParameterInfo", ParameterInfo.serializer())
    registry.register("PageInfo", PageInfo.serializer())
    registry.register("SearchScope", SearchScope.serializer())
    registry.register("DeclarationScope", DeclarationScope.serializer())
    registry.register("ServerLimits", ServerLimits.serializer())
    registry.register("TextEdit", TextEdit.serializer())
    registry.register("FileHash", FileHash.serializer())
    registry.register("OutlineSymbol", OutlineSymbol.serializer())
    registry.register("WorkspaceModule", WorkspaceModule.serializer())

    // Runtime identity vocabulary
    registry.register("RuntimeImplementationVersion", RuntimeImplementationVersion.serializer())

    // Read queries & results
    registry.register("SymbolQuery", SymbolQuery.serializer())
    registry.register("SymbolResult", SymbolResult.serializer())
    registry.register("ReferencesQuery", ReferencesQuery.serializer())
    registry.register("ResultCardinality", ResultCardinality.serializer())
    registry.register("EXACT", ResultCardinality.Exact.serializer())
    registry.register("KNOWN_MINIMUM", ResultCardinality.KnownMinimum.serializer())
    registry.register("RelationshipCoverageStatus", RelationshipCoverageStatus.serializer())
    registry.register("RelationshipSearchLimitation", RelationshipSearchLimitation.serializer())
    registry.register("RelationshipSearchCoverage", RelationshipSearchCoverage.serializer())
    registry.register(
        "RelationshipSearchCoverage.Complete",
        RelationshipSearchCoverage.Complete.serializer(),
    )
    registry.register(
        "RelationshipSearchCoverage.Resumable",
        RelationshipSearchCoverage.Resumable.serializer(),
    )
    registry.register(
        "RelationshipSearchCoverage.Limited",
        RelationshipSearchCoverage.Limited.serializer(),
    )
    registry.register("RelationshipResultEvidence", RelationshipResultEvidence.serializer())
    registry.register(
        "RelationshipResultEvidence.Complete",
        RelationshipResultEvidence.Complete.serializer(),
    )
    registry.register(
        "RelationshipResultEvidence.Resumable",
        RelationshipResultEvidence.Resumable.serializer(),
    )
    registry.register(
        "RelationshipResultEvidence.Limited",
        RelationshipResultEvidence.Limited.serializer(),
    )
    registry.register("ContainingSymbolEvidence", ContainingSymbolEvidence.serializer())
    registry.register("ContainingSymbolEvidence.Known", ContainingSymbolEvidence.Known.serializer())
    registry.register("ContainingSymbolEvidence.TopLevel", ContainingSymbolEvidence.TopLevel.serializer())
    registry.register("ContainingSymbolEvidence.Unavailable", ContainingSymbolEvidence.Unavailable.serializer())
    registry.register("ReferencesResult", ReferencesResult.serializer())
    registry.register("CallHierarchyQuery", CallHierarchyQuery.serializer())
    registry.register("CallHierarchyResult", CallHierarchyResult.serializer())
    registry.register("CallHierarchyStats", CallHierarchyStats.serializer())
    registry.register("CallNode", CallNode.serializer())
    registry.register("CallNodeTruncation", CallNodeTruncation.serializer())
    registry.register("TypeHierarchyQuery", TypeHierarchyQuery.serializer())
    registry.register("TypeHierarchyResult", TypeHierarchyResult.serializer())
    registry.register("TypeHierarchyNode", TypeHierarchyNode.serializer())
    registry.register("TypeHierarchyStats", TypeHierarchyStats.serializer())
    registry.register("TypeHierarchyTruncation", TypeHierarchyTruncation.serializer())
    registry.register("SemanticInsertionQuery", SemanticInsertionQuery.serializer())
    registry.register("SemanticInsertionResult", SemanticInsertionResult.serializer())
    registry.register("DiagnosticsQuery", DiagnosticsQuery.serializer())
    registry.register("DiagnosticsResult", DiagnosticsResult.serializer())
    registry.register("FileAnalysisStatus", FileAnalysisStatus.serializer())
    registry.register("Diagnostic", Diagnostic.serializer())
    registry.register("FileOutlineQuery", FileOutlineQuery.serializer())
    registry.register("FileOutlineResult", FileOutlineResult.serializer())
    registry.register("WorkspaceSymbolQuery", WorkspaceSymbolQuery.serializer())
    registry.register("WorkspaceSymbolResult", WorkspaceSymbolResult.serializer())
    registry.register("WorkspaceSearchQuery", WorkspaceSearchQuery.serializer())
    registry.register("SearchMatch", SearchMatch.serializer())
    registry.register("WorkspaceSearchResult", WorkspaceSearchResult.serializer())
    registry.register("WorkspaceFilesQuery", WorkspaceFilesQuery.serializer())
    registry.register("WorkspaceFilesResult", WorkspaceFilesResult.serializer())
    registry.register("SemanticGraphQuery", SemanticGraphQuery.serializer())
    registry.register("SemanticGraphResult", SemanticGraphResult.serializer())
    registry.register("WorkspaceFilesContinuationAction", WorkspaceFilesContinuationAction.serializer())
    registry.register("WorkspaceFilesContinuationQuery", WorkspaceFilesContinuationQuery.serializer())
    registry.registerSynthetic(
        "WorkspaceFilesContinuationQuery.Issue",
        WorkspaceFilesContinuationQuery.serializer(),
    )
    registry.registerSynthetic(
        "WorkspaceFilesContinuationQuery.Consume",
        WorkspaceFilesContinuationQuery.serializer(),
    )
    registry.register("WorkspaceFilesPublicContinuationIdentity", WorkspaceFilesPublicContinuationIdentity.serializer())
    registry.register("WorkspaceFilesPublicContinuationState", WorkspaceFilesPublicContinuationState.serializer())
    registry.register("WorkspaceFilesPublicContinuationProjection", WorkspaceFilesPublicContinuationProjection.serializer())
    registry.register("WorkspaceFilesContinuationResult", WorkspaceFilesContinuationResult.serializer())
    registry.register("WorkspaceFilesContinuationResult.Issued", WorkspaceFilesContinuationResult.Issued.serializer())
    registry.register("WorkspaceFilesContinuationResult.Consumed", WorkspaceFilesContinuationResult.Consumed.serializer())
    registry.register("ImplementationsQuery", ImplementationsQuery.serializer())
    registry.register("ImplementationsResult", ImplementationsResult.serializer())
    registry.register("CodeActionsQuery", CodeActionsQuery.serializer())
    registry.register("CodeActionsResult", CodeActionsResult.serializer())
    registry.register("CodeAction", CodeAction.serializer())
    registry.register("CompletionsQuery", CompletionsQuery.serializer())
    registry.register("CompletionsResult", CompletionsResult.serializer())
    registry.register("CompletionItem", CompletionItem.serializer())

    // Mutation queries & results
    registry.register("RenameQuery", RenameQuery.serializer())
    registry.register("RenameResult", RenameResult.serializer())
    registry.register("ReplacementPlanQuery", ReplacementPlanQuery.serializer())
    registry.register("ReplacementDeclarationSignature", ReplacementDeclarationSignature.serializer())
    registry.register(
        "ReplacementDeclarationSignature.Function",
        ReplacementFunctionSignature.serializer(),
    )
    registry.register(
        "ReplacementDeclarationSignature.Property",
        ReplacementPropertySignature.serializer(),
    )
    registry.register("ReplacementOutboundEvidence", ReplacementOutboundEvidence.serializer())
    registry.register("ReplacementProofDimension", ReplacementProofDimension.serializer())
    registry.register(
        "ReplacementOutboundEvidence.Complete",
        ReplacementOutboundEvidence.Complete.serializer(),
    )
    registry.register(
        "ReplacementOutboundEvidence.Limited",
        ReplacementOutboundEvidence.Limited.serializer(),
    )
    registry.register("ReplacementOutboundTarget", ReplacementOutboundTarget.serializer())
    registry.register("ReplacementOutboundTarget.Source", ReplacementOutboundTarget.Source.serializer())
    registry.register("ReplacementOutboundTarget.External", ReplacementOutboundTarget.External.serializer())
    registry.register("ReplacementPlanResult", ReplacementPlanResult.serializer())
    registry.register("AddFilePlanQuery", AddFilePlanQuery.serializer())
    registry.register("AdditionKotlinPackage", AdditionKotlinPackage.serializer())
    registry.register("AdditionKotlinPackage.Root", AdditionKotlinPackage.Root.serializer())
    registry.register("AdditionKotlinPackage.Named", AdditionKotlinPackage.Named.serializer())
    registry.register("AdditionResolvedTarget", AdditionResolvedTarget.serializer())
    registry.register("AdditionResolvedTarget.Source", AdditionResolvedTarget.Source.serializer())
    registry.register("AdditionResolvedTarget.External", AdditionResolvedTarget.External.serializer())
    registry.register("AdditionRebindingCurrentTarget", AdditionRebindingCurrentTarget.serializer())
    registry.register(
        "AdditionRebindingCurrentTarget.Resolved",
        AdditionRebindingCurrentTarget.Resolved.serializer(),
    )
    registry.register(
        "AdditionRebindingCurrentTarget.Unresolved",
        AdditionRebindingCurrentTarget.Unresolved.serializer(),
    )
    registry.register("AddFilePlanResult", AddFilePlanResult.serializer())
    registry.register("AddDeclarationPlanQuery", AddDeclarationPlanQuery.serializer())
    registry.register("AddDeclarationPlanResult", AddDeclarationPlanResult.serializer())
    registry.register("MutationPostconditionQuery", MutationPostconditionQuery.serializer())
    registry.register("MutationPostconditionAuthority", MutationPostconditionAuthority.serializer())
    registry.register(
        "MutationPostconditionAuthority.Rename",
        MutationPostconditionAuthority.Rename.serializer(),
    )
    registry.register(
        "MutationPostconditionAuthority.Replacement",
        MutationPostconditionAuthority.Replacement.serializer(),
    )
    registry.register(
        "MutationPostconditionAuthority.AddFile",
        MutationPostconditionAuthority.AddFile.serializer(),
    )
    registry.register(
        "MutationPostconditionAuthority.AddDeclaration",
        MutationPostconditionAuthority.AddDeclaration.serializer(),
    )
    registry.register("MutationPostconditionResult", MutationPostconditionResult.serializer())
    registry.register("MutationPostconditionEvidence", MutationPostconditionEvidence.serializer())
    registry.register(
        "MutationPostconditionEvidence.Rename",
        MutationPostconditionEvidence.Rename.serializer(),
    )
    registry.register(
        "MutationPostconditionEvidence.Replacement",
        MutationPostconditionEvidence.Replacement.serializer(),
    )
    registry.register(
        "MutationPostconditionEvidence.AddFile",
        MutationPostconditionEvidence.AddFile.serializer(),
    )
    registry.register(
        "MutationPostconditionEvidence.AddDeclaration",
        MutationPostconditionEvidence.AddDeclaration.serializer(),
    )
    registry.register("RawExactFileObservationQuery", RawExactFileObservationQuery.serializer())
    registry.register("RawExactFileObservationResult", RawExactFileObservationResult.serializer())
    registry.register(
        "RawExactFileObservationResult.Absent",
        RawExactFileObservationResult.Absent.serializer(),
    )
    registry.register(
        "RawExactFileObservationResult.Present",
        RawExactFileObservationResult.Present.serializer(),
    )
    registry.register("ExactFileImageQuery", ExactFileImageQuery.serializer())
    registry.register("ExactFileImageResult", ExactFileImageResult.serializer())
    registry.register("MutationScratchInspectQuery", MutationScratchInspectQuery.serializer())
    registry.register("MutationScratchInspectResult", MutationScratchInspectResult.serializer())
    registry.register("MutationScratchRecoveryQuery", MutationScratchRecoveryQuery.serializer())
    registry.register("MutationScratchRecoveryPreimage", MutationScratchRecoveryPreimage.serializer())
    registry.register(
        "MutationScratchRecoveryPreimage.Absent",
        MutationScratchRecoveryPreimage.Absent.serializer(),
    )
    registry.register(
        "MutationScratchRecoveryPreimage.Present",
        MutationScratchRecoveryPreimage.Present.serializer(),
    )
    registry.register("MutationScratchRecoveryResult", MutationScratchRecoveryResult.serializer())
    registry.register("ImportOptimizeQuery", ImportOptimizeQuery.serializer())
    registry.register("ImportOptimizeResult", ImportOptimizeResult.serializer())
    registry.register("ApplyEditsQuery", ApplyEditsQuery.serializer())
    registry.register("ApplyEditsResult", ApplyEditsResult.serializer())
    registry.register("RefreshQuery", RefreshQuery.serializer())
    registry.register("RefreshResult", RefreshResult.serializer())
    registry.register("SemanticAdmissionStatus", SemanticAdmissionStatus.serializer())

    // FileOperation sealed hierarchy
    registry.register("FileOperation", FileOperation.serializer())
    registry.register("FileOperation.CreateFile", FileOperation.CreateFile.serializer())
    registry.register("FileOperation.DeleteFile", FileOperation.DeleteFile.serializer())
}
