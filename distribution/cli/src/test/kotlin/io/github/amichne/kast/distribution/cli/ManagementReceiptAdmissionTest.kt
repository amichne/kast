package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagementReceiptAdmissionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `a schema 3 product receipt is admitted with its exact product identity`() {
        val fixture = productFixture(temporary)
        val root = fixture.installation.parent
        val executable = Files.writeString(temporary.resolve("public-kast"), "public Control")
        writeTestManagementReceipt(root, executable, fixture.reference)
        val admitted = readReceipt(root) as ReceiptRead.Read
        assertEquals(fixture.reference, admitted.receipt.product)
        assertEquals(executable.toString(), admitted.receipt.executable)
    }

    @Test
    fun `a pre-product schema 2 receipt is not admitted`() {
        val fixture = productFixture(temporary)
        val root = fixture.installation.parent
        val executable = Files.writeString(temporary.resolve("public-kast"), "public Control")
        val retired =
            RetiredSchemaTwoReceipt(root.toString(), executable.toString(), sha256(executable), "STABLE", emptyList())
        Files.writeString(receiptPath(root), managementJson.encodeToString(retired))
        assertEquals(ReceiptRead.Unavailable("receipt_invalid"), readReceipt(root))
    }

    @Test
    fun `recorded agent registrations are not part of the receipt and reject`() {
        val fixture = productFixture(temporary)
        val root = fixture.installation.parent
        val executable = Files.writeString(temporary.resolve("public-kast"), "public Control")
        val retired =
            RetiredRegistrationReceipt(
                root.toString(),
                executable.toString(),
                sha256(executable),
                fixture.reference,
                emptyList(),
            )
        Files.writeString(receiptPath(root), managementJson.encodeToString(retired))
        assertEquals(ReceiptRead.Unavailable("receipt_invalid"), readReceipt(root))
    }
}

/** The retired pre-product receipt: no product identity and harness registrations owned by Kast. */
@Serializable
private data class RetiredSchemaTwoReceipt(
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val channel: String,
    val registrations: List<String>,
    @Required val schemaVersion: Int = 2,
)

/** A schema 3 receipt that still records agent registrations, which Kast no longer owns. */
@Serializable
private data class RetiredRegistrationReceipt(
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val product: InstalledProductReference,
    val registrations: List<String>,
    @Required val schemaVersion: Int = 3,
)
