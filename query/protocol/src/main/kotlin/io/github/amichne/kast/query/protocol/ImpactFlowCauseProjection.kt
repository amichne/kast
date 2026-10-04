package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

internal fun ValueFlowUnsupportedCause.impactDocument(): ImpactFlowUnsupportedDocument =
    when (this) {
        ValueFlowUnsupportedCause.EXTERNAL_CALL -> ImpactFlowUnsupportedDocument.EXTERNAL_CALL
        ValueFlowUnsupportedCause.UNMODELED_CALL -> ImpactFlowUnsupportedDocument.UNMODELED_CALL
        ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW -> ImpactFlowUnsupportedDocument.MUTABLE_CONTROL_FLOW
        ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION -> ImpactFlowUnsupportedDocument.UNSUPPORTED_EXPRESSION
        ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE -> ImpactFlowUnsupportedDocument.UNRESOLVED_REFERENCE
        ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY -> ImpactFlowUnsupportedDocument.UNSUPPORTED_PROPERTY
        ValueFlowUnsupportedCause.UNSUPPORTED_RETURN -> ImpactFlowUnsupportedDocument.UNSUPPORTED_RETURN
        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.WORK_LIMIT_REACHED
        ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.RESULT_LIMIT_REACHED
        ValueFlowUnsupportedCause.TIME_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.TIME_LIMIT_REACHED
        ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.BYTE_LIMIT_REACHED
        ValueFlowUnsupportedCause.NESTED_EXECUTION -> ImpactFlowUnsupportedDocument.NESTED_EXECUTION
        ValueFlowUnsupportedCause.OUTSIDE_DOMAIN -> ImpactFlowUnsupportedDocument.OUTSIDE_DOMAIN
    }
