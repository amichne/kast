package io.github.amichne.kast.topology.build.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.GitObjectId
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedInputs
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedSource
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasis
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasisFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalCapturePlan
import io.github.amichne.kast.topology.contract.historical.HistoricalContext
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverage
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalHeader
import io.github.amichne.kast.topology.contract.historical.HistoricalModule
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureOutcome
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalRepositoryId
import io.github.amichne.kast.topology.contract.historical.HistoricalRevision
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import io.github.amichne.kast.topology.contract.historical.HistoricalSourceSet
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/** Scripted effect order and fixed source obligations. These cases make no native compiler claim. */
class HistoricalCaptureCoordinatorTest {
    private val json = Json { classDiscriminator = "status" }

    @Test
    fun `valid captured facts publish once after exact attribution and native completion`() {
        val input = input()
        val snapshot = input.admit().value()
        val calls = mutableListOf<String>()
        val coordinator =
            HistoricalCaptureCoordinator(
                { plan ->
                    calls += "capture"
                    assertEquals(input.header, plan.header)
                    assertEquals(input.files, plan.files)
                    HistoricalNativeCaptureOutcome.Captured(snapshot)
                },
                { basis ->
                    calls += "publish"
                    assertSame(snapshot, basis.snapshot)
                    assertEquals(input.modules, basis.modules)
                    assertEquals(input.files, basis.files)
                    HistoricalPublicationOutcome.Published(basis.snapshot.identity)
                },
            )
        val result =
            assertInstanceOf<HistoricalCaptureResult.Published>(coordinator.capture(plan(input), attribution(input)))
        assertEquals(snapshot.identity, result.identity)
        assertEquals(6L, result.work.gitCalls.value)
        assertEquals(1L, result.work.filesCompared.value)
        assertEquals(64L, result.work.bytesExamined.value)
        assertEquals(listOf("capture", "publish"), calls)
    }

    @Test
    fun `parsed attribution cannot bypass missing native coherence`() {
        val input = input()
        var nativeCalls = 0
        val result =
            HistoricalCaptureCoordinator(
                    {
                        nativeCalls++
                        HistoricalNativeCaptureOutcome.Rejected(
                            HistoricalNativeCaptureFailure.EXTERNAL_INPUT_STABILITY_UNPROVEN
                        )
                    },
                    { error("Publication must not occur after an unproved native capture") },
                )
                .capture(plan(input), attribution(input))
        assertEquals(
            HistoricalNativeCaptureFailure.EXTERNAL_INPUT_STABILITY_UNPROVEN,
            assertInstanceOf<HistoricalCaptureResult.NativeRejected>(result).cause,
        )
        assertEquals(1, nativeCalls)
    }

    @Test
    fun `native header movement refuses publication even when sources match`() {
        val input = input()
        val changed =
            input.copy(header = input.header.copy(context = input.header.context.copy(resolution = digest('f'))))
        assertRejected(input, changed, HistoricalCaptureBasisFailure.HEADER_MISMATCH)
    }

    @Test
    fun `native source shrinkage cannot discharge expected inventory`() {
        val input = input()
        assertRejected(input, input.copy(files = emptyList()), HistoricalCaptureBasisFailure.SOURCE_MISMATCH)
    }

    @Test
    fun `native omission of explicit empty module refuses publication`() {
        val input = input()
        val removed = input.modules.last().id
        assertRejected(
            input,
            input.copy(modules = input.modules.dropLast(1), coverage = input.coverage.filter { it.module != removed }),
            HistoricalCaptureBasisFailure.MODULE_MISMATCH,
        )
    }

    @Test
    fun `mismatched revision rejects before native effects`() {
        val input = input()
        val other =
            input.copy(header = input.header.copy(revision = input.header.revision.copy(commit = objectId('f'))))
        val result = noEffects().capture(plan(input), attribution(other))
        assertEquals(
            HistoricalCaptureBasisFailure.REVISION_MISMATCH,
            assertInstanceOf<HistoricalCaptureResult.BasisRejected>(result).cause,
        )
    }

    @Test
    fun `mismatched blob refuses native effects even when content hashes match`() {
        val input = input()
        val other =
            input.copy(
                files = input.files.map { it.copy(provenance = HistoricalFileProvenance.Tracked(objectId('f'))) }
            )
        val result = noEffects().capture(plan(input), attribution(other))
        assertEquals(
            HistoricalCaptureBasisFailure.SOURCE_MISMATCH,
            assertInstanceOf<HistoricalCaptureResult.BasisRejected>(result).cause,
        )
    }

    @Test
    fun `Git rejection preserves actual work and refuses native effects`() {
        val input = input()
        val encoded =
            json.encodeToString<GitAttributionDocument>(
                GitAttributionDocument.Rejected(
                    HistoricalGitFailure.CHECKOUT_CONTENT_MISMATCH,
                    GitWorkDocument(6, 0, 71),
                )
            )
        val result =
            assertInstanceOf<HistoricalCaptureResult.AttributionRejected>(noEffects().capture(plan(input), encoded))
        assertEquals(HistoricalGitFailure.CHECKOUT_CONTENT_MISMATCH, result.cause)
        assertEquals(71L, result.work.bytesExamined.value)
    }

    @Test
    fun `publication failure remains finite after a captured snapshot`() {
        val input = input()
        val snapshot = input.admit().value()
        val result =
            HistoricalCaptureCoordinator(
                    { HistoricalNativeCaptureOutcome.Captured(snapshot) },
                    { HistoricalPublicationOutcome.Rejected(HistoricalPublicationFailure.CANCELLED) },
                )
                .capture(plan(input), attribution(input))
        assertEquals(
            HistoricalPublicationFailure.CANCELLED,
            assertInstanceOf<HistoricalCaptureResult.PublicationRejected>(result).cause,
        )
    }

    @Test
    fun `fresh staging basis cannot come from an equal but separately issued plan`() {
        val input = input()
        val first = plan(input)
        val second = plan(input)
        val attribution =
            HistoricalAttributedInputs.fromGitObservation(
                    first,
                    input.header.revision,
                    input.files.map {
                        HistoricalAttributedSource(
                            it.path,
                            it.content,
                            (it.provenance as HistoricalFileProvenance.Tracked).blob,
                        )
                    },
                )
                .value()
        val result =
            HistoricalCaptureBasis.fromNativeCapture(
                second,
                attribution,
                HistoricalNativeCaptureOutcome.Captured(input.admit().value()),
            )
        assertEquals(
            HistoricalCaptureBasisFailure.PLAN_MISMATCH,
            assertInstanceOf<Refinement.Rejected<HistoricalCaptureBasisFailure>>(result).failure,
        )
    }

    @Test
    fun `unknown status and negative work are invalid input and have no effects`() {
        val input = input()
        assertEquals(
            HistoricalCaptureResult.InvalidAttribution,
            noEffects().capture(plan(input), "{\"status\":\"unknown\"}"),
        )
        val encoded =
            json.encodeToString<GitAttributionDocument>(
                GitAttributionDocument.Rejected(HistoricalGitFailure.GIT_FAILED, GitWorkDocument(-1, 0, 0))
            )
        assertEquals(HistoricalCaptureResult.InvalidAttribution, noEffects().capture(plan(input), encoded))
    }

    private fun assertRejected(
        original: HistoricalSnapshotInput,
        changed: HistoricalSnapshotInput,
        cause: HistoricalCaptureBasisFailure,
    ) {
        val result =
            HistoricalCaptureCoordinator(
                    { HistoricalNativeCaptureOutcome.Captured(changed.admit().value()) },
                    { error("Publication must not consume a mismatched snapshot") },
                )
                .capture(plan(original), attribution(original))
        assertEquals(cause, assertInstanceOf<HistoricalCaptureResult.BasisRejected>(result).cause)
    }

    private fun noEffects() =
        HistoricalCaptureCoordinator({ error("Unexpected native effect") }, { error("Unexpected publication") })

    private fun plan(input: HistoricalSnapshotInput) =
        HistoricalCapturePlan.admit(input.header, input.modules.mapTo(linkedSetOf()) { it.id }, input.files).value()

    private fun attribution(input: HistoricalSnapshotInput): String =
        json.encodeToString<GitAttributionDocument>(
            GitAttributionDocument.Attributed(
                GitRevisionDocument(
                    input.header.revision.repository.value,
                    input.header.revision.commit.value,
                    input.header.revision.tree.value,
                ),
                input.files.map {
                    GitSourceDocument(
                        it.path.value,
                        it.content.value,
                        (it.provenance as HistoricalFileProvenance.Tracked).blob.value,
                    )
                },
                GitWorkDocument(6, input.files.size.toLong(), 64),
            )
        )

    private fun input(): HistoricalSnapshotInput {
        val modules =
            listOf("api", "empty").map {
                HistoricalModule(module(it), name(":$it"), name(it), HistoricalSourceSet.Named(name("main")))
            }
        return HistoricalSnapshotInput(
            HistoricalHeader(
                HistoricalRevision(HistoricalRepositoryId.parse("fixture:git").value(), objectId('1'), objectId('2')),
                HistoricalContext(digest('3'), digest('4'), digest('5')),
                HistoricalProfile.STRUCTURE_V1,
                name("fixture-1"),
            ),
            modules,
            emptyList(),
            listOf(
                HistoricalFile(
                    WorkspaceSourcePath.parse("src/Api.kt").value(),
                    WorkspaceSourceContentHash.parse("a".repeat(64)).value(),
                    module("api"),
                    HistoricalFileProvenance.Tracked(objectId('b')),
                )
            ),
            emptyList(),
            emptyList(),
            modules.flatMap { owner ->
                HistoricalFactFamily.entries.map { family ->
                    HistoricalCoverage(
                        owner.id,
                        family,
                        if (family == HistoricalFactFamily.REFERENCES) HistoricalCoverageStatus.NotCollected
                        else HistoricalCoverageStatus.Complete,
                    )
                }
            },
        )
    }

    private fun name(text: String) = HistoricalName.parse(text).value()

    private fun module(text: String) = HistoricalModuleId.parse(text).value()

    private fun objectId(character: Char) = GitObjectId.parse(character.toString().repeat(40)).value()

    private fun digest(character: Char) = HistoricalDigest.parse(character.toString().repeat(64)).value()

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
