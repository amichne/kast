package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactImplementationPeerIdentityTest {
    @Test
    fun `reviewed peer rule accepts the same qualified call with fresh work observations`() {
        val f = QualifiedPeerFixture()
        assertTrue(f.path.hasValidPeerContinuation(f.basis))
        assertEquals(Refinement.Refined(Unit), f.result().validateImpactAccounting())

        val changedCoverage =
            f.native.target.dispatch.copy(
                coverage =
                    ImpactImplementationCoverageDocument.TerminalIncomplete(
                        f.values.count(2),
                        f.values.bounded(listOf(RelationLimitationDocument.UNRESOLVED_TARGET)),
                    )
            )
        val changedFrame = f.native.frame.copy(target = f.native.target.copy(dispatch = changedCoverage))
        val changed = f.path.copy(producer = f.source.copy(callStack = f.values.bounded(listOf(changedFrame))))
        assertFalse(changed.hasValidPeerContinuation(f.basis))
    }

    @Test
    fun `peer obligations and representation history keep exact positions without freezing work observations`() {
        val f = QualifiedPeerFixture()
        val obligation = f.values.obligation.copy(position = f.rule.source)
        val withObligation =
            f.path.copy(
                steps =
                    f.values.bounded(
                        listOf(
                            ImpactPathStepDocument.ModeledBoundary(
                                f.connection.copy(obligations = f.values.bounded(listOf(obligation)))
                            )
                        )
                    )
            )
        val history =
            ImpactRepresentationHistoryDocument.BoundaryModel(f.values.reference, f.rule.source, f.rule.target)
        val withHistory = f.path.copy(representation = f.representation(history))
        assertAll(
            { assertTrue(withObligation.hasValidPeerContinuation(f.basis)) },
            { assertTrue(withHistory.hasValidPeerContinuation(f.basis)) },
        )

        val wrong = obligation.copy(position = obligation.position.copy(slot = f.values.id("another-slot")))
        val wrongConnection = f.connection.copy(obligations = f.values.bounded(listOf(wrong)))
        val wrongPath =
            f.path.copy(steps = f.values.bounded(listOf(ImpactPathStepDocument.ModeledBoundary(wrongConnection))))
        assertFalse(wrongPath.hasValidPeerContinuation(f.basis))
        val wrongHistory = history.copy(target = history.target.copy(slot = f.values.id("another-slot")))
        assertFalse(f.path.copy(representation = f.representation(wrongHistory)).hasValidPeerContinuation(f.basis))
    }
}

private class QualifiedPeerFixture {
    val native = ImplementationShapeFixture()
    private val peer = ImpactPeerFixture()
    val values = native.values
    val basis = native.basis
    val source = native.argument.copy(callStack = values.bounded(listOf(native.frame)))
    private val freshFrame =
        native.frame.copy(
            target = native.target.copy(dispatch = native.dispatch.copy(examinedWorkUnits = values.count(7)))
        )
    private val freshSource = source.copy(callStack = values.bounded(listOf(freshFrame)))
    val rule = peer.rule.copy(source = peer.rule.source.copy(site = source))
    val connection = peer.connection.copy(rule = rule.copy(source = rule.source.copy(site = freshSource)))
    val path =
        peer.path.copy(
            producer = source,
            steps = values.bounded(listOf(ImpactPathStepDocument.ModeledBoundary(connection))),
        )

    fun result(): QueryRunResult {
        val original = peer.result(path)
        val from = original.question.from as QueryFromDocument.Impact
        val model = from.investigation.models.values.single() as ImpactModelDocument.Boundary
        return original.copy(
            question =
                original.question.copy(
                    from =
                        from.copy(
                            investigation =
                                from.investigation.copy(
                                    models = values.bounded(listOf(model.copy(rules = values.bounded(listOf(rule)))))
                                )
                        )
                ),
            impactAccounting = peer.accounting().copy(seeds = values.bounded(listOf(source))),
        )
    }

    fun representation(history: ImpactRepresentationHistoryDocument): ImpactRepresentationEvidenceDocument =
        ImpactRepresentationEvidenceDocument.Present(
            peer.target,
            values.bounded(
                listOf(
                    ImpactRepresentationBranchDocument(
                        ImpactRepresentationCurrentDocument.Known(values.state),
                        values.bounded(listOf(history)),
                    )
                )
            ),
        )
}
