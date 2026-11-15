package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class ProductReceiptFixture(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val product: InstalledProductReference,
    val registrations: List<ManagedRegistration>,
    val guidance: List<ManagedGuidance>,
)

internal fun writeProductManagement(installation: Path, executable: Path, reference: InstalledProductReference) {
    val receipt =
        ProductReceiptFixture(
            3,
            installation.parent.toString(),
            executable.toString(),
            sha256(executable),
            reference,
            emptyList(),
            emptyList(),
        )
    Files.writeString(receiptPath(installation.parent), containerJson.encodeToString(receipt))
}
