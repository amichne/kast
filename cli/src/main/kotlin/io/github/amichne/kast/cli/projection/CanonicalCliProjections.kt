package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliProjection
import io.github.amichne.kast.cli.TypedCliProjection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** The exact eleven generated wire projections installed behind the public CLI. */
internal fun canonicalCliProjections(): List<CliProjection> = listOf(
    TypedCliProjection(
        CanonicalOperationWireBindings.workspaceInspect,
        workspaceInspectCliParser,
        workspaceInspectCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.symbolDiscover,
        symbolDiscoverCliParser,
        symbolDiscoverCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.symbolResolve,
        symbolResolveCliParser,
        symbolResolveCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.symbolDescribe,
        symbolDescribeCliParser,
        symbolDescribeCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.relationRead,
        relationReadCliParser,
        relationReadCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.traversalRun,
        traversalRunCliParser,
        traversalRunCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.diagnosticCheck,
        diagnosticCheckCliParser,
        diagnosticCheckCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.changePlan,
        changePlanCliParser,
        changePlanCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.changeApply,
        changeApplyCliParser,
        changeApplyCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.changeVerify,
        changeVerifyCliParser,
        changeVerifyCliProjector,
    ),
    TypedCliProjection(
        CanonicalOperationWireBindings.changeRecover,
        changeRecoverCliParser,
        changeRecoverCliProjector,
    ),
)
