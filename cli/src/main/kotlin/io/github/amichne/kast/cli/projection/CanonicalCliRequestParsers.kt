package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliArgument
import io.github.amichne.kast.cli.CliArguments
import io.github.amichne.kast.cli.CliRequestParser
import io.github.amichne.kast.cli.CliRequestParsing
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest

internal val workspaceInspectCliParser = CliRequestParser { arguments ->
    if (arguments.values.isEmpty()) parsed(WorkspaceInspectRequest) else rejected()
}

internal val symbolDiscoverCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--query", "--limit") ?: return@CliRequestParser rejected()
    val query = options.text("--query") ?: return@CliRequestParser rejected()
    val limit = options.count("--limit") ?: return@CliRequestParser rejected()
    parsed(SymbolDiscoverRequest(query, limit))
}

internal val symbolResolveCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--candidate") ?: return@CliRequestParser rejected()
    val candidate = options.text("--candidate") ?: return@CliRequestParser rejected()
    parsed(SymbolResolveRequest(candidate))
}

internal val symbolDescribeCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--selector") ?: return@CliRequestParser rejected()
    val selector = options.text("--selector") ?: return@CliRequestParser rejected()
    parsed(SymbolDescribeRequest(selector))
}

internal val relationReadCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--selector", "--relation", "--limit")
                  ?: return@CliRequestParser rejected()
    val selector = options.text("--selector") ?: return@CliRequestParser rejected()
    val relation = options.relation("--relation") ?: return@CliRequestParser rejected()
    val limit = options.count("--limit") ?: return@CliRequestParser rejected()
    parsed(RelationReadRequest(selector, relation, limit))
}

internal val traversalRunCliParser = CliRequestParser { arguments ->
    val options = arguments.options(
        "--selector",
        "--relation",
        "--maximum-depth",
        "--maximum-results",
    ) ?: return@CliRequestParser rejected()
    val selector = options.text("--selector") ?: return@CliRequestParser rejected()
    val relation = options.relation("--relation") ?: return@CliRequestParser rejected()
    val depth = options.count("--maximum-depth") ?: return@CliRequestParser rejected()
    val results = options.count("--maximum-results") ?: return@CliRequestParser rejected()
    parsed(TraversalRunRequest(selector, relation, depth, results))
}

internal val diagnosticCheckCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--scope", "--limit") ?: return@CliRequestParser rejected()
    val scope = options.text("--scope") ?: return@CliRequestParser rejected()
    val limit = options.count("--limit") ?: return@CliRequestParser rejected()
    parsed(DiagnosticCheckRequest(scope, limit))
}

internal val changePlanCliParser = CliRequestParser { arguments ->
    val options = CliOptionSet.parse(arguments) ?: return@CliRequestParser rejected()
    val intent = when (options.raw("--intent")) {
                     "add-file" -> options.addFileIntent()
                     "add-declaration" -> options.addDeclarationIntent()
                     "replace-declaration" -> options.replaceDeclarationIntent()
                     "rename-symbol" -> options.renameSymbolIntent()
                     else -> null
                 } ?: return@CliRequestParser rejected()
    parsed(ChangePlanRequest(intent))
}

internal val changeApplyCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--plan") ?: return@CliRequestParser rejected()
    val identity = options.text("--plan") ?: return@CliRequestParser rejected()
    parsed(ChangeApplyRequest(identity))
}

internal val changeVerifyCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--application") ?: return@CliRequestParser rejected()
    val identity = options.text("--application") ?: return@CliRequestParser rejected()
    parsed(ChangeVerifyRequest(identity))
}

internal val changeRecoverCliParser = CliRequestParser { arguments ->
    val options = arguments.options("--plan") ?: return@CliRequestParser rejected()
    val identity = options.text("--plan") ?: return@CliRequestParser rejected()
    parsed(ChangeRecoverRequest(identity))
}

private data class CliOption(
    val name: String,
    val value: CliArgument,
)

/** An exact, duplicate-free option set admitted from one operation's bounded arguments. */
private class CliOptionSet private constructor(
    private val values: List<CliOption>,
) {
    fun raw(name: String): String? = values.singleOrNull { it.name == name }?.value?.value

    fun text(name: String): ProtocolText? = raw(name)?.let { raw ->
        when (val parsed = ProtocolText.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> null
        }
    }

    fun count(name: String): ProtocolCount? = raw(name)?.toIntOrNull()?.let { raw ->
        when (val parsed = ProtocolCount.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> null
        }
    }

    fun relation(name: String): RelationKindDocument? = raw(name)
        ?.replace('-', '_')
        ?.uppercase()
        ?.let { raw -> RelationKindDocument.entries.singleOrNull { it.name == raw } }

    fun hasExactly(vararg names: String): Boolean =
        values.map(CliOption::name).toSet() == names.toSet()

    fun addFileIntent(): ChangeIntentDocument.AddFile? {
        if (!hasExactly("--intent", "--path", "--content")) return null
        val path = text("--path") ?: return null
        val content = text("--content") ?: return null
        return ChangeIntentDocument.AddFile(path, content)
    }

    fun addDeclarationIntent(): ChangeIntentDocument.AddDeclaration? {
        if (!hasExactly("--intent", "--target", "--declaration")) return null
        val target = text("--target") ?: return null
        val declaration = text("--declaration") ?: return null
        return ChangeIntentDocument.AddDeclaration(target, declaration)
    }

    fun replaceDeclarationIntent(): ChangeIntentDocument.ReplaceDeclaration? {
        if (!hasExactly("--intent", "--target", "--replacement")) return null
        val target = text("--target") ?: return null
        val replacement = text("--replacement") ?: return null
        return ChangeIntentDocument.ReplaceDeclaration(target, replacement)
    }

    fun renameSymbolIntent(): ChangeIntentDocument.RenameSymbol? {
        if (!hasExactly("--intent", "--target", "--new-name")) return null
        val target = text("--target") ?: return null
        val name = text("--new-name") ?: return null
        return ChangeIntentDocument.RenameSymbol(target, name)
    }

    companion object {
        /**
         * Proof transition: `CliArguments -> CliOptionSet?`.
         *
         * A non-null result establishes paired long-option names, one bounded value per option,
         * and no duplicate option. `null` is confined to this private parser and becomes the
         * closed [CliRequestParsing.Rejected] state before leaving the request boundary.
         */
        fun parse(arguments: CliArguments): CliOptionSet? {
            if (arguments.values.size % 2 != 0) return null
            val options = arguments.values.chunked(2).map { pair ->
                val name = pair[0].value
                if (!name.startsWith("--") || name.length == 2) return null
                CliOption(name, pair[1])
            }
            if (options.map(CliOption::name).distinct().size != options.size) return null
            return CliOptionSet(options)
        }
    }
}

/**
 * Proof transition: `(CliArguments, exact option names) -> CliOptionSet?`.
 *
 * A non-null result proves that the arguments contain exactly the named options once each. The
 * private nullable failure is immediately refined to [CliRequestParsing.Rejected] by its caller.
 */
private fun CliArguments.options(vararg names: String): CliOptionSet? =
    CliOptionSet.parse(this)?.takeIf { it.hasExactly(*names) }

private fun <Request : io.github.amichne.kast.protocol.contract.OperationRequest> parsed(
    request: Request,
): CliRequestParsing<Request> = CliRequestParsing.Parsed(request)

private fun rejected(): CliRequestParsing<Nothing> = CliRequestParsing.Rejected
