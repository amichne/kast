package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceTerminalReasonDocument {
    @SerialName("upstream-incomplete") UPSTREAM_INCOMPLETE,
    @SerialName("text-projection-withheld") TEXT_PROJECTION_WITHHELD,
}

/** The qualification owns progress; legacy cursor availability is a derived projection. */
@Serializable
sealed interface SourceQualifiedProgressDocument {
    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val checkpoint: SourceCheckpointDocument,
        @SerialName("next_action") val nextAction: ReadResumeActionDocument,
    ) : SourceQualifiedProgressDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(val reason: SourceTerminalReasonDocument) : SourceQualifiedProgressDocument
}

@Serializable
sealed interface SourceCheckpointDocument {
    val token: ProtocolText

    @Serializable
    @SerialName("upstream")
    data class Upstream(override val token: ProtocolText) : SourceCheckpointDocument

    @Serializable
    @SerialName("retained_output")
    data class RetainedOutput(
        override val token: ProtocolText,
        val upstream: SourcePreparedCoverageDocument,
    ) : SourceCheckpointDocument
}

/** Draining retained entities cannot strengthen the original source coverage. */
@Serializable
sealed interface SourcePreparedCoverageDocument {
    @Serializable @SerialName("complete") data object Complete : SourcePreparedCoverageDocument

    @Serializable @SerialName("resumable") data object Resumable : SourcePreparedCoverageDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(val reason: SourceTerminalReasonDocument) : SourcePreparedCoverageDocument
}

internal fun SourceQualifiedProgressDocument.hasCanonicalSyntax(): Boolean =
    when (this) {
        is SourceQualifiedProgressDocument.TerminalIncomplete -> true
        is SourceQualifiedProgressDocument.Resumable ->
            when (val reference = checkpoint) {
                is SourceCheckpointDocument.Upstream -> NATIVE_TOKEN.matches(reference.token.value)
                is SourceCheckpointDocument.RetainedOutput -> OUTPUT_TOKEN.matches(reference.token.value)
            }
    }

private val NATIVE_TOKEN = Regex("source-read-continuation-v1\\|[0-9a-f]{64}")
private val OUTPUT_TOKEN = Regex("source-output:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun SourceQualifiedProgressDocument.hasSupportedTerminalReason(
    limitations: List<SourceReadLimitationDocument>
): Boolean =
    when (this) {
        is SourceQualifiedProgressDocument.TerminalIncomplete -> reason.isSupportedBy(limitations)
        is SourceQualifiedProgressDocument.Resumable ->
            when (val cursor = checkpoint) {
                is SourceCheckpointDocument.Upstream -> true
                is SourceCheckpointDocument.RetainedOutput ->
                    when (val coverage = cursor.upstream) {
                        SourcePreparedCoverageDocument.Complete,
                        SourcePreparedCoverageDocument.Resumable -> true
                        is SourcePreparedCoverageDocument.TerminalIncomplete ->
                            coverage.reason.isSupportedBy(limitations)
                    }
            }
    }

private fun SourceTerminalReasonDocument.isSupportedBy(limitations: List<SourceReadLimitationDocument>): Boolean =
    when (this) {
        SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE -> true
        SourceTerminalReasonDocument.TEXT_PROJECTION_WITHHELD ->
            SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED in limitations
    }
