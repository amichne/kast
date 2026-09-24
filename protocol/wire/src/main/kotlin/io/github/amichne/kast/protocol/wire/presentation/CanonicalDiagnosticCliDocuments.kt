@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import kotlinx.serialization.Serializable

/** Canonical IDE diagnostic results retain check kind and exact file coverage. */
object CanonicalDiagnosticCliDocuments {
    fun project(
        outcome:
            OperationOutcome<
                DiagnosticCheckResult,
                DiagnosticCheckQualification,
                DiagnosticCheckFailure,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result, live ->
                diagnosticCompleteFactory.create(
                    DiagnosticCompleteCliDocument(
                        operation = CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
                        status = "complete",
                        diagnostics = result.diagnostics.values.map { it.toCliDocument() },
                        progress = result.progress,
                        analysisKind = DiagnosticAnalysisKindCliDocument.IDE_FILE_DIAGNOSTICS,
                        coverage = result.progress?.coverage(exhaustive = true),
                        live = live,
                    )
                )
            },
            qualified = { result, qualification, live ->
                diagnosticQualifiedFactory.create(
                    DiagnosticQualifiedCliDocument(
                        operation = CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
                        status = "qualified",
                        diagnostics = result.diagnostics.values.map { it.toCliDocument() },
                        progress = result.progress,
                        qualification = qualification.toCliDocument(),
                        analysisKind = DiagnosticAnalysisKindCliDocument.IDE_FILE_DIAGNOSTICS,
                        coverage = result.progress?.coverage(exhaustive = false),
                        live = live,
                    )
                )
            },
            rejected = { rejection ->
                canonicalDiagnosticRejectedDocument(rejection)
            },
        )
}

@Serializable
private data class DiagnosticCompleteCliDocument(
    val operation: String,
    val status: String,
    val diagnostics: List<DiagnosticCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val progress: io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument? = null,
    val analysisKind: DiagnosticAnalysisKindCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val coverage: DiagnosticCoverageCliDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
private data class DiagnosticQualifiedCliDocument(
    val operation: String,
    val status: String,
    val diagnostics: List<DiagnosticCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val progress: io.github.amichne.kast.protocol.contract.DiagnosticProgressDocument? = null,
    val qualification: DiagnosticQualificationCliDocument,
    val analysisKind: DiagnosticAnalysisKindCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val coverage: DiagnosticCoverageCliDocument? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
enum class DiagnosticAnalysisKindCliDocument {
    IDE_FILE_DIAGNOSTICS
}

@Serializable
data class DiagnosticCoverageCliDocument(
    val requestedPath: String?,
    val filesDiscovered: Int?,
    val filesAnalyzed: Int,
    val filesSkipped: Int?,
    val exhaustive: Boolean,
)

private fun DiagnosticProgressDocument.coverage(exhaustive: Boolean): DiagnosticCoverageCliDocument {
    val discovered = (inventory as? DiagnosticInventoryDocument.Exhausted)?.totalFiles?.value
    val analyzed = analyzedFiles.size
    val finished = stage == DiagnosticProgressStage.FINISHED
    return DiagnosticCoverageCliDocument(
        requestedPath = requestedPath?.value,
        filesDiscovered = discovered,
        filesAnalyzed = analyzed,
        filesSkipped = if (finished) discovered?.minus(analyzed)?.takeIf { it >= 0 } else null,
        exhaustive = exhaustive && finished && discovered == analyzed,
    )
}

@Serializable
private data class DiagnosticQualificationCliDocument(
    val knownDiagnosticCount: Int,
    val resultLimitReached: Boolean,
    val analyzedFiles: List<String>,
    val limitations: List<DiagnosticLimitationCliDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val continuation: String? = null,
)

@Serializable
private data class DiagnosticLimitationCliDocument(
    val file: String,
    val reason: String,
)

@Serializable
private data class DiagnosticCliDocument(
    val severity: String,
    val code: String,
    val message: String,
    val location: DiagnosticLocationCliDocument,
)

@Serializable
private data class DiagnosticLocationCliDocument(
    val candidateSelector: String,
    val file: String,
    val range: SourceRangeCliDocument,
)

private fun DiagnosticDocument.toCliDocument(): DiagnosticCliDocument =
    DiagnosticCliDocument(
        severity.cliName(),
        code.value,
        message.value,
        DiagnosticLocationCliDocument(
            location.candidateSelector.value,
            location.file.value,
            SourceRangeCliDocument(
                location.range.startInclusive.value,
                location.range.endExclusive.value,
            ),
        ),
    )

private fun DiagnosticCheckQualification.toCliDocument() =
    DiagnosticQualificationCliDocument(
        knownDiagnosticCount = knownDiagnosticCount.value,
        resultLimitReached = resultLimitReached,
        analyzedFiles = analyzedFiles.map { it.value },
        limitations = limitations.map(DiagnosticLimitationDocument::toCliDocument),
        continuation = continuation?.value,
    )

private fun DiagnosticLimitationDocument.toCliDocument() =
    DiagnosticLimitationCliDocument(
        file.value,
        reason.cliName(),
    )

private val diagnosticCompleteFactory = CanonicalJsonDocument.generated(DiagnosticCompleteCliDocument.serializer())
private val diagnosticQualifiedFactory = CanonicalJsonDocument.generated(DiagnosticQualifiedCliDocument.serializer())
