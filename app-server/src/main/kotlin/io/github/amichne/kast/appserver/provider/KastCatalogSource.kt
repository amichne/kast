package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Reads only the packaged contract; it cannot execute a command or acquire IDEA authority. */
internal fun interface KastCatalogSource {
    fun read(): Refinement<String, KastQualificationFailure>
}

internal class PackagedKastCatalog(private val path: Path) : KastCatalogSource {
    override fun read(): Refinement<String, KastQualificationFailure> =
        try {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                Refinement.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE)
            } else {
                val bytes =
                    Files.newInputStream(path, NOFOLLOW_LINKS).use {
                        it.readNBytes(BrokerOperationalLimits.maximumKastSchemaBytes + 1)
                    }
                if (bytes.size > BrokerOperationalLimits.maximumKastSchemaBytes) {
                    Refinement.Rejected(KastQualificationFailure.SCHEMA_SIZE_LIMIT)
                } else {
                    val text =
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                    Refinement.Refined(text)
                }
            }
        } catch (_: java.nio.charset.CharacterCodingException) {
            Refinement.Rejected(KastQualificationFailure.SCHEMA_INVALID)
        } catch (_: IOException) {
            Refinement.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE)
        } catch (_: SecurityException) {
            Refinement.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE)
        }
}
