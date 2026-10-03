package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactExecutionAccountingCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionBoundaryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionLedgerCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionModelHistoryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionPathCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRepresentationCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRowIdentityCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionSelectionCause
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactLedgerFailure
import io.github.amichne.kast.query.contract.QueryImpactModelHistoryFailure
import io.github.amichne.kast.query.contract.QueryImpactPathFailure
import io.github.amichne.kast.query.contract.QueryImpactRowIdentityFailure
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QueryValuePathAccountingFailure
import io.github.amichne.kast.relation.contract.BoundaryModelFailure
import io.github.amichne.kast.relation.contract.RepresentationPropagationFailure

internal fun QueryImpactExecutionFailure.executionDocument(): ImpactExecutionFailureDocument =
    when (this) {
        QueryImpactExecutionFailure.PresentationOnly -> ImpactExecutionFailureDocument.PresentationOnly

        is QueryImpactExecutionFailure.Path -> ImpactExecutionFailureDocument.Path(cause.executionCauseDocument())
        is QueryImpactExecutionFailure.Ledger -> ImpactExecutionFailureDocument.Ledger(cause.executionCauseDocument())
        is QueryImpactExecutionFailure.Accounting ->
            ImpactExecutionFailureDocument.Accounting(cause.executionCauseDocument())
        is QueryImpactExecutionFailure.Representation ->
            ImpactExecutionFailureDocument.Representation(cause.executionCauseDocument())
        is QueryImpactExecutionFailure.Boundary ->
            ImpactExecutionFailureDocument.Boundary(cause.executionCauseDocument())
        is QueryImpactExecutionFailure.ModelHistory ->
            ImpactExecutionFailureDocument.ModelHistory(cause.executionCauseDocument())
        is QueryImpactExecutionFailure.Selection ->
            ImpactExecutionFailureDocument.Selection(cause.executionCauseDocument())
        is QueryImpactExecutionFailure.RowIdentity ->
            ImpactExecutionFailureDocument.RowIdentity(cause.executionCauseDocument())
    }

private fun QueryImpactPathFailure.executionCauseDocument(): ImpactExecutionPathCause =
    when (this) {
        QueryImpactPathFailure.DISCONNECTED_STEP -> ImpactExecutionPathCause.DISCONNECTED_STEP
        QueryImpactPathFailure.TERMINAL_SITE_MISMATCH -> ImpactExecutionPathCause.TERMINAL_SITE_MISMATCH
        QueryImpactPathFailure.REPRESENTATION_SITE_MISMATCH -> ImpactExecutionPathCause.REPRESENTATION_SITE_MISMATCH
        QueryImpactPathFailure.REPRESENTATION_PATH_MISMATCH -> ImpactExecutionPathCause.REPRESENTATION_PATH_MISMATCH
        QueryImpactPathFailure.CONSUMER_REPRESENTATION_MISMATCH ->
            ImpactExecutionPathCause.CONSUMER_REPRESENTATION_MISMATCH
        QueryImpactPathFailure.EXCLUSION_UNPROVEN -> ImpactExecutionPathCause.EXCLUSION_UNPROVEN
        QueryImpactPathFailure.TERMINAL_UNPROVEN -> ImpactExecutionPathCause.TERMINAL_UNPROVEN
    }

private fun QueryImpactLedgerFailure.executionCauseDocument(): ImpactExecutionLedgerCause =
    when (this) {
        QueryImpactLedgerFailure.DUPLICATE_REQUESTED_SITE -> ImpactExecutionLedgerCause.DUPLICATE_REQUESTED_SITE
        QueryImpactLedgerFailure.FOREIGN_REQUESTED_SITE -> ImpactExecutionLedgerCause.FOREIGN_REQUESTED_SITE
        QueryImpactLedgerFailure.EMPTY_SEEDS -> ImpactExecutionLedgerCause.EMPTY_SEEDS
        QueryImpactLedgerFailure.DUPLICATE_SEED -> ImpactExecutionLedgerCause.DUPLICATE_SEED
        QueryImpactLedgerFailure.DUPLICATE_PATH -> ImpactExecutionLedgerCause.DUPLICATE_PATH
        QueryImpactLedgerFailure.FOREIGN_PRODUCER -> ImpactExecutionLedgerCause.FOREIGN_PRODUCER
        QueryImpactLedgerFailure.MISSING_SEED_PATH -> ImpactExecutionLedgerCause.MISSING_SEED_PATH
        QueryImpactLedgerFailure.DOMAIN_MISMATCH -> ImpactExecutionLedgerCause.DOMAIN_MISMATCH
        QueryImpactLedgerFailure.CONFLICTING_OBSERVATIONS -> ImpactExecutionLedgerCause.CONFLICTING_OBSERVATIONS
        QueryImpactLedgerFailure.MISSING_NATIVE_OBSERVATION -> ImpactExecutionLedgerCause.MISSING_NATIVE_OBSERVATION
        QueryImpactLedgerFailure.UNPROVEN_COMPILER_STEP -> ImpactExecutionLedgerCause.UNPROVEN_COMPILER_STEP
        QueryImpactLedgerFailure.MISSING_BRANCH -> ImpactExecutionLedgerCause.MISSING_BRANCH
        QueryImpactLedgerFailure.MISSING_OBLIGATION -> ImpactExecutionLedgerCause.MISSING_OBLIGATION
        QueryImpactLedgerFailure.UNDECLARED_MODEL -> ImpactExecutionLedgerCause.UNDECLARED_MODEL
        QueryImpactLedgerFailure.DUPLICATE_MODEL_REFERENCE -> ImpactExecutionLedgerCause.DUPLICATE_MODEL_REFERENCE
        QueryImpactLedgerFailure.UNPROVEN_TERMINAL -> ImpactExecutionLedgerCause.UNPROVEN_TERMINAL
        QueryImpactLedgerFailure.UNACCOUNTED_NATIVE_READ -> ImpactExecutionLedgerCause.UNACCOUNTED_NATIVE_READ
        QueryImpactLedgerFailure.PRODUCER_IDENTITY_MISMATCH -> ImpactExecutionLedgerCause.PRODUCER_IDENTITY_MISMATCH
    }

private fun QueryValuePathAccountingFailure.executionCauseDocument(): ImpactExecutionAccountingCause =
    when (this) {
        QueryValuePathAccountingFailure.DUPLICATE_PATH -> ImpactExecutionAccountingCause.DUPLICATE_PATH
    }

private fun RepresentationPropagationFailure.executionCauseDocument(): ImpactExecutionRepresentationCause =
    when (this) {
        RepresentationPropagationFailure.SITE_MISMATCH -> ImpactExecutionRepresentationCause.SITE_MISMATCH
        RepresentationPropagationFailure.BASIS_MISMATCH -> ImpactExecutionRepresentationCause.BASIS_MISMATCH
        RepresentationPropagationFailure.CALLABLE_MISMATCH -> ImpactExecutionRepresentationCause.CALLABLE_MISMATCH
        RepresentationPropagationFailure.POSITION_MISMATCH -> ImpactExecutionRepresentationCause.POSITION_MISMATCH
        RepresentationPropagationFailure.EMPTY_MERGE -> ImpactExecutionRepresentationCause.EMPTY_MERGE
    }

private fun BoundaryModelFailure.executionCauseDocument(): ImpactExecutionBoundaryCause =
    when (this) {
        BoundaryModelFailure.SOURCE_MISMATCH -> ImpactExecutionBoundaryCause.SOURCE_MISMATCH
        BoundaryModelFailure.TARGET_MISMATCH -> ImpactExecutionBoundaryCause.TARGET_MISMATCH
        BoundaryModelFailure.BASIS_MISMATCH -> ImpactExecutionBoundaryCause.BASIS_MISMATCH
        BoundaryModelFailure.KIND_MISMATCH -> ImpactExecutionBoundaryCause.KIND_MISMATCH
    }

private fun QueryImpactModelHistoryFailure.executionCauseDocument(): ImpactExecutionModelHistoryCause =
    when (this) {
        QueryImpactModelHistoryFailure.MISSING_APPLICATION -> ImpactExecutionModelHistoryCause.MISSING_APPLICATION
    }

private fun QueryRetainedResultFailure.executionCauseDocument(): ImpactExecutionSelectionCause =
    when (this) {
        QueryRetainedResultFailure.EXECUTION_REJECTED -> ImpactExecutionSelectionCause.EXECUTION_REJECTED
        QueryRetainedResultFailure.PRESENTATION_ONLY_ROWS -> ImpactExecutionSelectionCause.PRESENTATION_ONLY_ROWS
        QueryRetainedResultFailure.BASIS_MISMATCH -> ImpactExecutionSelectionCause.BASIS_MISMATCH
        QueryRetainedResultFailure.INCONSISTENT_COVERAGE -> ImpactExecutionSelectionCause.INCONSISTENT_COVERAGE
        QueryRetainedResultFailure.UNKNOWN_ROW -> ImpactExecutionSelectionCause.UNKNOWN_ROW
        QueryRetainedResultFailure.DUPLICATE_ROW -> ImpactExecutionSelectionCause.DUPLICATE_ROW
    }

private fun QueryImpactRowIdentityFailure.executionCauseDocument(): ImpactExecutionRowIdentityCause =
    when (this) {
        QueryImpactRowIdentityFailure.CHANGED_RETAINED_ROWS -> ImpactExecutionRowIdentityCause.CHANGED_RETAINED_ROWS
    }
