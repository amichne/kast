package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.ObserverMarkdown
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Best-effort, observer-only projection of schema-admitted Kast output. */
internal object KastObserverProjector {
    internal fun project(
        operation: KastOperationId,
        output: KastInvocationOutput,
    ): ObserverPresentation = if (!output.success) ObserverPresentation.None else projectDocument(
        operation,
        output.document,
        ObserverWorkingDirectory.from(output.observerDirectory),
    )

    internal fun projectHistorical(
        envelope: JsonObject,
        directory: ObserverWorkingDirectory,
    ): ObserverPresentation {
        val operation = (envelope["document"] as? JsonObject)?.strictString("operation")
            ?.let(KastOperationId::admit) ?: return ObserverPresentation.None
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
        val document = envelope["document"] as? JsonObject
            ?: return ObserverPresentation.None
        if (document.strictString("operation") != operation.value) {
            return ObserverPresentation.None
        }
        val evidence = ObserverEvidence.admit(document) ?: return ObserverPresentation.None
        val markdown = when (operation.value) {
            SYMBOL_DISCOVER -> projectDiscovery(document, evidence, directory)
            SYMBOL_INSPECT -> projectInspection(document, evidence, directory)
            SOURCE_READ -> projectSource(document, evidence, directory)
            RELATION_READ -> projectRelations(document, evidence, directory)
            TRAVERSAL_RUN -> projectTraversal(document, evidence, directory)
            DIAGNOSTIC_CHECK -> projectDiagnostics(document, evidence, directory)
            else -> null
        } ?: return ObserverPresentation.None
        return ObserverPresentation.Markdown(ObserverMarkdown(markdown))
    }

    private fun projectDiscovery(
        document: JsonObject,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val candidates = (document["items"] as? JsonArray)?.map { candidate ->
            admitDiscovery(candidate as? JsonObject ?: return null, observerDirectory) ?: return null
        } ?: return null
        val body = when (candidates.size) {
            0 -> "_No matching symbols._"
            1 -> candidates.single().render()
            else -> buildString {
                appendLine("| Symbol | Kind | File |")
                appendLine("|---|---|---|")
                candidates.forEach { candidate ->
                    append("| ")
                    append(inlineCode(candidate.name).markdownTableCell())
                    append(" | ")
                    append(candidate.kind)
                    append(" | ")
                    append(candidate.file.link().markdownTableCell())
                    appendLine(" |")
                }
            }.trimEnd()
        }
        return observerDocument("symbol", evidence, body)
    }

    private fun admitDiscovery(
        item: JsonObject,
        observerDirectory: ObserverWorkingDirectory,
    ): DiscoveredSymbolObservation? {
        val file = item.strictString("file")
            ?.let { raw -> ObserverFilePath.admit(raw, observerDirectory.path) }
            ?: return null
        return when (item.strictString("type")) {
            "file" -> DiscoveredSymbolObservation(
                name = item.strictLabel("name") ?: return null,
                kind = "file",
                file = file,
            )
            "declaration" -> DiscoveredSymbolObservation(
                name = item.strictLabel("name") ?: return null,
                kind = when (item.strictString("kind")) {
                    "file" -> "file"
                    "class" -> "class"
                    "symbol" -> "symbol"
                    else -> return null
                },
                file = file,
            )
            "text-match" -> DiscoveredSymbolObservation(
                name = item.strictLabel("query") ?: return null,
                kind = "text match",
                file = file,
            )
            else -> null
        }
    }

    private fun projectInspection(
        document: JsonObject,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val symbol = document["symbol"] as? JsonObject ?: return null
        val name = symbol.strictLabel("name") ?: return null
        val kind = when (symbol.strictString("kind")) {
            "classlike" -> "class-like"
            "constructor" -> "constructor"
            "function" -> "function"
            "property" -> "property"
            "type-alias" -> "type-alias"
            else -> return null
        }
        val file = symbol.strictString("file")
            ?.let { raw -> ObserverFilePath.admit(raw, observerDirectory.path) }
            ?: return null
        val qualifiedIdentity = when (val candidate = symbol["qualifiedIdentity"]) {
            null -> return null
            is JsonPrimitive -> candidate.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf(::isSafeLabel)
            else -> return null
        }
        if (symbol["range"] !is JsonObject || symbol["compilerEvidence"] !is JsonObject) return null
        val summary = buildString {
            append(inlineCode(name))
            append(" · ")
            append(kind)
            if (evidence == ObserverEvidence.Complete) append(" · compiler-confirmed")
        }
        val body = buildList {
            add(summary)
            add(file.link())
            qualifiedIdentity?.let { identity -> add(inlineCode(identity)) }
        }.joinToString("\n\n")
        return observerDocument("symbol", evidence, body)
    }

    private fun projectSource(
        document: JsonObject,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val snapshot = document["snapshot"] as? JsonObject ?: return null
        if (document["region"] !is JsonObject || document["entities"] !is JsonArray) return null
        val canonicalRoot = snapshot.strictString("canonicalRoot") ?: return null
        val file = snapshot.strictString("file")
            ?.let { raw -> ObserverFilePath.admitSource(raw, canonicalRoot, observerDirectory.path) }
            ?: return null
        val text = document["text"] as? JsonObject ?: return null
        val source = when (text.strictString("type")) {
            "returned" -> text.strictString("text") ?: return null
            "not-requested", "withheld" -> null
            else -> return null
        }
        val body = buildString {
            append(file.link())
            val lines = when (val candidate = text["lines"]) {
                null -> null
                is JsonObject -> candidate
                else -> return null
            }
            if (lines != null) {
                val range = when (val admitted = SourceLineRangeDocument.parse(
                    lines.strictLong("startInclusive") ?: return null,
                    lines.strictLong("endInclusive") ?: return null,
                )) {
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
            val severity = diagnostic.strictString("severity")
                ?.takeIf { it in setOf("error", "warning", "info") } ?: return null
            val message = diagnostic.strictLabel("message") ?: return null
            val location = diagnostic["location"] as? JsonObject ?: return null
            val file = location.strictString("file")
                ?.let { ObserverFilePath.admit(it, directory.path) } ?: return null
            val range = location["range"] as? JsonObject ?: return null
            val start = range.strictInt("startInclusive")?.takeIf { it >= 0 } ?: return null
            val end = range.strictInt("endExclusive")?.takeIf { it >= start } ?: return null
            "- **$severity** · ${file.link()} · UTF-16 offsets $start–$end: ${inlineCode(message)}"
        }
        return observerDocument("diagnostics", evidence, rows.joinToString("\n"))
    }

    private fun projectRelations(
        document: JsonObject,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val relations = (document["relations"] as? JsonArray)?.map { candidate ->
            admitRelation(candidate as? JsonObject ?: return null, observerDirectory.path)
                ?: return null
        } ?: return null
        val meaning = relations.firstOrNull()?.meaning
        if (relations.any { relation -> relation.meaning != meaning }) return null
        val body = if (relations.isEmpty()) {
            if (evidence == ObserverEvidence.Complete) {
                "_No compiler-confirmed relations._"
            } else {
                "_No known relations._"
            }
        } else {
            buildString {
                append("**")
                append(relations.size)
                append(if (evidence == ObserverEvidence.Complete) " compiler-confirmed " else " known ")
                append(meaning!!.countedLabel(relations.size))
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
            }.trimEnd()
        }
        return observerDocument("semantic query", evidence, body)
    }

    private fun projectTraversal(
        document: JsonObject,
        evidence: ObserverEvidence,
        observerDirectory: ObserverWorkingDirectory,
    ): String? {
        val graph = document["graph"] as? JsonObject ?: return null
        val snapshot = graph["snapshot"] as? JsonObject ?: return null
        val canonicalRoot = snapshot.strictString("canonicalRoot") ?: return null
        val generation = snapshot.strictLong("generation")?.takeIf { it >= 0L } ?: return null
        val proofs = admitProofs(graph["proofs"] as? JsonArray ?: return null) ?: return null
        val nodes = (graph["nodes"] as? JsonArray)?.map { candidate ->
            admitTraversalNode(
                candidate as? JsonObject ?: return null,
                canonicalRoot,
                observerDirectory.path,
                proofs,
            ) ?: return null
        } ?: return null
        if (nodes.map(TraversalSymbolObservation::id) != nodes.indices.toList()) return null
        val nodesById = nodes.associateBy(TraversalSymbolObservation::id)
        if (nodesById.size != nodes.size) return null
        val edges = (graph["edges"] as? JsonArray)?.map { candidate ->
            admitTraversalEdge(candidate as? JsonObject ?: return null, nodesById) ?: return null
        } ?: return null
        if (edges.isEmpty() && (nodes.isNotEmpty() || proofs.isNotEmpty())) return null
        val meaning = edges.firstOrNull()?.meaning
        if (edges.any { edge -> edge.meaning != meaning }) return null
        val affected = linkedMapOf<Int, AffectedSymbolObservation>()
        edges.forEach { edge ->
            val relatedId = edge.meaning.relatedNode(edge.source, edge.target)
            val related = nodesById[relatedId] ?: return null
            val existing = affected[relatedId]
            if (existing == null || edge.depth < existing.depth) {
                affected[relatedId] = AffectedSymbolObservation(edge.depth, related.symbol)
            }
        }
        val orderedAffected = affected.values.sortedWith(
            compareBy(AffectedSymbolObservation::depth)
                .thenBy { observation -> observation.symbol.name }
                .thenBy { observation -> observation.symbol.file.value },
        )
        val maximumDepth = edges.maxOfOrNull(TraversalEdgeObservation::depth) ?: 0
        val body = buildString {
            if (orderedAffected.isEmpty()) {
                appendLine("**No affected symbols**")
                appendLine()
                appendLine(
                    if (evidence == ObserverEvidence.Complete) {
                        "_No compiler-confirmed relationships found._"
                    } else {
                        "_No known relationships found._"
                    },
                )
            } else {
                append("**")
                append(orderedAffected.size)
                append(if (orderedAffected.size == 1) " affected symbol" else " affected symbols")
                append("** · ")
                append(maximumDepth)
                appendLine(if (maximumDepth == 1) " hop" else " hops")
                appendLine()
                appendLine("| Depth | Symbol | Kind | File |")
                appendLine("|---:|---|---|---|")
                orderedAffected.forEach { observation ->
                    append("| ")
                    append(observation.depth)
                    append(" | ")
                    append(inlineCode(observation.symbol.name).markdownTableCell())
                    append(" | ")
                    append(observation.symbol.kind)
                    append(" | ")
                    append(observation.symbol.file.link().markdownTableCell())
                    appendLine(" |")
                }
            }
            appendLine()
            append("_")
            meaning?.let { relationMeaning ->
                append(relationMeaning.displayLabel)
                append(" · ")
            }
            append("generation ")
            append(generation)
            append(" · ")
            append(edges.size)
            val evidenceLabel = if (evidence == ObserverEvidence.Complete) {
                "compiler-confirmed"
            } else {
                "known"
            }
            append(" ")
            append(evidenceLabel)
            append(if (edges.size == 1) " relationship_" else " relationships_")
        }.trimEnd()
        return observerDocument("impact analysis", evidence, body)
    }

    private fun admitRelation(
        relation: JsonObject,
        observerDirectory: Path,
    ): RelationObservation? {
        val meaning = relation.strictString("meaning")?.let(RelationMeaningObservation::admit)
            ?: return null
        val source = admitRelatedSymbol(
            relation["source"] as? JsonObject ?: return null,
            observerDirectory,
        ) ?: return null
        val target = admitRelatedSymbol(
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
        ) return null
        return RelationObservation(meaning, meaning.relatedSymbol(source, target))
    }

    private fun admitRelatedSymbol(
        symbol: JsonObject,
        observerDirectory: Path,
    ): RelatedSymbolObservation? {
        val name = symbol.strictLabel("name") ?: return null
        val kind = symbol.strictString("kind")?.let(::observerSymbolKind) ?: return null
        val file = symbol.strictString("file")
            ?.let { raw -> ObserverFilePath.admit(raw, observerDirectory) }
            ?: return null
        if (
            symbol.strictString("selector") == null ||
            symbol.strictLabel("qualifiedIdentity") == null ||
            symbol["range"] !is JsonObject ||
            symbol["compilerEvidence"] !is JsonObject
        ) return null
        return RelatedSymbolObservation(name, kind, file)
    }

    private fun admitProofs(proofs: JsonArray): Set<Int>? {
        val ids = proofs.map { candidate ->
            val proof = candidate as? JsonObject ?: return null
            if (proof.strictString("identity") == null) return null
            proof.strictInt("id")?.takeIf { it >= 0 } ?: return null
        }
        return ids.takeIf { values ->
            values.distinct().size == values.size && values == values.indices.toList()
        }?.toSet()
    }

    private fun admitTraversalNode(
        node: JsonObject,
        canonicalRoot: String,
        observerDirectory: Path,
        proofs: Set<Int>,
    ): TraversalSymbolObservation? {
        val id = node.strictInt("id")?.takeIf { it >= 0 } ?: return null
        node.strictInt("proof")?.takeIf(proofs::contains) ?: return null
        val name = node.strictLabel("name") ?: return null
        val kind = node.strictString("kind")?.let(::observerSymbolKind) ?: return null
        val file = node.strictString("file")?.let { raw ->
            ObserverFilePath.admitSource(raw, canonicalRoot, observerDirectory)
        } ?: return null
        if (
            node.strictString("selector") == null ||
            node.strictLabel("qualifiedIdentity") == null ||
            node["range"] !is JsonObject
        ) return null
        return TraversalSymbolObservation(id, RelatedSymbolObservation(name, kind, file))
    }

    private fun admitTraversalEdge(
        edge: JsonObject,
        nodes: Map<Int, TraversalSymbolObservation>,
    ): TraversalEdgeObservation? {
        val depth = edge.strictInt("depth")?.takeIf { it > 0 } ?: return null
        val meaning = edge.strictString("meaning")?.let(RelationMeaningObservation::admit)
            ?: return null
        val source = edge.strictInt("source")?.takeIf(nodes::containsKey) ?: return null
        val target = edge.strictInt("target")?.takeIf(nodes::containsKey) ?: return null
        val occurrence = edge["occurrence"] as? JsonObject ?: return null
        if (
            occurrence.strictString("candidateSelector") == null ||
            occurrence.strictString("file") == null ||
            occurrence["range"] !is JsonObject ||
            edge.strictString("provenance") !in RELATION_PROVENANCE ||
            edge.strictString("coverage") != "exact-compiler-confirmed"
        ) return null
        return TraversalEdgeObservation(depth, meaning, source, target)
    }

    private fun observerSymbolKind(value: String): String? = when (value) {
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
        if (evidence == ObserverEvidence.Qualified) {
            append("> Qualified — evidence incomplete\n\n")
        }
        append(body)
    }

    private fun fencedKotlin(source: String): String {
        val longestRun = BACKTICK_RUN.findAll(source).maxOfOrNull { match -> match.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return buildString {
            append(fence)
            append("kotlin\n")
            append(source)
            if (!source.endsWith('\n')) append('\n')
            append(fence)
        }
    }

    private fun inlineCode(value: String): String {
        val longestRun = BACKTICK_RUN.findAll(value).maxOfOrNull { match -> match.value.length } ?: 0
        val fence = "`".repeat(maxOf(1, longestRun + 1))
        val needsPadding = value.startsWith('`') || value.endsWith('`') ||
            value.startsWith(' ') || value.endsWith(' ')
        val padding = if (needsPadding) " " else ""
        return "$fence$padding$value$padding$fence"
    }

    private fun JsonObject.strictString(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.strictInt(name: String): Int? =
        (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private fun JsonObject.strictLong(name: String): Long? =
        (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.strictLabel(name: String): String? =
        strictString(name)?.takeIf(::isSafeLabel)

    private fun isSafeLabel(value: String): Boolean =
        value.isNotBlank() && value.length <= MAXIMUM_LABEL_LENGTH &&
            value.none { character -> character == '\n' || character == '\r' || character == '\u0000' }

    private sealed interface ObserverEvidence {
        data object Complete : ObserverEvidence
        data object Qualified : ObserverEvidence

        companion object {
            fun admit(document: JsonObject): ObserverEvidence? = when (document.strictString("status")) {
                "complete" -> Complete
                "qualified" -> if (document.containsKey("qualification")) Qualified else null
                else -> null
            }
        }
    }

    private data class DiscoveredSymbolObservation(
        val name: String,
        val kind: String,
        val file: ObserverFilePath,
    ) {
        fun render(): String = "${inlineCode(name)} · $kind\n\n${file.link()}"
    }

    private data class RelatedSymbolObservation(
        val name: String,
        val kind: String,
        val file: ObserverFilePath,
    )

    private data class RelationObservation(
        val meaning: RelationMeaningObservation,
        val related: RelatedSymbolObservation,
    )

    private data class TraversalSymbolObservation(
        val id: Int,
        val symbol: RelatedSymbolObservation,
    )

    private data class TraversalEdgeObservation(
        val depth: Int,
        val meaning: RelationMeaningObservation,
        val source: Int,
        val target: Int,
    )

    private data class AffectedSymbolObservation(
        val depth: Int,
        val symbol: RelatedSymbolObservation,
    )

    private enum class RelationMeaningObservation(
        private val singular: String,
        private val plural: String,
        val displayLabel: String,
    ) {
        REFERENCES("reference", "references", "References"),
        CALLERS("caller", "callers", "Callers"),
        CALLEES("callee", "callees", "Callees"),
        IMPLEMENTATIONS("implementation", "implementations", "Implementations"),
        INHERITORS("inheritor", "inheritors", "Inheritors"),
        OVERRIDES("override", "overrides", "Overrides"),
        TYPE_USES("type use", "type uses", "Type uses"),
        ;

        fun relatedSymbol(
            source: RelatedSymbolObservation,
            target: RelatedSymbolObservation,
        ): RelatedSymbolObservation = if (this == CALLEES) target else source

        fun relatedNode(source: Int, target: Int): Int = if (this == CALLEES) target else source

        fun countedLabel(count: Int): String = if (count == 1) singular else plural

        companion object {
            fun admit(value: String): RelationMeaningObservation? = when (value) {
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

    @JvmInline
    private value class ObserverFilePath private constructor(val value: String) {
        fun link(): String =
            "[${value.substringAfterLast('/').markdownLabel()}](<${value.markdownDestination()}>)"

        companion object {
            fun admit(raw: String, observerDirectory: Path): ObserverFilePath? {
                if (
                    raw.isBlank() || raw.length > MAXIMUM_FILE_LENGTH ||
                    raw.any { character -> character == '\n' || character == '\r' || character == '\u0000' }
                ) return null
                return try {
                    val candidate = Path.of(raw).normalize()
                    val relative = when {
                        !candidate.isAbsolute -> candidate
                        candidate.startsWith(observerDirectory) -> observerDirectory.relativize(candidate)
                        else -> return null
                    }
                    admitRelative(relative)
                } catch (_: InvalidPathException) {
                    null
                }
            }

            fun admitSource(
                raw: String,
                canonicalRoot: String,
                observerDirectory: Path,
            ): ObserverFilePath? {
                if (
                    raw.isBlank() || raw.length > MAXIMUM_FILE_LENGTH ||
                    canonicalRoot.isBlank() || canonicalRoot.length > MAXIMUM_FILE_LENGTH ||
                    raw.any(::isForbiddenPathCharacter) ||
                    canonicalRoot.any(::isForbiddenPathCharacter)
                ) return null
                return try {
                    val root = Path.of(canonicalRoot).normalize()
                    if (!root.isAbsolute) return null
                    val candidate = Path.of(raw).normalize()
                    if (!candidate.isAbsolute) return admitRelative(candidate)
                    if (!candidate.startsWith(root) || !candidate.startsWith(observerDirectory)) {
                        return null
                    }
                    admitRelative(observerDirectory.relativize(candidate))
                } catch (_: InvalidPathException) {
                    null
                }
            }

            private fun admitRelative(relative: Path): ObserverFilePath? {
                val value = relative.toString().replace(relative.fileSystem.separator, "/")
                return if (
                    value.isBlank() || value == "." || value == ".." || value.startsWith("../")
                ) null else ObserverFilePath(value)
            }

            private fun isForbiddenPathCharacter(character: Char): Boolean =
                character == '\n' || character == '\r' || character == '\u0000'
        }
    }

    private fun String.markdownLabel(): String = replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")

    private fun String.markdownTableCell(): String = replace("|", "\\|")

    private fun String.markdownDestination(): String = replace("%", "%25")
        .replace("<", "%3C")
        .replace(">", "%3E")
        .replace("|", "%7C")

    private const val SYMBOL_DISCOVER = "symbol.discover"
    private const val SYMBOL_INSPECT = "symbol.inspect"
    private const val SOURCE_READ = "source.read"
    private const val RELATION_READ = "relation.read"
    private const val TRAVERSAL_RUN = "traversal.run"
    private const val DIAGNOSTIC_CHECK = "diagnostic.check"
    private const val MAXIMUM_LABEL_LENGTH = 16_384
    private const val MAXIMUM_FILE_LENGTH = 16_384
    private val BACKTICK_RUN = Regex("`+")
    private val RELATION_PROVENANCE = setOf(
        "k2-authored-source",
        "k2-generated-source",
        "k2-project-library",
    )
}
