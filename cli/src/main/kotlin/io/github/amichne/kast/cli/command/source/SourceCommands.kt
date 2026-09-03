package io.github.amichne.kast.cli.command.source

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliOptionValue
import io.github.amichne.kast.cli.command.CliUsageFailure
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.command.closedChoiceOption
import io.github.amichne.kast.cli.command.defaultOnce
import io.github.amichne.kast.cli.command.optionalOnce
import io.github.amichne.kast.cli.command.protocolTextOption
import io.github.amichne.kast.cli.command.requiredOnce
import io.github.amichne.kast.cli.command.sourceEntityLimitOption
import io.github.amichne.kast.cli.command.sourceLineCountOption
import io.github.amichne.kast.cli.command.sourceTextByteLimitOption
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceLineCountDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument

internal fun sourceCommandGroup(preparers: CanonicalCliRequestPreparers): CommandFamily {
    val read = SourceReadCommand(preparers)
    return CommandFamily(
        KastCommandGroup(
            "source",
            "Read exact structural source context from a reusable Kast selector.",
        ).subcommands(read),
        listOf(read),
    )
}

private enum class SourceTextMode { COMPLETE, NONE, WINDOW }

private class SourceReadCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<SourceReadRequest>(
    name = "read",
    operation = CanonicalOperation.SOURCE_READ,
    schemaUsage = "source read --anchor <selector> [--region <region>] " +
        "[--declaration-kind <kind>]... [--visibility <visibility>]... " +
        "[--include-parameters] [--include-calls] [--include-references] " +
        "[--containment <direct|descendants>] [--text <complete|none|window>] " +
        "[--before-lines <0..1000>] [--after-lines <0..1000>] " +
        "[--entity-limit <1..1000>] [--text-byte-limit <positive-count>] " +
        "[--continuation <continuation>]",
    preparer = preparers.sourceRead,
) {
    private val anchor by protocolTextOption(
        "--anchor",
        "Candidate, exact-symbol, or source selector token.",
    ).requiredOnce()
    private val region by closedChoiceOption(
        "--region",
        "region",
        "Selected structural region. Defaults to anchor.",
        linkedMapOf(
            "anchor" to SourceRegionSelectionDocument.Anchor,
            "callable-body" to SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE),
            "class-body" to SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CLASS),
            "file" to SourceRegionSelectionDocument.File,
            "enclosing-declaration" to SourceRegionSelectionDocument.Enclosing(
                SourceEnclosingRegionKindDocument.DECLARATION,
            ),
            "enclosing-callable-body" to SourceRegionSelectionDocument.Enclosing(
                SourceEnclosingRegionKindDocument.CALLABLE_BODY,
            ),
            "enclosing-class-body" to SourceRegionSelectionDocument.Enclosing(
                SourceEnclosingRegionKindDocument.CLASS_BODY,
            ),
        ),
    ).defaultOnce(SourceRegionSelectionDocument.Anchor, "anchor")
    private val declarationKinds by closedChoiceOption(
        "--declaration-kind",
        "kind",
        "Declaration kind to include; repeat for a union.",
        linkedMapOf(
            "classlike" to SourceDeclarationKindDocument.CLASSLIKE,
            "constructor" to SourceDeclarationKindDocument.CONSTRUCTOR,
            "function" to SourceDeclarationKindDocument.FUNCTION,
            "property" to SourceDeclarationKindDocument.PROPERTY,
            "type-alias" to SourceDeclarationKindDocument.TYPE_ALIAS,
        ),
    ).multiple()
    private val visibilities by closedChoiceOption(
        "--visibility",
        "visibility",
        "Declaration visibility to include; repeat for a union.",
        linkedMapOf(
            "public" to SourceDeclarationVisibilityDocument.PUBLIC,
            "protected" to SourceDeclarationVisibilityDocument.PROTECTED,
            "internal" to SourceDeclarationVisibilityDocument.INTERNAL,
            "private" to SourceDeclarationVisibilityDocument.PRIVATE,
            "local" to SourceDeclarationVisibilityDocument.LOCAL,
        ),
    ).multiple()
    private val includeParameters by option(
        "--include-parameters",
        help = "Include value-parameter entities.",
    ).flag(default = false)
    private val includeCalls by option(
        "--include-calls",
        help = "Include call and callee entities.",
    ).flag(default = false)
    private val includeReferences by option(
        "--include-references",
        help = "Include reference entities.",
    ).flag(default = false)
    private val containment by closedChoiceOption(
        "--containment",
        "containment",
        "Direct children or all descendants. Defaults to direct when entities are requested.",
        linkedMapOf(
            "direct" to SourceContainmentDocument.DIRECT,
            "descendants" to SourceContainmentDocument.DESCENDANTS,
        ),
    ).optionalOnce()
    private val text by closedChoiceOption(
        "--text",
        "projection",
        "Text projection. Defaults to complete.",
        linkedMapOf(
            "complete" to SourceTextMode.COMPLETE,
            "none" to SourceTextMode.NONE,
            "window" to SourceTextMode.WINDOW,
        ),
    ).defaultOnce(SourceTextMode.COMPLETE, "complete")
    private val beforeLines by sourceLineCountOption(
        "--before-lines",
        "Whole lines before the anchor in a window.",
    ).optionalOnce()
    private val afterLines by sourceLineCountOption(
        "--after-lines",
        "Whole lines after the anchor in a window.",
    ).optionalOnce()
    private val entityLimit by sourceEntityLimitOption(
        "--entity-limit",
        "Maximum returned entities.",
    ).defaultOnce(defaultEntityLimit, "250")
    private val textByteLimit by sourceTextByteLimitOption(
        "--text-byte-limit",
        "Maximum UTF-8 bytes for returned text.",
    ).defaultOnce(defaultTextByteLimit, "65536")
    private val continuation by protocolTextOption(
        "--continuation",
        "Snapshot-bound continuation returned by an earlier source read.",
    ).optionalOnce()

    override fun help(context: Context): String =
        "Read one exact bounded source region, typed entity inventory, and text projection."

    override fun resolveAction(): CliActionResolution = when (
        val refined = SourceReadCliInput.refine(
            anchor,
            region,
            declarationKinds,
            visibilities,
            includeParameters,
            includeCalls,
            includeReferences,
            containment,
            text,
            beforeLines,
            afterLines,
            entityLimit,
            textByteLimit,
            continuation,
        )
    ) {
        is Refinement.Refined -> prepare(refined.value)
        is Refinement.Rejected -> CliActionResolution.UsageRejected(refined.failure)
    }
}

private object SourceReadCliInput {
    @Suppress("LongParameterList")
    fun refine(
        anchor: ProtocolText,
        region: SourceRegionSelectionDocument,
        declarationKinds: List<SourceDeclarationKindDocument>,
        visibilities: List<SourceDeclarationVisibilityDocument>,
        includeParameters: Boolean,
        includeCalls: Boolean,
        includeReferences: Boolean,
        containment: CliOptionValue<SourceContainmentDocument>,
        text: SourceTextMode,
        beforeLines: CliOptionValue<SourceLineCountDocument>,
        afterLines: CliOptionValue<SourceLineCountDocument>,
        entityLimit: SourceEntityLimitDocument,
        textByteLimit: SourceTextByteLimitDocument,
        continuation: CliOptionValue<ProtocolText>,
    ): Refinement<SourceReadRequest, CliUsageFailure.SourceRead> {
        val admittedAnchor = when (val admitted = SourceReadAnchorDocument.admit(anchor)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(CliUsageFailure.SourceRead.ANCHOR_REJECTED)
        }
        if (
            declarationKinds.size != declarationKinds.distinct().size ||
            visibilities.size != visibilities.distinct().size
        ) {
            return rejected(CliUsageFailure.SourceRead.DUPLICATE_SELECTION)
        }
        if (visibilities.isNotEmpty() && declarationKinds.isEmpty()) {
            return rejected(CliUsageFailure.SourceRead.VISIBILITY_REQUIRES_DECLARATIONS)
        }
        val filters = buildList {
            if (declarationKinds.isNotEmpty()) {
                add(
                    SourceEntityFilterDocument.Declarations(
                        declarationKinds.sortedBy { it.ordinal },
                        if (visibilities.isEmpty()) {
                            SourceVisibilitySelectionDocument.Any
                        } else {
                            SourceVisibilitySelectionDocument.Exact(
                                visibilities.sortedBy { it.ordinal },
                            )
                        },
                    ),
                )
            }
            if (includeParameters) add(SourceEntityFilterDocument.Parameters)
            if (includeCalls) add(SourceEntityFilterDocument.Calls)
            if (includeReferences) add(SourceEntityFilterDocument.References)
        }
        val entities = if (filters.isEmpty()) {
            if (containment is CliOptionValue.Present) {
                return rejected(CliUsageFailure.SourceRead.CONTAINMENT_REQUIRES_ENTITIES)
            }
            SourceEntitySelectionDocument.None
        } else {
            SourceEntitySelectionDocument.Matching(
                when (containment) {
                    CliOptionValue.Absent -> SourceContainmentDocument.DIRECT
                    is CliOptionValue.Present -> containment.value
                },
                filters,
            )
        }
        val textProjection = when (text) {
            SourceTextMode.COMPLETE,
            SourceTextMode.NONE,
            -> {
                if (beforeLines is CliOptionValue.Present || afterLines is CliOptionValue.Present) {
                    return rejected(
                        CliUsageFailure.SourceRead.WINDOW_LINES_REQUIRE_WINDOW_TEXT,
                    )
                }
                if (text == SourceTextMode.COMPLETE) {
                    SourceTextRequestDocument.Complete
                } else {
                    SourceTextRequestDocument.None
                }
            }
            SourceTextMode.WINDOW -> SourceTextRequestDocument.Window(
                beforeLines.valueOr(defaultLineCount),
                afterLines.valueOr(defaultLineCount),
            )
        }
        return Refinement.Refined(
            SourceReadRequest(
                admittedAnchor,
                region,
                entities,
                textProjection,
                entityLimit,
                textByteLimit,
                when (continuation) {
                    CliOptionValue.Absent -> SourceReadPageDocument.First
                    is CliOptionValue.Present -> SourceReadPageDocument.Continue(continuation.value)
                },
            ),
        )
    }

    private fun <Value> CliOptionValue<Value>.valueOr(default: Value): Value = when (this) {
        CliOptionValue.Absent -> default
        is CliOptionValue.Present -> value
    }

    private fun rejected(failure: CliUsageFailure.SourceRead) =
        Refinement.Rejected(failure)
}

private val defaultLineCount = SourceLineCountDocument.parse(0).staticRefined()
private val defaultEntityLimit = SourceEntityLimitDocument.parse(250).staticRefined()
private val defaultTextByteLimit = SourceTextByteLimitDocument.parse(65_536).staticRefined()

private fun <Value, Failure> Refinement<Value, Failure>.staticRefined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Invalid static source-read CLI default: $failure")
}
