package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InstallationReplacementReceiptTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val identity = InstallationFilesystemIdentity(1, 2, 501)

    @Test
    fun `a physical previous payload encodes its discriminator and round-trips`() {
        val receipt =
            InstallationReplacementReceipt(
                stage = InstallationReplacementStage.PREPARED,
                installation = "/root/installation",
                installationIdentity = identity,
                previous =
                    PreviousInstallationPayload.Physical(
                        "/root/installation",
                        identity,
                        "/slot/payload",
                        "/slot/recovery",
                    ),
            )
        val encoded = json.encodeToString(receipt)
        val previous = json.parseToJsonElement(encoded).jsonObject.getValue("previous").jsonObject
        assertEquals("PHYSICAL", previous.getValue("type").jsonPrimitive.content)
        assertEquals(receipt, json.decodeFromString<InstallationReplacementReceipt>(encoded))
    }

    @Test
    fun `the retired legacy previous payload rejects at decoding`() {
        val retired =
            json.encodeToString(
                RetiredReceipt(
                    stage = "PREPARED",
                    installation = "/root/installation",
                    installationIdentity = identity,
                    previous =
                        RetiredLegacyPrevious(
                            "/root/versions/1.0.0",
                            identity,
                            "/root/current",
                            "versions/1.0.0",
                            "/slot/recovery",
                        ),
                )
            )
        assertThrows<SerializationException> { json.decodeFromString<InstallationReplacementReceipt>(retired) }
    }

    @Test
    fun `a receipt with the retired composition record rejects at decoding`() {
        val retired =
            json.encodeToString(
                RetiredCompositionReceipt(
                    stage = "PREPARED",
                    installation = "/root/installation",
                    installationIdentity = identity,
                    previous = PreviousInstallationPayload.None,
                    composition = RetiredComposition("PREPARED"),
                )
            )
        assertThrows<SerializationException> { json.decodeFromString<InstallationReplacementReceipt>(retired) }
    }
}

/** The retired `LEGACY` previous payload: a versions/ directory behind a `current` selector link. */
@Serializable
private data class RetiredLegacyPrevious(
    val installation: String,
    val identity: InstallationFilesystemIdentity,
    val selector: String,
    val target: String,
    val recovery: String,
    @Required val type: String = "LEGACY",
)

@Serializable
private data class RetiredReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: String,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: RetiredLegacyPrevious,
)

@Serializable private data class RetiredComposition(val phase: String)

@Serializable
private data class RetiredCompositionReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: String,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: PreviousInstallationPayload,
    @SerialName("composition") val composition: RetiredComposition,
)
