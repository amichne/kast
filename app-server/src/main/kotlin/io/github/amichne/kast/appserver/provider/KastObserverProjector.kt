package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.ObserverFileChange
import io.github.amichne.kast.appserver.core.ObserverFileChangeKind
import io.github.amichne.kast.appserver.core.ObserverFileChangeSet
import io.github.amichne.kast.appserver.core.ObserverMarkdown
import io.github.amichne.kast.appserver.core.ObserverPresentation
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Best-effort, observer-only projection of schema-admitted Kast output. */
internal object KastObserverProjector {
    internal fun project(
        operation: KastOperationId,
        output: KastInvocationOutput,
    ): ObserverPresentation =
        if (!output.success) ObserverPresentation.None
        else
            projectDocument(
                operation,
                output.document,
                ObserverWorkingDirectory.from(output.observerDirectory),
            )

    internal fun projectHistorical(
        envelope: JsonObject,
        directory: ObserverWorkingDirectory,
    ): ObserverPresentation {
        val operation =
            (envelope["document"] as? JsonObject)?.strictString("operation")?.let(KastOperationId::admit)
                ?: return ObserverPresentation.None
        return projectDocument(operation, envelope, directory)
    }

    private fun projectDocument(
        operation: KastOperationId,
        envelope: JsonObject,
        directory: ObserverWorkingDirectory,
    ): ObserverPresentation {
        if (envelope.strictString("status") != "completed") {
            return ObserverPresentation.None
        }
        val document = envelope["document"] as? JsonObject ?: return ObserverPresentation.None
        if (document.strictString("operation") != operation.value) {
            return ObserverPresentation.None
        }
        val evidence = ObserverEvidence.admit(document) ?: return ObserverPresentation.None
        if (operation.value == CHANGE_APPLY) return projectAppliedChange(document)
        val markdown =
            when (operation.value) {
                "query.run" -> projectQuery(document, evidence, directory)
                SOURCE_READ -> projectSource(document, evidence, directory)
                DIAGNOSTIC_CHECK -> projectDiagnostics(document, evidence, directory)
                CHANGE_PLAN -> projectPlannedChange(document, evidence)
                CHANGE_RECOVER -> projectRecovery(document, evidence)
                else -> null
            } ?: return ObserverPresentation.None
        return ObserverPresentation.Markdown(ObserverMarkdown(markdown))
    }

    private fun projectAppliedChange(document: JsonObject): ObserverPresentation {
        val files = admitFileChanges(document) ?: return ObserverPresentation.None
        return ObserverPresentation.FileChanges(files)
    }

    private fun projectPlannedChange(
        document: JsonObject,
        evidence: ObserverEvidence,
    ): String? {
        val planIdentity = document.strictString("planIdentity") ?: return null
        val files = admitFileChanges(document) ?: return null
        val body = buildString {
            append("Plan identity: ")
            appendLine(inlineCode(planIdentity))
            appendLine()
            append("**")
            append(files.entries.size)
            append(if (files.entries.size == 1) " file planned" else " files planned")
            appendLine("**")
            files.entries.forEach { change ->
                appendLine()
                append(inlineCode(change.kind.name.lowercase()))
                append(" · [")
                append(change.path.value.substringAfterLast('/').markdownLabel())
                append("](<")
                append(change.path.value.markdownDestination())
                appendLine(">)")
                appendLine()
                append(fenced("diff", change.diff.value))
                appendLine()
            }
        }
            .trimEnd()
        return observerDocument("change plan", evidence, body)
    }

    private fun projectRecovery(
        document: JsonObject,
        evidence: ObserverEvidence,
    ): String? {
        val outcome =
            when (document.strictString("state")) {
                "prior-state" -> "Prior state retained."
                "rolled-back" -> "Change rolled back."
                "recovery-required" -> "Manual recovery required."
                else -> return null
            }
        return observerDocument("recovery", evidence, outcome)
    }

    private fun admitFileChanges(document: JsonObject): ObserverFileChangeSet? {
        val candidates = document["changes"] as? JsonArray ?: return null
        val files = candidates.map { candidate ->
            val change = candidate as? JsonObject ?: return null
            val kind =
                when (change.strictString("kind")) {
                    "add" -> ObserverFileChangeKind.ADD
                    "delete" -> ObserverFileChangeKind.DELETE
                    "update" -> ObserverFileChangeKind.UPDATE
                    else -> return null
                }
            when (
                val admitted =
                    ObserverFileChange.admit(
                        change.strictString("path") ?: return null,
                        kind,
                        change.strictString("diff") ?: return null,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return null
            }
        }
        return when (val admitted = ObserverFileChangeSet.admit(files)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> null
        }
    }

    private fun projectQuery(
        document: JsonObject,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val items = document["items"] as? JsonArray ?: return null
        val failures = document["failures"] as? JsonArray ?: return null
        val omissions = document["omissions"] as? JsonArray ?: return null
        val walkObservations = document["walk_observations"] as? JsonArray ?: return null
        if (items.isNotEmpty() && items.all { (it as? JsonObject)?.strictString("type") == "traversal_record" }) {
            return projectQueryWalk(items, walkObservations, failures, omissions, evidence, observerDirectory)
        }
        if (items.isEmpty() && walkObservations.isNotEmpty()) {
            return projectQueryWalk(items, walkObservations, failures, omissions, evidence, observerDirectory)
        }
        if (items.isNotEmpty() && items.all { (it as? JsonObject)?.strictString("type") == "occurrence" }) {
            return projectQueryOccurrences(items, failures, omissions, evidence, observerDirectory)
        }
        return observerDocument(
            "query",
            evidence,
            querySummary(items.size, failures.size, omissions.size),
        )
    }

    private fun querySummary(items: Int, failures: Int, omissions: Int): String =
        (listOf("**${countedQueryLabel(items, "query result")}**") +
                listOfNotNull(
                    failures.takeIf { it > 0 }?.let { countedQueryLabel(it, "item failure") },
                    omissions.takeIf { it > 0 }?.let { countedQueryLabel(it, "relation omission") },
                ))
            .joinToString(" · ")

    private fun countedQueryLabel(count: Int, singular: String): String =
        "$count $singular${if (count == 1) "" else "s"}"

    private fun projectSource(
        document: JsonObject,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val parts = sourcePresentationParts(document) ?: return null
        val snapshot = parts.structure["snapshot"] as? JsonObject ?: return null
        val canonicalRoot = snapshot.strictString("canonicalRoot") ?: return null
        val file =
            snapshot.strictString("file")?.let { raw ->
                ObserverFilePath.admitSource(raw, canonicalRoot, observerDirectory.path)
            } ?: return null
        val text = parts.text
        val source =
            when (text.strictString("type")) {
                "returned" -> text.strictString("text") ?: return null
                "not-requested",
                "withheld" -> null
                else -> return null
            }
        val body = buildString {
            append(file.link())
            val lines =
                when (val candidate = text["lines"]) {
                    null -> null
                    is JsonObject -> candidate
                    else -> return null
                }
            if (lines != null) {
                val range =
                    when (
                        val admitted =
                            SourceLineRangeDocument.parse(
                                lines.strictLong("startInclusive") ?: return null,
                                lines.strictLong("endInclusive") ?: return null,
                            )
                    ) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return null
                    }
                append(" · lines ${range.startInclusive.value}–${range.endInclusive.value}")
            }
            source?.let { returned ->
                append("\n\n")
                append(fencedKotlin(returned))
            }
        }
        return observerDocument("source", evidence, body)
    }

    private fun projectDiagnostics(
        document: JsonObject,
        evidence: ObserverEvidence,
        directory: ObserverWorkingDirectory,
    ): String? {
        val diagnostics = document["diagnostics"] as? JsonArray ?: return null
        if (diagnostics.isEmpty()) return observerDocument("diagnostics", evidence, "_No diagnostics._")
        val rows = diagnostics.map { entry ->
            val diagnostic = entry as? JsonObject ?: return null
            val severity =
                diagnostic.strictString("severity")?.takeIf { it in setOf("error", "warning", "info") } ?: return null
            val message = diagnostic.strictLabel("message") ?: return null
            val location = diagnostic["location"] as? JsonObject ?: return null
            val file = location.strictString("file")?.let { ObserverFilePath.admit(it, directory.path) } ?: return null
            val range = location["range"] as? JsonObject ?: return null
            val start = range.strictInt("startInclusive")?.takeIf { it >= 0 } ?: return null
            val end = range.strictInt("endExclusive")?.takeIf { it >= start } ?: return null
            "- **$severity** · ${file.link()} · UTF-16 offsets $start–$end: ${inlineCode(message)}"
        }
        return observerDocument("diagnostics", evidence, rows.joinToString("\n"))
    }

    private fun projectQueryOccurrences(
        items: JsonArray,
        failures: JsonArray,
        omissions: JsonArray,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val relations = admitQueryRelations(items, observerDirectory.path) ?: return null
        val meaning = relations.firstOrNull()?.meaning
        if (relations.any { relation -> relation.meaning != meaning }) return null
        return observerDocument(
            "query",
            evidence,
            renderQueryRelations(relations, evidence.coverage, failures.size, omissions.size),
        )
    }

    private fun admitQueryRelations(items: JsonArray, observerDirectory: Path): List<RelationObservation>? =
        items.map { candidate ->
            val item = candidate as? JsonObject ?: return null
            if (item.strictString("ref") == null) return null
            admitRelation(item["relation"] as? JsonObject ?: return null, observerDirectory) ?: return null
        }

    private fun renderQueryRelations(
        relations: List<RelationObservation>,
        coverage: ObserverCoverage,
        failures: Int,
        omissions: Int,
    ): String {
        if (relations.isEmpty()) {
            return if (coverage == ObserverCoverage.COMPLETE) {
                "_No compiler-confirmed relations._"
            } else {
                "_No known relations._"
            }
        }
        return buildString {
            append("**")
            append(relations.size)
            append(if (coverage == ObserverCoverage.COMPLETE) " compiler-confirmed " else " known ")
            append(relations.first().meaning.countedLabel(relations.size))
            appendLine("**")
            appendLine()
            appendLine("| Symbol | Kind | File |")
            appendLine("|---|---|---|")
            relations.forEach { relation ->
                append("| ")
                append(inlineCode(relation.related.name).markdownTableCell())
                append(" | ")
                append(relation.related.kind)
                append(" | ")
                append(relation.related.file.link().markdownTableCell())
                appendLine(" |")
            }
            if (failures > 0) append("\n$failures item failures")
            if (omissions > 0) append("\n$omissions relation omissions")
        }
            .trimEnd()
    }

    private fun projectQueryWalk(
        items: JsonArray,
        walkObservations: JsonArray,
        failures: JsonArray,
        omissions: JsonArray,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val records = admitQueryWalkRecords(items, observerDirectory.path) ?: return null
        if (walkObservations.any { !admittedWalkObservation(it, evidence.coverage) }) return null
        return observerDocument(
            "query walk",
            evidence,
            renderQueryWalk(records, failures.size, omissions.size, walkObservations.size, evidence.coverage),
        )
    }

    private fun admitQueryWalkRecords(items: JsonArray, directory: Path): List<QueryWalkRecordObservation>? =
        items.map { candidate ->
            val item = candidate as? JsonObject ?: return null
            if (item.strictString("type") != "traversal_record" || item.strictString("ref") == null) return null
            val record = item["record"] as? JsonObject ?: return null
            val depth = record.strictInt("depth")?.takeIf { it > 0 } ?: return null
            val relation = admitRelation(record["relation"] as? JsonObject ?: return null, directory) ?: return null
            QueryWalkRecordObservation(depth, relation)
        }

    private fun admittedWalkObservation(
        candidate: kotlinx.serialization.json.JsonElement,
        status: ObserverCoverage,
    ): Boolean {
        val observation = candidate as? JsonObject ?: return false
        if (observation.strictString("subject") == null) return false
        if (observation.strictString("relation")?.let(RelationMeaningObservation::admit) == null) return false
        if (observation.strictInt("maximum_depth")?.takeIf { it > 0 } == null) return false
        if (observation.strictInt("expanded_frontier")?.takeIf { it >= 0 } == null) return false
        if (observation["progress"] !is JsonObject) return false
        if (observation["strategy"] !is JsonObject) return false
        if (observation["partial_expansions"] !is JsonArray) return false
        val coverage = (observation["coverage"] as? JsonObject)?.strictString("kind") ?: return false
        return coverage in setOf("complete", "resumable", "terminal_incomplete") &&
            (status != ObserverCoverage.COMPLETE || coverage == "complete")
    }

    private fun renderQueryWalk(
        records: List<QueryWalkRecordObservation>,
        failureCount: Int,
        omissionCount: Int,
        observationCount: Int,
        coverage: ObserverCoverage,
    ): String = buildString {
        append(if (records.isEmpty()) emptyQueryWalkMessage(coverage) else renderQueryWalkRecords(records, coverage))
        if (failureCount > 0) append("\n$failureCount item failures")
        if (omissionCount > 0) append("\n$omissionCount relation omissions")
        if (observationCount > 0) append("\n${countedQueryLabel(observationCount, "walk observation")}")
    }
        .trimEnd()

    private fun emptyQueryWalkMessage(coverage: ObserverCoverage): String =
        if (coverage == ObserverCoverage.COMPLETE) "_No compiler-confirmed relationships found._"
        else "_No known relationships found._"

    private fun renderQueryWalkRecords(records: List<QueryWalkRecordObservation>, coverage: ObserverCoverage): String =
        buildString {
            val meanings = records.map { it.relation.meaning }.distinct()
            append("**")
            append(records.size)
            append(if (coverage == ObserverCoverage.COMPLETE) " compiler-confirmed " else " known ")
            append(if (meanings.size == 1) meanings.single().countedLabel(records.size) else "relationships")
            append("** · ")
            val maximumDepth = records.maxOf(QueryWalkRecordObservation::depth)
            append(maximumDepth)
            appendLine(if (maximumDepth == 1) " hop" else " hops")
            appendLine()
            appendLine("| Depth | Symbol | Kind | File |")
            appendLine("|---:|---|---|---|")
            records.forEach { record ->
                append("| ")
                append(record.depth)
                append(" | ")
                append(inlineCode(record.relation.related.name).markdownTableCell())
                append(" | ")
                append(record.relation.related.kind)
                append(" | ")
                append(record.relation.related.file.link().markdownTableCell())
                appendLine(" |")
            }
        }

    private fun admitRelation(
        relation: JsonObject,
        observerDirectory: Path,
    ): RelationObservation? {
        val meaning = relation.strictString("meaning")?.let(RelationMeaningObservation::admit) ?: return null
        val source =
            admitRelatedSymbol(
                relation["source"] as? JsonObject ?: return null,
                observerDirectory,
            ) ?: return null
        val target =
            admitRelatedSymbol(
                relation["target"] as? JsonObject ?: return null,
                observerDirectory,
            ) ?: return null
        val occurrence = relation["occurrence"] as? JsonObject ?: return null
        if (
            occurrence.strictString("candidateSelector") == null ||
                occurrence.strictString("file") == null ||
                occurrence["range"] !is JsonObject ||
                relation.strictString("provenance") !in RELATION_PROVENANCE ||
                relation.strictString("coverage") != "exact-compiler-confirmed"
        )
            return null
        return RelationObservation(meaning, meaning.relatedSymbol(source, target))
    }

    private fun admitRelatedSymbol(
        symbol: JsonObject,
        observerDirectory: Path,
    ): RelatedSymbolObservation? {
        val name = symbol.strictLabel("name") ?: return null
        val kind = symbol.strictString("kind")?.let(::observerSymbolKind) ?: return null
        val file =
            symbol.strictString("file")?.let { raw -> ObserverFilePath.admit(raw, observerDirectory) } ?: return null
        if (
            symbol.strictString("selector") == null ||
                symbol.strictLabel("qualifiedIdentity") == null ||
                symbol["range"] !is JsonObject ||
                symbol["compilerEvidence"] !is JsonObject
        )
            return null
        return RelatedSymbolObservation(name, kind, file)
    }

    private fun observerSymbolKind(value: String): String? =
        when (value) {
            "classlike" -> "class-like"
            "constructor" -> "constructor"
            "function" -> "function"
            "property" -> "property"
            "type-alias" -> "type-alias"
            else -> null
        }

    private fun observerDocument(
        subject: String,
        evidence: ObserverEvidence,
        body: String,
    ): String = buildString {
        append("**Kast · ")
        append(subject)
        append("**\n\n")
        if (evidence.coverage == ObserverCoverage.QUALIFIED) {
            append("> Qualified — evidence incomplete\n\n")
        }
        when (val basis = evidence.basis) {
            ObserverBasis.Published -> Unit
            is ObserverBasis.Live ->
                append("> Live IDE evidence · saved, committed content · epoch ${basis.evidence.epoch}\n\n")
        }
        append(body)
    }

    private fun fencedKotlin(source: String): String = fenced("kotlin", source)

    private fun fenced(language: String, source: String): String {
        val longestRun = BACKTICK_RUN.findAll(source).maxOfOrNull { match -> match.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return buildString {
            append(fence)
            append(language)
            append('\n')
            append(source)
            if (!source.endsWith('\n')) append('\n')
            append(fence)
        }
    }

    private fun inlineCode(value: String): String {
        val longestRun = BACKTICK_RUN.findAll(value).maxOfOrNull { match -> match.value.length } ?: 0
        val fence = "`".repeat(maxOf(1, longestRun + 1))
        val needsPadding = value.startsWith('`') || value.endsWith('`') || value.startsWith(' ') || value.endsWith(' ')
        val padding = if (needsPadding) " " else ""
        return "$fence$padding$value$padding$fence"
    }

    private fun JsonObject.strictString(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.strictInt(name: String): Int? =
        (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private fun JsonObject.strictLong(name: String): Long? =
        (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.strictLabel(name: String): String? = strictString(name)?.takeIf(::isSafeLabel)

    private fun isSafeLabel(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAXIMUM_LABEL_LENGTH &&
            value.none { character -> character == '\n' || character == '\r' || character == '\u0000' }

    private enum class ObserverCoverage {
        COMPLETE,
        QUALIFIED,
    }

    private sealed interface ObserverBasis {
        data object Published : ObserverBasis

        data class Live(val evidence: LiveReadEvidence) : ObserverBasis
    }

    private data class ObserverEvidence(val coverage: ObserverCoverage, val basis: ObserverBasis) {
        companion object {
            fun admit(document: JsonObject): ObserverEvidence? {
                val coverage =
                    when (document.strictString("status")) {
                        "complete" -> ObserverCoverage.COMPLETE
                        "qualified" ->
                            if (document.containsKey("qualification")) ObserverCoverage.QUALIFIED else return null
                        else -> return null
                    }
                val snapshot = document["snapshot"] as? JsonObject
                if (!document.containsKey("live")) {
                    if (snapshot?.containsKey("live") == true) return null
                    return ObserverEvidence(coverage, ObserverBasis.Published)
                }
                if (
                    document.strictString("operation") !in
                        setOf(
                            "query.run",
                            SOURCE_READ,
                            DIAGNOSTIC_CHECK,
                        )
                )
                    return null
                val raw = document["live"] as? JsonObject ?: return null
                if (raw.keys != setOf("root", "host", "epoch", "contentView", "version")) return null
                val root = raw.strictString("root") ?: return null
                val hostText = raw.strictString("host") ?: return null
                val host =
                    try {
                        java.util.UUID.fromString(hostText)
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                if (host.toString() != hostText) return null
                val contentView =
                    LiveReadContentView.entries.singleOrNull { it.name == raw.strictString("contentView") }
                        ?: return null
                val live =
                    when (
                        val admitted =
                            LiveReadEvidence.create(
                                root,
                                host,
                                raw.strictLong("epoch") ?: return null,
                                contentView,
                                raw.strictInt("version") ?: return null,
                            )
                    ) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return null
                    }
                if (
                    document.containsKey("generation") ||
                        snapshot?.containsKey("generation") == true ||
                        snapshot?.containsKey("sourceState") == true
                )
                    return null
                if (snapshot != null && (snapshot["live"] != raw || snapshot.strictString("canonicalRoot") != root))
                    return null
                return ObserverEvidence(coverage, ObserverBasis.Live(live))
            }
        }
    }

    private data class RelatedSymbolObservation(
        val name: String,
        val kind: String,
        val file: ObserverFilePath,
    )

    private data class QueryWalkRecordObservation(val depth: Int, val relation: RelationObservation)

    private data class RelationObservation(
        val meaning: RelationMeaningObservation,
        val related: RelatedSymbolObservation,
    )

    private enum class RelationMeaningObservation(
        private val singular: String,
        private val plural: String,
    ) {
        REFERENCES("reference", "references"),
        CALLERS("caller", "callers"),
        CALLEES("callee", "callees"),
        IMPLEMENTATIONS("implementation", "implementations"),
        INHERITORS("inheritor", "inheritors"),
        OVERRIDES("override", "overrides"),
        TYPE_USES("type use", "type uses");

        fun relatedSymbol(
            source: RelatedSymbolObservation,
            target: RelatedSymbolObservation,
        ): RelatedSymbolObservation = if (this == CALLEES) target else source

        fun countedLabel(count: Int): String = if (count == 1) singular else plural

        companion object {
            fun admit(value: String): RelationMeaningObservation? =
                when (value) {
                    "references" -> REFERENCES
                    "callers" -> CALLERS
                    "callees" -> CALLEES
                    "implementations" -> IMPLEMENTATIONS
                    "inheritors" -> INHERITORS
                    "overrides" -> OVERRIDES
                    "type-uses" -> TYPE_USES
                    else -> null
                }
        }
    }

    private const val CHANGE_PLAN = "change.plan"
    private const val CHANGE_APPLY = "change.apply"
    private const val CHANGE_RECOVER = "change.recover"
    private const val SOURCE_READ = "source.read"
    private const val DIAGNOSTIC_CHECK = "diagnostic.check"
    private const val MAXIMUM_LABEL_LENGTH = 16_384
    private val BACKTICK_RUN = Regex("`+")
    private val RELATION_PROVENANCE =
        setOf(
            "k2-authored-source",
            "k2-generated-source",
            "k2-project-library",
        )
}
