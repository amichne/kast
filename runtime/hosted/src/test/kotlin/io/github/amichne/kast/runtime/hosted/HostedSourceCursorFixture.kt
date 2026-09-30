package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection

internal fun sourceCursorTraversal(
    fixture: HostedSourcePagingFixture,
    revision: Long,
): io.github.amichne.kast.source.contract.SourceEntityTraversalState {
    val region = fixture.selected
    val range =
        io.github.amichne.kast.source.contract.SourceRange.create(
                region.snapshot,
                io.github.amichne.kast.source.contract.Utf16CodeUnitOffset.parse(5).sourceFixtureValue(),
                io.github.amichne.kast.source.contract.Utf16CodeUnitOffset.parse(6).sourceFixtureValue(),
            )
            .sourceFixtureValue()
    val selector =
        io.github.amichne.kast.source.contract.SourceSelector.issueEntity(
                region,
                io.github.amichne.kast.source.contract.NonEmptySourceRange.create(range).sourceFixtureValue(),
                io.github.amichne.kast.source.contract.SourceEntityKind.VALUE_PARAMETER,
                io.github.amichne.kast.source.contract.SourceEntityName.present("p5").sourceFixtureValue(),
            )
            .sourceFixtureValue()
    val entity =
        io.github.amichne.kast.source.contract.SourceEntity.ValueParameter.create(
                selector,
                io.github.amichne.kast.source.contract.SourceNestingDepth.parse(1).sourceFixtureValue(),
            )
            .sourceFixtureValue()
    return io.github.amichne.kast.source.contract.SourceEntityTraversalState.create(
            region,
            revision,
            listOf(io.github.amichne.kast.source.contract.SourceEntityTraversalTask.ProvenEntity(entity)),
        )
        .sourceFixtureValue()
}

internal fun sourceCursorRequest(fixture: HostedSourcePagingFixture) =
    SourceReadRequest(
        SourceReadAnchor.Source(fixture.selected),
        RegionSelection.File,
        EntitySelection.matching(Containment.DESCENDANTS, listOf(EntityFilter.Parameters)).sourceFixtureValue(),
        TextProjection.None,
        SourceEntityLimit.parse(6).sourceFixtureValue(),
        SourceTextByteLimit.parse(50_000).sourceFixtureValue(),
        SourceReadPage.First,
        ResourceBudget(
            ResultLimit.parse(6).sourceFixtureValue(),
            WorkUnitLimit.parse(100).sourceFixtureValue(),
            ElapsedTimeLimitMillis.parse(1000).sourceFixtureValue(),
        ),
    )

internal fun sourceCursorFitted(fixture: HostedSourcePagingFixture, token: ProtocolText) =
    HostedResponse.Canonical.encode(
        CanonicalOperationWireBindings.sourceRead,
        OperationOutcome.Qualified(
            fixture.outcome.evidence,
            SourceReadQualification.create(
                    fixture.outcome.qualification.knownMinimumEntityCount,
                    listOf(SourceReadLimitationDocument.ENTITY_LIMIT_REACHED),
                    SourceQualifiedProgressDocument.Resumable(
                        SourceCheckpointDocument.Upstream(token),
                        ReadResumeActionDocument.RESUME,
                    ),
                )
                .sourceFixtureValue(),
        ),
    )

internal fun sourceCursorLimits(vararg values: Pair<ReadLimitParameter, String>) =
    ReadLimits.resolve(environment = values.associate { (key, value) -> key.environmentKey to value })
        .sourceFixtureValue()

internal fun sourceCursorProof(fixture: HostedSourcePagingFixture) =
    SourceReadCursorProof.create(
            sourceCursorRequest(fixture),
            fixture.selected,
            SourceReadEntityCursor.First,
            1,
            sourceCursorTraversal(fixture, 1),
        )
        .sourceFixtureValue()

internal fun HostedSourceStateStore.checkSourceCursorAvailable(
    fixture: HostedSourcePagingFixture,
    token: ProtocolText,
) {
    acquire(fixture.request.copy(page = SourceReadPageDocument.Continue(token)), fixture.owner.authority, token, 65_536)
        .sourceFixtureValue()
        .discard()
}
