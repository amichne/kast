package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SourceDeclarationSemanticIdentityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityTargetDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object CanonicalSourceReadCliDocuments {
    fun project(
        outcome: OperationOutcome<SourceReadResult, SourceReadQualification, SourceReadRejection>,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            completeFactory.create(
                SourceReadCompleteCliDocument(
                    operation = CanonicalOperation.SOURCE_READ.id.value,
                    status = "complete",
                    snapshot = result.snapshot.toCliDocument(),
                    region = result.region.toCliDocument(),
                    entities = result.entities.values.map(SourceEntityDocument::toCliDocument),
                    text = result.text.toCliDocument(),
                ),
            )
        },
        qualified = { result, qualification ->
            qualifiedFactory.create(
                SourceReadQualifiedCliDocument(
                    operation = CanonicalOperation.SOURCE_READ.id.value,
                    status = "qualified",
                    snapshot = result.snapshot.toCliDocument(),
                    region = result.region.toCliDocument(),
                    entities = result.entities.values.map(SourceEntityDocument::toCliDocument),
                    text = result.text.toCliDocument(),
                    qualification = qualification.toCliDocument(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.SOURCE_READ, rejection.cliName())
        },
    )
}

@Serializable
private data class SourceReadCompleteCliDocument(
    val operation: String,
    val status: String,
    val snapshot: SourceSnapshotCliDocument,
    val region: SourceRegionCliDocument,
    val entities: List<SourceEntityCliDocument>,
    val text: SourceTextProjectionCliDocument,
)

@Serializable
private data class SourceReadQualifiedCliDocument(
    val operation: String,
    val status: String,
    val snapshot: SourceSnapshotCliDocument,
    val region: SourceRegionCliDocument,
    val entities: List<SourceEntityCliDocument>,
    val text: SourceTextProjectionCliDocument,
    val qualification: SourceReadQualificationCliDocument,
)

@Serializable
private data class SourceSnapshotCliDocument(
    val canonicalRoot: String,
    val generation: Long,
    val sourceState: String,
    val file: String,
    val textIdentity: String,
    val coordinateUnit: String,
    val length: Int,
)

@Serializable
private data class SourceRegionCliDocument(
    val kind: String,
    val selection: SourceSelectionCliDocument,
)

@Serializable
private data class SourceSelectionCliDocument(
    val selector: String,
    val range: SourceSelectionRangeCliDocument,
)

@Serializable
private data class SourceSelectionRangeCliDocument(
    val startInclusive: Int,
    val endExclusive: Int,
)

@Serializable
private sealed interface SourceDeclarationSemanticIdentityCliDocument {
    @Serializable
    @SerialName("candidate")
    data class Candidate(val selector: String) : SourceDeclarationSemanticIdentityCliDocument
}

@Serializable
private sealed interface SourceEntityTargetCliDocument {
    @Serializable
    @SerialName("candidate")
    data class Candidate(val selector: String) : SourceEntityTargetCliDocument

    @Serializable
    @SerialName("local")
    data class Local(val selector: String) : SourceEntityTargetCliDocument

    @Serializable
    @SerialName("unresolved")
    data class Unresolved(val reason: String) : SourceEntityTargetCliDocument
}

@Serializable
private sealed interface SourceEntityCliDocument {
    @Serializable
    @SerialName("declaration")
    data class Declaration(
        val kind: String,
        val name: String,
        val visibility: String,
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionCliDocument,
        val semanticIdentity: SourceDeclarationSemanticIdentityCliDocument,
    ) : SourceEntityCliDocument

    @Serializable
    @SerialName("value-parameter")
    data class ValueParameter(
        val name: String,
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionCliDocument,
    ) : SourceEntityCliDocument

    @Serializable
    @SerialName("call")
    data class Call(
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionCliDocument,
        val callee: SourceSelectionCliDocument,
        val target: SourceEntityTargetCliDocument,
    ) : SourceEntityCliDocument

    @Serializable
    @SerialName("reference")
    data class Reference(
        val name: String,
        val nestingDepth: Int,
        val parentSelector: String,
        val selection: SourceSelectionCliDocument,
        val target: SourceEntityTargetCliDocument,
    ) : SourceEntityCliDocument
}

@Serializable
private sealed interface SourceTextProjectionCliDocument {
    @Serializable
    @SerialName("not-requested")
    data object NotRequested : SourceTextProjectionCliDocument

    @Serializable
    @SerialName("returned")
    data class Returned(
        val selection: SourceSelectionCliDocument,
        val text: String,
        val lines: SourceLineRangeCliDocument,
    ) : SourceTextProjectionCliDocument

    @Serializable
    @SerialName("withheld")
    data class Withheld(val reason: String) : SourceTextProjectionCliDocument
}

@Serializable
private data class SourceReadQualificationCliDocument(
    val knownMinimumEntityCount: Int,
    val limitations: List<String>,
    val continuation: SourceReadContinuationCliDocument,
)

@Serializable
private sealed interface SourceReadContinuationCliDocument {
    @Serializable
    @SerialName("unavailable")
    data object Unavailable : SourceReadContinuationCliDocument

    @Serializable
    @SerialName("available")
    data class Available(val continuation: String) : SourceReadContinuationCliDocument
}

private fun SourceSnapshotDocument.toCliDocument() = SourceSnapshotCliDocument(
    canonicalRoot.value,
    generation,
    sourceState.value,
    file.value,
    textIdentity.value,
    coordinateUnit.cliName(),
    length.value,
)

private fun SourceRegionDocument.toCliDocument() = SourceRegionCliDocument(
    kind.cliName(),
    selection.toCliDocument(),
)

private fun SourceSelectionDocument.toCliDocument() = SourceSelectionCliDocument(
    selector.value,
    SourceSelectionRangeCliDocument(range.startInclusive.value, range.endExclusive.value),
)

private fun SourceEntityDocument.toCliDocument(): SourceEntityCliDocument = when (this) {
    is SourceEntityDocument.Declaration -> SourceEntityCliDocument.Declaration(
        kind.cliName(),
        name.value,
        visibility.cliName(),
        nestingDepth.value,
        parentSelector.value,
        selection.toCliDocument(),
        semanticIdentity.toCliDocument(),
    )
    is SourceEntityDocument.ValueParameter -> SourceEntityCliDocument.ValueParameter(
        name.value,
        nestingDepth.value,
        parentSelector.value,
        selection.toCliDocument(),
    )
    is SourceEntityDocument.Call -> SourceEntityCliDocument.Call(
        nestingDepth.value,
        parentSelector.value,
        selection.toCliDocument(),
        callee.toCliDocument(),
        target.toCliDocument(),
    )
    is SourceEntityDocument.Reference -> SourceEntityCliDocument.Reference(
        name.value,
        nestingDepth.value,
        parentSelector.value,
        selection.toCliDocument(),
        target.toCliDocument(),
    )
}

private fun SourceDeclarationSemanticIdentityDocument.toCliDocument():
    SourceDeclarationSemanticIdentityCliDocument = when (this) {
    is SourceDeclarationSemanticIdentityDocument.Candidate ->
        SourceDeclarationSemanticIdentityCliDocument.Candidate(selector.value)
}

private fun SourceEntityTargetDocument.toCliDocument(): SourceEntityTargetCliDocument = when (this) {
    is SourceEntityTargetDocument.Candidate ->
        SourceEntityTargetCliDocument.Candidate(selector.value)
    is SourceEntityTargetDocument.Local -> SourceEntityTargetCliDocument.Local(selector.value)
    is SourceEntityTargetDocument.Unresolved ->
        SourceEntityTargetCliDocument.Unresolved(reason.cliName())
}

private fun SourceTextProjectionDocument.toCliDocument(): SourceTextProjectionCliDocument =
    when (this) {
        SourceTextProjectionDocument.NotRequested -> SourceTextProjectionCliDocument.NotRequested
        is SourceTextProjectionDocument.Returned -> SourceTextProjectionCliDocument.Returned(
            selection.toCliDocument(),
            text.value,
            SourceLineRangeCliDocument(lines.startInclusive.value, lines.endInclusive.value),
        )
        is SourceTextProjectionDocument.Withheld ->
            SourceTextProjectionCliDocument.Withheld(reason.cliName())
    }

private fun SourceReadQualification.toCliDocument() = SourceReadQualificationCliDocument(
    knownMinimumEntityCount.value,
    limitations.map { it.cliName() },
    when (val state = continuation) {
        SourceReadContinuationStateDocument.Unavailable ->
            SourceReadContinuationCliDocument.Unavailable
        is SourceReadContinuationStateDocument.Available ->
            SourceReadContinuationCliDocument.Available(state.continuation.value)
    },
)

private val completeFactory = CliJsonDocument.generated(SourceReadCompleteCliDocument.serializer())
private val qualifiedFactory = CliJsonDocument.generated(SourceReadQualifiedCliDocument.serializer())

@Serializable
private data class SourceLineRangeCliDocument(val startInclusive: Long, val endInclusive: Long)
