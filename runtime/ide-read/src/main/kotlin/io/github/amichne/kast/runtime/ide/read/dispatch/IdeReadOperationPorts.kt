package io.github.amichne.kast.runtime.ide.read.dispatch

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult

/** Exact workspace inspection authority supplied by the later hosted composition. */
fun interface WorkspaceInspectReadPort {
    suspend fun execute(
        request: WorkspaceInspectRequest,
    ): OperationOutcome<
        WorkspaceInspectResult,
        WorkspaceInspectQualification,
        WorkspaceInspectRejection,
        >
}

/** Exact symbol discovery authority supplied by the later hosted composition. */
fun interface SymbolDiscoverReadPort {
    suspend fun execute(
        request: SymbolDiscoverRequest,
    ): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        >
}

/**
 * Proof transition: `SymbolResolveRequest ->
 * OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection>`.
 *
 * Establishes either one exact selector or the closed [SymbolResolveRejection]. The uninhabited
 * qualification type excludes partial success for this complete-required operation. Raw candidate
 * selector extraction is permitted only at the hosted symbol-resolution boundary.
 */
fun interface SymbolResolveReadPort {
    suspend fun execute(
        request: SymbolResolveRequest,
    ): OperationOutcome<
        SymbolResolveResult,
        Nothing,
        SymbolResolveRejection,
        >
}

/**
 * Proof transition: `SymbolDescribeRequest ->
 * OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection>`.
 *
 * Establishes either the exact symbol description or the closed [SymbolDescribeRejection]. The
 * uninhabited qualification type excludes partial success for this complete-required operation.
 * Raw exact-selector extraction is permitted only at the hosted symbol-description boundary.
 */
fun interface SymbolDescribeReadPort {
    suspend fun execute(
        request: SymbolDescribeRequest,
    ): OperationOutcome<
        SymbolDescribeResult,
        Nothing,
        SymbolDescribeRejection,
        >
}
