package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactNativeOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactNativePrerequisiteDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeRelationOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeRelationRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeUnexpectedKindDocument
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueNativeOperation
import io.github.amichne.kast.relation.contract.ValueNativePrerequisite
import io.github.amichne.kast.relation.contract.ValueNativeRelationOrigin
import io.github.amichne.kast.relation.contract.ValueNativeUnexpectedKind

internal fun ValueFlowRejection.Native.nativeDocument(): ImpactNativeReadRejectionDocument.Operational =
    when (this) {
        is ValueFlowRejection.Native.HostUnavailable ->
            ImpactNativeReadRejectionDocument.HostUnavailable(operation.document(), prerequisite.document())
        is ValueFlowRejection.Native.Unexpected ->
            ImpactNativeReadRejectionDocument.Unexpected(
                operation = operation.document(),
                kind = failure.kind.document(),
                exceptionType = failure.exceptionType,
                adapterFrames = failure.adapterFrames,
            )
        is ValueFlowRejection.Native.RelationRejected ->
            ImpactNativeReadRejectionDocument.RelationRejected(origin.document(), rejection.document())
        is ValueFlowRejection.Native.PortUnconfigured ->
            ImpactNativeReadRejectionDocument.PortUnconfigured(operation.document())
    }

private fun ValueNativeOperation.document(): ImpactNativeOperationDocument =
    when (this) {
        ValueNativeOperation.FLOW_READ -> ImpactNativeOperationDocument.FLOW_READ
        ValueNativeOperation.SITE_RESOLUTION -> ImpactNativeOperationDocument.SITE_RESOLUTION
        ValueNativeOperation.PRODUCER_SEED -> ImpactNativeOperationDocument.PRODUCER_SEED
        ValueNativeOperation.MODEL_DECLARATION -> ImpactNativeOperationDocument.MODEL_DECLARATION
        ValueNativeOperation.MODEL_SITE -> ImpactNativeOperationDocument.MODEL_SITE
    }

private fun ValueNativePrerequisite.document(): ImpactNativePrerequisiteDocument =
    when (this) {
        ValueNativePrerequisite.PROJECT_DISPOSED -> ImpactNativePrerequisiteDocument.PROJECT_DISPOSED
        ValueNativePrerequisite.INDEXES_UNAVAILABLE -> ImpactNativePrerequisiteDocument.INDEXES_UNAVAILABLE
    }

private fun ValueNativeUnexpectedKind.document(): ImpactNativeUnexpectedKindDocument =
    when (this) {
        ValueNativeUnexpectedKind.RUNTIME -> ImpactNativeUnexpectedKindDocument.RUNTIME
        ValueNativeUnexpectedKind.LINKAGE -> ImpactNativeUnexpectedKindDocument.LINKAGE
    }

private fun ValueNativeRelationOrigin.document(): ImpactNativeRelationOriginDocument =
    when (this) {
        ValueNativeRelationOrigin.IMPLEMENTATION_DISPATCH -> ImpactNativeRelationOriginDocument.IMPLEMENTATION_DISPATCH
        ValueNativeRelationOrigin.RETURN_CALLER_REFERENCES ->
            ImpactNativeRelationOriginDocument.RETURN_CALLER_REFERENCES
    }

private fun RelationCompilerRejection.document(): ImpactNativeRelationRejectionDocument =
    when (this) {
        RelationCompilerRejection.WORKSPACE_ROOT_MISMATCH ->
            ImpactNativeRelationRejectionDocument.WORKSPACE_ROOT_MISMATCH
        RelationCompilerRejection.GENERATION_MOVED -> ImpactNativeRelationRejectionDocument.GENERATION_MOVED
        RelationCompilerRejection.SCOPE_REJECTED -> ImpactNativeRelationRejectionDocument.SCOPE_REJECTED
        RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE ->
            ImpactNativeRelationRejectionDocument.WORKSPACE_INDEX_UNAVAILABLE
        RelationCompilerRejection.STALE_SELECTOR -> ImpactNativeRelationRejectionDocument.STALE_SELECTOR
        RelationCompilerRejection.OUTSIDE_SCOPE -> ImpactNativeRelationRejectionDocument.OUTSIDE_SCOPE
        RelationCompilerRejection.AMBIGUOUS_SUBJECT -> ImpactNativeRelationRejectionDocument.AMBIGUOUS_SUBJECT
        RelationCompilerRejection.UNSUPPORTED_SUBJECT -> ImpactNativeRelationRejectionDocument.UNSUPPORTED_SUBJECT
        RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE ->
            ImpactNativeRelationRejectionDocument.COMPILER_IDENTITY_UNAVAILABLE
        RelationCompilerRejection.CONTINUATION_CURSOR_MOVED ->
            ImpactNativeRelationRejectionDocument.CONTINUATION_CURSOR_MOVED
        RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION ->
            ImpactNativeRelationRejectionDocument.COMPILER_CONTRACT_VIOLATION
    }
