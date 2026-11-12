package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ReadLimits
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
import io.github.amichne.kast.relation.contract.ValueNativeUnexpectedFailure
import io.github.amichne.kast.relation.contract.ValueNativeUnexpectedKind

/** Independent expected projections, including required evidence rather than just matching discriminators. */
internal fun nativeRejectionCases(): List<Pair<ValueFlowRejection, ImpactNativeReadRejectionDocument>> =
    listOf(
        ValueFlowRejection.STALE_SITE to ImpactNativeReadRejectionDocument.STALE_SITE,
        ValueFlowRejection.OUTSIDE_DOMAIN to ImpactNativeReadRejectionDocument.OUTSIDE_DOMAIN,
        ValueFlowRejection.OWNER_UNAVAILABLE to ImpactNativeReadRejectionDocument.OWNER_UNAVAILABLE,
        ValueFlowRejection.AUTHORITY_MOVED to ImpactNativeReadRejectionDocument.AUTHORITY_MOVED,
        ValueFlowRejection.UNSUPPORTED_SEED to ImpactNativeReadRejectionDocument.UNSUPPORTED_SEED,
        ValueFlowRejection.UNRESOLVED_SEED to ImpactNativeReadRejectionDocument.UNRESOLVED_SEED,
        ValueFlowRejection.GRANT_TOO_SMALL to ImpactNativeReadRejectionDocument.GRANT_TOO_SMALL,
        ValueFlowRejection.NESTED_EXECUTION to ImpactNativeReadRejectionDocument.NESTED_EXECUTION,
    ) + readinessCases() + unexpectedCases() + nestedCases()

private fun readinessCases(): List<Pair<ValueFlowRejection, ImpactNativeReadRejectionDocument>> =
    listOf(
        ValueFlowRejection.Native.HostUnavailable(
            ValueNativeOperation.FLOW_READ,
            ValueNativePrerequisite.PROJECT_DISPOSED,
        ) to
            ImpactNativeReadRejectionDocument.HostUnavailable(
                ImpactNativeOperationDocument.FLOW_READ,
                ImpactNativePrerequisiteDocument.PROJECT_DISPOSED,
            ),
        ValueFlowRejection.Native.HostUnavailable(
            ValueNativeOperation.MODEL_SITE,
            ValueNativePrerequisite.INDEXES_UNAVAILABLE,
        ) to
            ImpactNativeReadRejectionDocument.HostUnavailable(
                ImpactNativeOperationDocument.MODEL_SITE,
                ImpactNativePrerequisiteDocument.INDEXES_UNAVAILABLE,
            ),
    )

private fun unexpectedCases(): List<Pair<ValueFlowRejection, ImpactNativeReadRejectionDocument>> =
    listOf(
        ValueFlowRejection.Native.Unexpected(
            ValueNativeOperation.PRODUCER_SEED,
            ValueNativeUnexpectedFailure.fromBoundary(
                kind = ValueNativeUnexpectedKind.RUNTIME,
                exceptionType = "java.lang.IllegalStateException",
                adapterFrames = listOf("io.github.amichne.kast.Adapter.read:12"),
                limits = ReadLimits.Default,
            ),
        ) to
            ImpactNativeReadRejectionDocument.Unexpected(
                operation = ImpactNativeOperationDocument.PRODUCER_SEED,
                kind = ImpactNativeUnexpectedKindDocument.RUNTIME,
                exceptionType = "java.lang.IllegalStateException",
                adapterFrames = listOf("io.github.amichne.kast.Adapter.read:12"),
            ),
        ValueFlowRejection.Native.Unexpected(
            ValueNativeOperation.FLOW_READ,
            ValueNativeUnexpectedFailure.fromBoundary(
                kind = ValueNativeUnexpectedKind.LINKAGE,
                exceptionType = "java.lang.NoSuchMethodError",
                adapterFrames = emptyList(),
                limits = ReadLimits.Default,
            ),
        ) to
            ImpactNativeReadRejectionDocument.Unexpected(
                operation = ImpactNativeOperationDocument.FLOW_READ,
                kind = ImpactNativeUnexpectedKindDocument.LINKAGE,
                exceptionType = "java.lang.NoSuchMethodError",
                adapterFrames = emptyList(),
            ),
    )

private fun nestedCases(): List<Pair<ValueFlowRejection, ImpactNativeReadRejectionDocument>> =
    listOf(
        ValueFlowRejection.Native.RelationRejected(
            ValueNativeRelationOrigin.IMPLEMENTATION_DISPATCH,
            RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE,
        ) to
            ImpactNativeReadRejectionDocument.RelationRejected(
                ImpactNativeRelationOriginDocument.IMPLEMENTATION_DISPATCH,
                ImpactNativeRelationRejectionDocument.WORKSPACE_INDEX_UNAVAILABLE,
            ),
        ValueFlowRejection.Native.RelationRejected(
            ValueNativeRelationOrigin.RETURN_CALLER_REFERENCES,
            RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION,
        ) to
            ImpactNativeReadRejectionDocument.RelationRejected(
                ImpactNativeRelationOriginDocument.RETURN_CALLER_REFERENCES,
                ImpactNativeRelationRejectionDocument.COMPILER_CONTRACT_VIOLATION,
            ),
        ValueFlowRejection.Native.PortUnconfigured(ValueNativeOperation.FLOW_READ) to
            ImpactNativeReadRejectionDocument.PortUnconfigured(ImpactNativeOperationDocument.FLOW_READ),
    )
