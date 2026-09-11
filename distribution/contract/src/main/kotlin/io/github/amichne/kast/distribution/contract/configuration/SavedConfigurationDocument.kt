package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class SavedConfigurationDocumentFailure {
    TOO_LARGE,
    INVALID_ENCODING,
    MALFORMED_RECORD,
    TOO_MANY_ASSIGNMENTS,
}

/** Literal ordered assignments. Syntax is never evaluated, duplicates are never erased. */
class SavedConfigurationDocument private constructor(private val assignments: List<Pair<String, String>>) {
    /** Raw assignments may cross only into the shared configuration-resolution boundary. */
    fun configurationSources(environment: Map<String, String>): ConfigurationSources =
        ConfigurationSources(
            environment = environment,
            savedInstallation = assignments,
        )

    override fun toString(): String = "SavedConfigurationDocument(assignments=${assignments.size})"

    companion object {
        const val MAXIMUM_BYTES: Int = 65_536
        const val MAXIMUM_ASSIGNMENTS: Int = 256

        fun parse(bytes: ByteArray): Refinement<SavedConfigurationDocument, SavedConfigurationDocumentFailure> {
            if (bytes.size > MAXIMUM_BYTES) return Refinement.Rejected(SavedConfigurationDocumentFailure.TOO_LARGE)
            val text =
                try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: CharacterCodingException) {
                    return Refinement.Rejected(SavedConfigurationDocumentFailure.INVALID_ENCODING)
                }
            val assignments = mutableListOf<Pair<String, String>>()
            for (line in text.split('\n')) {
                if (line.isEmpty() || line.startsWith('#')) continue
                val separator = line.indexOf('=')
                if (separator <= 0 || '\u0000' in line || '\r' in line) {
                    return Refinement.Rejected(SavedConfigurationDocumentFailure.MALFORMED_RECORD)
                }
                assignments.add(line.substring(0, separator) to line.substring(separator + 1))
                if (assignments.size > MAXIMUM_ASSIGNMENTS)
                    return Refinement.Rejected(SavedConfigurationDocumentFailure.TOO_MANY_ASSIGNMENTS)
            }
            return Refinement.Refined(SavedConfigurationDocument(assignments.toList()))
        }
    }
}
