package io.github.amichne.kast.cli.broker.core

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class ProviderNamespaceFailure { INVALID }

@JvmInline
internal value class ProviderNamespace private constructor(
    val value: String,
) : Comparable<ProviderNamespace> {
    override fun compareTo(other: ProviderNamespace): Int = value.compareTo(other.value)

    companion object {
        private val FORMAT = Regex("[a-z][a-z0-9-]{0,62}")

        internal fun admit(raw: String): Refinement<ProviderNamespace, ProviderNamespaceFailure> =
            if (FORMAT.matches(raw)) {
                Refinement.Refined(ProviderNamespace(raw))
            } else {
                Refinement.Rejected(ProviderNamespaceFailure.INVALID)
            }
    }
}

internal enum class ToolNameFailure { INVALID }

@JvmInline
internal value class ToolName private constructor(
    val value: String,
) : Comparable<ToolName> {
    override fun compareTo(other: ToolName): Int = value.compareTo(other.value)

    companion object {
        private val FORMAT = Regex("[a-z][a-z0-9_-]{0,63}")

        internal fun admit(raw: String): Refinement<ToolName, ToolNameFailure> =
            if (FORMAT.matches(raw)) Refinement.Refined(ToolName(raw))
            else Refinement.Rejected(ToolNameFailure.INVALID)
    }
}

internal enum class ProviderVersionFailure { INVALID }

@JvmInline
internal value class ProviderVersion private constructor(
    val value: String,
) {
    companion object {
        internal fun admit(raw: String): Refinement<ProviderVersion, ProviderVersionFailure> =
            if (
                raw.isNotBlank() && raw.length <= 128 &&
                raw.none { character -> character == '\n' || character == '\r' || character == '\u0000' }
            ) {
                Refinement.Refined(ProviderVersion(raw))
            } else {
                Refinement.Rejected(ProviderVersionFailure.INVALID)
            }
    }
}

internal enum class ToolDescriptionFailure { INVALID }

@JvmInline
internal value class ToolDescription private constructor(
    val value: String,
) {
    companion object {
        internal fun admit(raw: String): Refinement<ToolDescription, ToolDescriptionFailure> =
            if (raw.isNotBlank() && raw.length <= 4_096) {
                Refinement.Refined(ToolDescription(raw))
            } else {
                Refinement.Rejected(ToolDescriptionFailure.INVALID)
            }
    }
}

internal data class ToolAddress(
    val namespace: ProviderNamespace,
    val tool: ToolName,
)

internal enum class BrokerInvocationContextFailure {
    INVALID_THREAD_ID,
    INVALID_TURN_ID,
    INVALID_CALL_ID,
    WORKING_DIRECTORY_REJECTED,
}

@JvmInline
internal value class BrokerThreadId private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): BrokerThreadId? = raw.admittedProtocolId()?.let(::BrokerThreadId)
    }
}

@JvmInline
internal value class BrokerTurnId private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): BrokerTurnId? = raw.admittedProtocolId()?.let(::BrokerTurnId)
    }
}

@JvmInline
internal value class BrokerCallId private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): BrokerCallId? = raw.admittedProtocolId()?.let(::BrokerCallId)
    }
}

@JvmInline
internal value class CanonicalBrokerDirectory private constructor(val path: Path) {
    companion object {
        internal fun admit(candidate: Path): CanonicalBrokerDirectory? = try {
            candidate.toRealPath().takeIf { canonical ->
                canonical == candidate && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
            }?.let(::CanonicalBrokerDirectory)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

internal class BrokerInvocationContext private constructor(
    val threadId: BrokerThreadId,
    val turnId: BrokerTurnId,
    val callId: BrokerCallId,
    val workingDirectory: CanonicalBrokerDirectory,
) {
    val invocationId: String
        get() = "${threadId.value}:${turnId.value}:${callId.value}"

    companion object {
        internal fun admit(
            threadId: String,
            turnId: String,
            callId: String,
            workingDirectory: Path,
        ): Refinement<BrokerInvocationContext, BrokerInvocationContextFailure> {
            val admittedThread = BrokerThreadId.admit(threadId)
                ?: return Refinement.Rejected(BrokerInvocationContextFailure.INVALID_THREAD_ID)
            val admittedTurn = BrokerTurnId.admit(turnId)
                ?: return Refinement.Rejected(BrokerInvocationContextFailure.INVALID_TURN_ID)
            val admittedCall = BrokerCallId.admit(callId)
                ?: return Refinement.Rejected(BrokerInvocationContextFailure.INVALID_CALL_ID)
            val admittedDirectory = CanonicalBrokerDirectory.admit(workingDirectory)
                ?: return Refinement.Rejected(
                    BrokerInvocationContextFailure.WORKING_DIRECTORY_REJECTED,
                )
            return Refinement.Refined(
                BrokerInvocationContext(
                    admittedThread,
                    admittedTurn,
                    admittedCall,
                    admittedDirectory,
                ),
            )
        }
    }
}

private fun String.admittedProtocolId(): String? =
    takeIf { it.isNotBlank() && it.length <= 512 && it.none { character -> character == '\u0000' } }
