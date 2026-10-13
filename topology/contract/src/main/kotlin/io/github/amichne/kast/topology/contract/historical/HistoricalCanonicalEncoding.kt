package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Version 1 uses UTF-8 byte lengths, a colon, bytes, and LF for every field. Each row includes its family tag. */
object HistoricalCanonicalEncoding {
    const val SCHEMA_VERSION = 1
    const val COORDINATES = "UTF16_ZERO_BASED_END_EXCLUSIVE"

    val occurrenceOrder: Comparator<HistoricalOccurrence> = compareBy { occurrence(it).joinToString("\u0000") }

    fun header(value: HistoricalHeader): List<String> =
        listOf(
            "manifest",
            SCHEMA_VERSION.toString(),
            COORDINATES,
            value.revision.repository.value,
            value.revision.commit.value,
            value.revision.tree.value,
            value.context.model.value,
            value.context.resolution.value,
            value.context.compiler.value,
            value.profile.name,
            value.producer.value,
        )

    fun module(value: HistoricalModule): List<String> =
        listOf("module", value.id.value, value.project.value, value.name.value) +
            when (val sourceSet = value.sourceSet) {
                is HistoricalSourceSet.Named -> listOf("named", sourceSet.name.value)
                HistoricalSourceSet.Rootless -> listOf("rootless")
            }

    fun dependency(value: HistoricalDependency): List<String> =
        listOf("dependency", value.source.value, value.target.value)

    fun file(value: HistoricalFile): List<String> =
        listOf("file", value.path.value, value.content.value, value.module.value) +
            when (val provenance = value.provenance) {
                is HistoricalFileProvenance.Tracked -> listOf("tracked", provenance.blob.value)
                is HistoricalFileProvenance.Generated -> listOf("generated", provenance.producer.value)
            }

    fun declarationId(value: HistoricalDeclarationId): List<String> =
        listOf(
            value.file.value,
            value.range.startInclusive.toString(),
            value.range.endExclusive.toString(),
            value.compiler.value,
        )

    fun declaration(value: HistoricalDeclaration): List<String> =
        listOf("declaration") +
            declarationId(value.id) +
            listOf(value.name.value, value.signature.value, value.kind.name)

    fun occurrence(value: HistoricalOccurrence): List<String> =
        listOf(
            "occurrence",
            value.sourceFile.value,
            value.range.startInclusive.toString(),
            value.range.endExclusive.toString(),
            value.kind.name,
            value.context.name,
        ) +
            declarationId(value.target) +
            when (val owner = value.owner) {
                is HistoricalOccurrenceOwner.Declaration -> listOf("declaration") + declarationId(owner.id)
                is HistoricalOccurrenceOwner.FileScoped -> listOf("file", owner.context.name)
                is HistoricalOccurrenceOwner.Unavailable -> listOf("unavailable", owner.cause.name)
            }

    fun coverage(value: HistoricalCoverage): List<String> =
        listOf("coverage", value.module.value, value.family.name) +
            when (val status = value.status) {
                HistoricalCoverageStatus.Complete -> listOf("complete")
                HistoricalCoverageStatus.NotCollected -> listOf("not_collected")
                is HistoricalCoverageStatus.OutsideProfile -> listOf("outside_profile", status.reason.name)
                is HistoricalCoverageStatus.Qualified -> listOf("qualified") + status.reasons.sorted().map { it.name }
            }

    fun rows(snapshot: HistoricalSnapshot): Sequence<List<String>> = sequence {
        yield(header(snapshot.header))
        yieldAll(snapshot.modules.asSequence().map(::module))
        yieldAll(snapshot.dependencies.asSequence().map(::dependency))
        yieldAll(snapshot.files.asSequence().map(::file))
        yieldAll(snapshot.declarations.asSequence().map(::declaration))
        yieldAll(snapshot.occurrences.asSequence().map(::occurrence))
        yieldAll(snapshot.coverage.asSequence().map(::coverage))
    }

    internal fun rows(snapshot: HistoricalSnapshotInput): Sequence<List<String>> = sequence {
        yield(header(snapshot.header))
        yieldAll(snapshot.modules.asSequence().map(::module))
        yieldAll(snapshot.dependencies.asSequence().map(::dependency))
        yieldAll(snapshot.files.asSequence().map(::file))
        yieldAll(snapshot.declarations.asSequence().map(::declaration))
        yieldAll(snapshot.occurrences.asSequence().map(::occurrence))
        yieldAll(snapshot.coverage.asSequence().map(::coverage))
    }

    fun digest(snapshot: HistoricalSnapshot): Refinement<HistoricalDigest, HistoricalValueFailure> =
        digest(rows(snapshot))

    fun digest(rows: Sequence<List<String>>): Refinement<HistoricalDigest, HistoricalValueFailure> {
        val digest = MessageDigest.getInstance("SHA-256")
        for (row in rows) when (val updated = update(digest, row)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return updated
        }
        val hex = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return when (val parsed = HistoricalDigest.parse(hex)) {
            is Refinement.Refined -> parsed
            is Refinement.Rejected -> error("SHA-256 produced a noncanonical digest")
        }
    }

    fun update(digest: MessageDigest, row: List<String>): Refinement<Unit, HistoricalValueFailure> {
        val fields =
            when (val parsed = HistoricalCanonicalFields.parse(listOf(row.size.toString()) + row)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
        update(digest, fields)
        return Refinement.Refined(Unit)
    }

    private fun update(digest: MessageDigest, fields: HistoricalCanonicalFields) {
        // Row length prevents moving fields across variable-length rows without changing the digest.
        fields.utf8.forEach { bytes ->
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update('\n'.code.toByte())
        }
    }
}
