package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSourceDependencyBindingTest {
    @Test
    fun `an immutable fitted page replays while its matching public successor is running`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(ReadLimits.Default)
        val initial = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val cursor = initial.issue(sourceCursorProof(fixture)).sourceFixtureValue()
        val token = ProtocolText.parse(cursor.value).sourceFixtureValue()
        initial.finish(sourceCursorFitted(fixture, token)).sourceFixtureValue()
        initial.commit().sourceFixtureValue()
        val resumed = store.acquire(fixture.request, fixture.owner.authority, token, 65_536).sourceFixtureValue()
        val request = sourceCursorRequest(fixture)
        val admitted =
            resumed
                .admit(fixture.selected.snapshot.context, request.copy(page = SourceReadPage.Continue(cursor)))
                .sourceFixtureValue()
        val proof =
            SourceReadCursorProof.create(request, fixture.selected, admitted, 2, sourceCursorTraversal(fixture, 2))
                .sourceFixtureValue()
        val successor = ProtocolText.parse(resumed.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        val fitted = sourceCursorFitted(fixture, successor)
        resumed.finish(fitted).sourceFixtureValue()
        resumed.commit().sourceFixtureValue()
        val child = store.acquire(fixture.request, fixture.owner.authority, successor, 65_536).sourceFixtureValue()
        val protected = store.entries.getValue(successor)
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.IN_USE),
            store.acquire(fixture.request, fixture.owner.authority, successor, 65_536),
        )
        val replay = store.acquire(fixture.request, fixture.owner.authority, token, 65_536).sourceFixtureValue()
        assertEquals(
            (fitted as HostedResponse.Canonical<*, *, *>).semantic,
            (replay.input as HostedSourceInput.Published).outcome,
        )
        replay.finish(fitted).sourceFixtureValue()
        replay.commit().sourceFixtureValue()
        assertEquals(protected, store.entries.getValue(successor))
        child.discard()
        store.checkSourceCursorAvailable(fixture, successor)
    }

    @Test
    fun `fitted publication cannot advertise another semantic requests live cursor under the same authority`() =
        runTest {
            val fixture = HostedSourcePagingFixture.create()
            val store = HostedSourceStateStore(ReadLimits.Default)
            val broaderRequest =
                fixture.request.copy(
                    entities =
                        SourceEntitySelectionDocument.Matching(
                            SourceContainmentDocument.DESCENDANTS,
                            listOf(SourceEntityFilterDocument.Parameters, SourceEntityFilterDocument.Calls),
                        )
                )
            val broader = store.acquire(broaderRequest, fixture.owner.authority, null, 65_536).sourceFixtureValue()
            val foreign =
                ProtocolText.parse(broader.issue(broaderSourceProof(fixture)).sourceFixtureValue().value)
                    .sourceFixtureValue()
            broader.finish(sourceCursorFitted(fixture, foreign)).sourceFixtureValue()
            broader.commit().sourceFixtureValue()
            store.acquire(broaderRequest, fixture.owner.authority, foreign, 65_536).sourceFixtureValue().discard()
            assertEquals(
                Refinement.Rejected(HostedOutputAcquisitionFailure.MISMATCH),
                store.acquire(fixture.request, fixture.owner.authority, foreign, 65_536),
            )
            val protected = store.entries.getValue(foreign)
            val current = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
            val owned =
                ProtocolText.parse(current.issue(sourceCursorProof(fixture)).sourceFixtureValue().value)
                    .sourceFixtureValue()
            current.finish(sourceCursorFitted(fixture, foreign)).sourceFixtureValue()
            assertEquals(
                Refinement.Rejected(
                    HostedQueryFailure.Publication(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
                ),
                current.commit(),
            )
            current.discard()
            assertEquals(protected, store.entries.getValue(foreign))
            assertEquals(
                Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
                store.acquire(fixture.request, fixture.owner.authority, owned, 65_536),
            )
            store.acquire(broaderRequest, fixture.owner.authority, foreign, 65_536).sourceFixtureValue().discard()
            assertEquals(1, store.entries.size)
        }

    private fun broaderSourceProof(fixture: HostedSourcePagingFixture): SourceReadCursorProof {
        val request =
            sourceCursorRequest(fixture)
                .copy(
                    entities =
                        EntitySelection.matching(
                                Containment.DESCENDANTS,
                                listOf(EntityFilter.Parameters, EntityFilter.Calls),
                            )
                            .sourceFixtureValue()
                )
        return SourceReadCursorProof.create(
                request,
                fixture.selected,
                SourceReadEntityCursor.First,
                1,
                sourceCursorTraversal(fixture, 1),
            )
            .sourceFixtureValue()
    }
}
