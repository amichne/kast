package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HistoricalSnapshotContractTest {
    @Test
    fun `structural inventory preserves empty and rootless modules and exact locations`() {
        val snapshot = fixture().admit().value()
        assertEquals(listOf("api", "empty", "rootless"), snapshot.modules.map { it.id.value })
        assertEquals(listOf("Api.kt", "Other.kt"), snapshot.declarations.map { it.id.file.value })
        assertEquals(2, snapshot.declarations.map { it.id }.distinct().size)
        assertEquals(1, snapshot.declarations.map { it.id.compiler.value }.distinct().size)
        assertEquals(HistoricalProfile.STRUCTURE_V1, snapshot.header.profile)
    }

    @Test
    fun `missing terminal coverage never becomes empty complete coverage`() {
        val input = fixture()
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalAdmissionFailure>>(
                input.copy(coverage = input.coverage.dropLast(1)).admit()
            )
        assertEquals(HistoricalAdmissionFailure.COVERAGE_DOMAIN, rejected.failure)
    }

    @Test
    fun `canonical identity includes signature and remains stable under input permutation`() {
        val input = fixture()
        val original = input.admit().value()
        // Independent table and byte framing, computed without the production encoder.
        assertEquals("872c365352cd7fc8875b660ac78022d1e88a5317971e943ce65405303c3941c6", original.identity.value)
        val reordered =
            input.copy(modules = input.modules.reversed(), declarations = input.declarations.reversed()).admit().value()
        assertEquals(original.identity, reordered.identity)
        val changed =
            input.copy(declarations = input.declarations.map { it.copy(signature = name("changed()")) }).admit().value()
        assertNotEquals(original.identity, changed.identity)
    }

    @Test
    fun `structural profile rejects fabricated reference completion`() {
        val input = fixture()
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalAdmissionFailure>>(
                input
                    .copy(
                        coverage =
                            input.coverage.map {
                                if (it.family == HistoricalFactFamily.REFERENCES)
                                    it.copy(status = HistoricalCoverageStatus.Complete)
                                else it
                            }
                    )
                    .admit()
            )
        assertEquals(HistoricalAdmissionFailure.PROFILE_MISMATCH, rejected.failure)
    }

    @Test
    fun `historical selections bind both artifact and exact declaration`() {
        val snapshot = fixture().admit().value()
        val selected = snapshot.reference(snapshot.declarations.first().id).value()
        assertEquals(snapshot.identity, selected.snapshot)
        assertEquals(snapshot.declarations.first().id, selected.declaration)
        val unknown = snapshot.declarations.first().id.copy(file = path("Absent.kt"))
        assertInstanceOf<Refinement.Rejected<HistoricalSelectionFailure>>(snapshot.reference(unknown))
    }

    @Test
    fun `reference targets and declaration owners must resolve to exact stored rows`() {
        val input = fixture()
        val first = input.declarations.first().id
        val missing = first.copy(file = path("Missing.kt"))
        val referenceInput =
            input.copy(
                header = input.header.copy(profile = HistoricalProfile.REFERENCES_V1),
                coverage =
                    input.coverage.map {
                        if (it.family == HistoricalFactFamily.REFERENCES)
                            it.copy(status = HistoricalCoverageStatus.Complete)
                        else it
                    },
                occurrences =
                    listOf(
                        HistoricalOccurrence(
                            first.file,
                            first.range,
                            missing,
                            HistoricalOccurrenceOwner.FileScoped(HistoricalReferenceContext.IMPORT),
                            HistoricalReferenceKind.REFERENCE,
                            HistoricalReferenceContext.IMPORT,
                        )
                    ),
            )
        assertEquals(
            HistoricalAdmissionFailure.INVALID_ENDPOINT,
            assertInstanceOf<Refinement.Rejected<HistoricalAdmissionFailure>>(referenceInput.admit()).failure,
        )
        val occurrence =
            referenceInput.occurrences
                .single()
                .copy(
                    target = first,
                    owner = HistoricalOccurrenceOwner.Declaration(input.declarations.last().id),
                )
        assertEquals(
            HistoricalAdmissionFailure.OWNER_MISMATCH,
            assertInstanceOf<Refinement.Rejected<HistoricalAdmissionFailure>>(
                    referenceInput.copy(occurrences = listOf(occurrence)).admit()
                )
                .failure,
        )
    }

    @Test
    fun `snapshot collections cannot change after admission`() {
        val input = fixture()
        val modules = input.modules.toMutableList()
        val snapshot = input.copy(modules = modules).admit().value()
        val identity = snapshot.identity
        modules.clear()
        assertEquals(3, snapshot.modules.size)
        assertEquals(identity, HistoricalCanonicalEncoding.digest(snapshot))
    }

    @Test
    fun `file scoped ownership preserves the live contexts and rejects code or type ownership`() {
        val input = fixture()
        val declaration = input.declarations.first().id
        for (context in HistoricalReferenceContext.entries) {
            val occurrence =
                HistoricalOccurrence(
                    declaration.file,
                    declaration.range,
                    declaration,
                    HistoricalOccurrenceOwner.FileScoped(context),
                    HistoricalReferenceKind.REFERENCE,
                    context,
                )
            val admitted =
                input
                    .copy(
                        header = input.header.copy(profile = HistoricalProfile.REFERENCES_V1),
                        occurrences = listOf(occurrence),
                        coverage = input.coverage.map { it.copy(status = HistoricalCoverageStatus.Complete) },
                    )
                    .admit()
            when (context) {
                HistoricalReferenceContext.IMPORT,
                HistoricalReferenceContext.ALIASED_IMPORT,
                HistoricalReferenceContext.FILE_ANNOTATION ->
                    assertInstanceOf<Refinement.Refined<HistoricalSnapshot>>(admitted)
                HistoricalReferenceContext.CODE,
                HistoricalReferenceContext.TYPE ->
                    assertEquals(
                        HistoricalAdmissionFailure.OWNER_MISMATCH,
                        assertInstanceOf<Refinement.Rejected<HistoricalAdmissionFailure>>(admitted).failure,
                    )
            }
        }
    }

    private fun fixture(): HistoricalSnapshotInput {
        val api = HistoricalModuleId.parse("api").value()
        val empty = HistoricalModuleId.parse("empty").value()
        val rootless = HistoricalModuleId.parse("rootless").value()
        val modules =
            listOf(api, empty, rootless).map {
                HistoricalModule(
                    it,
                    name(":"),
                    name(it.value),
                    if (it == rootless) HistoricalSourceSet.Rootless else HistoricalSourceSet.Named(name("main")),
                )
            }
        val files =
            listOf("Api.kt", "Other.kt").map {
                HistoricalFile(
                    path(it),
                    WorkspaceSourceContentHash.parse("a".repeat(64)).value(),
                    api,
                    HistoricalFileProvenance.Tracked(GitObjectId.parse("b".repeat(40)).value()),
                )
            }
        return HistoricalSnapshotInput(
            fixtureHeader(),
            modules,
            emptyList(),
            files,
            fixtureDeclarations(files),
            emptyList(),
            modules.flatMap { module ->
                HistoricalFactFamily.entries.map { family ->
                    HistoricalCoverage(
                        module.id,
                        family,
                        if (family == HistoricalFactFamily.REFERENCES) HistoricalCoverageStatus.NotCollected
                        else HistoricalCoverageStatus.Complete,
                    )
                }
            },
        )
    }

    private fun fixtureDeclarations(files: List<HistoricalFile>) = files.map {
        HistoricalDeclaration(
            HistoricalDeclarationId(
                CompilerSymbolIdentity.parse("same-id").value(),
                it.path,
                ExactDeclarationTextRange.parse(0, 12).value(),
            ),
            name("api"),
            name("api()"),
            CompilerSymbolKind.FUNCTION,
        )
    }

    private fun fixtureHeader() =
        HistoricalHeader(
            HistoricalRevision(
                HistoricalRepositoryId.parse("fixture:repository").value(),
                GitObjectId.parse("1".repeat(40)).value(),
                GitObjectId.parse("2".repeat(40)).value(),
            ),
            HistoricalContext(digest("3"), digest("4"), digest("5")),
            HistoricalProfile.STRUCTURE_V1,
            name("fixture-1"),
        )

    private fun name(value: String) = HistoricalName.parse(value).value()

    private fun path(value: String) = WorkspaceSourcePath.parse(value).value()

    private fun digest(character: String) = HistoricalDigest.parse(character.repeat(64)).value()

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
