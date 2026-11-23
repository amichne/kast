package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The fixtures are receipts the Python product installer wrote in `test-managed-build-record.py`; only their temporary
 * path prefix is replaced with `/home/user`.
 */
class InstallationReplacementReceiptTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val identity = InstallationFilesystemIdentity(1, 2, 501)

    @Test
    fun `a first product installation journal decodes to its recorded values`() {
        val receipt = json.decodeFromString<InstallationReplacementReceipt>(fixture("product-install.json"))
        assertEquals(
            InstallationReplacementReceipt(
                stage = InstallationReplacementStage.PAYLOAD_COMMITTED,
                installation = "/home/user/managed/installation",
                installationIdentity = InstallationFilesystemIdentity(16777234, 656218458, 501),
                previous = PreviousInstallationPayload.None,
                reservation =
                    ManagedControlReservation(
                        "91e7e538-badd-40a0-9c42-dbe71c53590c",
                        "/home/user/.kast-install-91e7e538-badd-40a0-9c42-dbe71c53590c",
                        "06fbeec023d40b0304e519ef91e58dc4369cc84086747ddecb6f6f495dcfb2c2",
                        "0.0.123",
                        "44d1b0b6a3a0ab0f6f14319a6dc8701e0bbd709c2c8c874f3196052f6d6f73b4",
                    ),
                product =
                    ProductReplacement(
                        management = ManagedFilePreimage.Absent("/home/user/managed/management.json"),
                        publicExecutable = ManagedFilePreimage.Absent("/home/user/.local/bin/kast"),
                        selectionSha256 = "c06fa4b3d3e6ebe4170e27162245d6af468e868cf0cd2c1b05df0639c10df8be",
                        previousManifestSha256 = null,
                        phase = ManagedReplacementPhase.CONTROL_COMMITTED,
                        fence = InstallationFilesystemIdentity(16777234, 656218521, 501),
                    ),
            ),
            receipt,
        )
    }

    @Test
    fun `an upgrade journal decodes its present preimages and previous manifest`() {
        val product = json.decodeFromString<InstallationReplacementReceipt>(fixture("product-upgrade.json")).product
        assertEquals(
            ManagedFilePreimage.Present(
                "/home/user/managed/management.json",
                "/home/user/managed/recovery/replacement/management-preimage",
                "44d9f96a72fce3b9df85a0e874b235e552343c8bb74691b8b4ee19fbb730a7de",
                384,
            ),
            product.management,
        )
        assertEquals(
            ManagedFilePreimage.Present(
                "/home/user/.local/bin/kast",
                "/home/user/managed/recovery/replacement/command-preimage",
                "6f2b0134b33d0389edce3e9436de937f6878531411624bb8eea9e96c1851014e",
                493,
            ),
            product.publicExecutable,
        )
        assertEquals(
            "377c7553fb671d599a406f502ed2fb3307d6a5f1f7201911d9fe9bec91fe0c73",
            product.previousManifestSha256,
        )
    }

    @Test
    fun `an encoded installation journal has exactly the installer's recorded shape`() {
        assertRecordedShape("product-install.json")
    }

    @Test
    fun `an encoded upgrade journal has exactly the installer's recorded shape`() {
        assertRecordedShape("product-upgrade.json")
    }

    private fun assertRecordedShape(name: String) {
        val recorded = fixture(name)
        val receipt = json.decodeFromString<InstallationReplacementReceipt>(recorded)
        assertEquals(
            json.parseToJsonElement(recorded),
            json.parseToJsonElement(json.encodeToString(receipt)),
        )
    }

    @Test
    fun `a composition journal rejects at decoding`() {
        val composition =
            json.encodeToString(
                CompositionReceipt(
                    stage = "PREPARED",
                    installation = "/root/installation",
                    installationIdentity = identity,
                    previous = PreviousInstallationPayload.None,
                    reservation = reservation(),
                    composition = RecordedComposition("PREPARED"),
                    product = product(),
                )
            )
        assertThrows<SerializationException> { json.decodeFromString<InstallationReplacementReceipt>(composition) }
    }

    @Test
    fun `a journal that still records a null composition rejects at decoding`() {
        val retired =
            json.encodeToString(
                NullCompositionReceipt(
                    stage = "PREPARED",
                    installation = "/root/installation",
                    installationIdentity = identity,
                    previous = PreviousInstallationPayload.None,
                    reservation = reservation(),
                    product = product(),
                )
            )
        assertThrows<SerializationException> { json.decodeFromString<InstallationReplacementReceipt>(retired) }
    }

    @Test
    fun `a journal without its product record rejects at decoding`() {
        val unrecorded =
            json.encodeToString(
                UnrecordedProductReceipt(
                    stage = "PREPARED",
                    installation = "/root/installation",
                    installationIdentity = identity,
                    previous = PreviousInstallationPayload.None,
                    reservation = reservation(),
                )
            )
        assertThrows<SerializationException> { json.decodeFromString<InstallationReplacementReceipt>(unrecorded) }
    }

    @Test
    fun `an unknown product phase rejects at decoding`() {
        val unknown =
            json.encodeToString(
                UnknownPhaseReceipt(
                    stage = "PREPARED",
                    installation = "/root/installation",
                    installationIdentity = identity,
                    previous = PreviousInstallationPayload.None,
                    reservation = reservation(),
                    product =
                        UnknownPhaseProduct(
                            ManagedFilePreimage.Absent("/root/management.json"),
                            ManagedFilePreimage.Absent("/home/.local/bin/kast"),
                            "a".repeat(64),
                            null,
                            "COMPOSED",
                            null,
                        ),
                )
            )
        assertThrows<SerializationException> { json.decodeFromString<InstallationReplacementReceipt>(unknown) }
    }

    @Test
    fun `an unwritten product phase or stage rejects at decoding`() {
        json.decodeFromString<InstallationReplacementReceipt>(json.encodeToString(unknownPhase("PREPARED", "PREPARED")))
        val unwritten =
            listOf("HOST_COMMITTED", "RESTORING", "RESTORED").map { unknownPhase("PREPARED", it) } +
                unknownPhase("RECOVERY_REQUIRED", "PREPARED")
        unwritten.forEach { receipt ->
            assertThrows<SerializationException> {
                json.decodeFromString<InstallationReplacementReceipt>(json.encodeToString(receipt))
            }
        }
    }

    private fun unknownPhase(stage: String, phase: String) =
        UnknownPhaseReceipt(
            stage = stage,
            installation = "/root/installation",
            installationIdentity = identity,
            previous = PreviousInstallationPayload.None,
            reservation = reservation(),
            product =
                UnknownPhaseProduct(
                    ManagedFilePreimage.Absent("/root/management.json"),
                    ManagedFilePreimage.Absent("/home/.local/bin/kast"),
                    "a".repeat(64),
                    null,
                    phase,
                    null,
                ),
        )

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
                    reservation = reservation(),
                    product = product(),
                )
            )
        assertThrows<SerializationException> { json.decodeFromString<InstallationReplacementReceipt>(retired) }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/replacement-receipt/$name")) { name }.readText()

    private fun reservation() =
        ManagedControlReservation(
            "12345678-1234-1234-1234-123456789abc",
            "/root/.install-12345678-1234-1234-1234-123456789abc",
            "b".repeat(64),
            "1.2.3",
            "c".repeat(64),
        )

    private fun product() =
        ProductReplacement(
            ManagedFilePreimage.Absent("/root/management.json"),
            ManagedFilePreimage.Absent("/home/.local/bin/kast"),
            "a".repeat(64),
            null,
            ManagedReplacementPhase.PREPARED,
            null,
        )
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
    val reservation: ManagedControlReservation,
    val product: ProductReplacement,
)

@Serializable private data class RecordedComposition(val phase: String)

/** A Host composition journal; the product installer never recovers one. */
@Serializable
private data class CompositionReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: String,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: PreviousInstallationPayload,
    val reservation: ManagedControlReservation,
    @SerialName("composition") val composition: RecordedComposition,
    val product: ProductReplacement,
)

/** The retired journal shape that recorded an always-null `composition` beside the product record. */
@Serializable
private data class NullCompositionReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: String,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: PreviousInstallationPayload,
    val reservation: ManagedControlReservation,
    @Required val composition: Nothing? = null,
    val product: ProductReplacement,
)

@Serializable
private data class UnrecordedProductReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: String,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: PreviousInstallationPayload,
    val reservation: ManagedControlReservation,
)

@Serializable
private data class UnknownPhaseProduct(
    val management: ManagedFilePreimage,
    val publicExecutable: ManagedFilePreimage,
    val selectionSha256: String,
    val previousManifestSha256: String?,
    val phase: String,
    val fence: InstallationFilesystemIdentity?,
)

@Serializable
private data class UnknownPhaseReceipt(
    @Required val schemaVersion: Int = 1,
    val stage: String,
    val installation: String,
    val installationIdentity: InstallationFilesystemIdentity,
    val previous: PreviousInstallationPayload,
    val reservation: ManagedControlReservation,
    val product: UnknownPhaseProduct,
)
