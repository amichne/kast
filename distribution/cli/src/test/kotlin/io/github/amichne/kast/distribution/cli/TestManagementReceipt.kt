package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString

/** Tests record the schema 3 receipt the product installer writes; production Kast never writes it. */
internal fun writeTestManagementReceipt(root: Path, executable: Path, product: InstalledProductReference) {
    Files.createDirectories(root)
    Files.writeString(
        receiptPath(root),
        managementJson.encodeToString(
            ProductManagementReceipt(
                PRODUCT_MANAGEMENT_SCHEMA_VERSION,
                root.toString(),
                executable.toString(),
                sha256(executable),
                product,
            )
        ),
    )
}

internal fun encodeManagementReceipt(receipt: ManagementReceipt): String =
    managementJson.encodeToString(
        ProductManagementReceipt(
            receipt.schemaVersion,
            receipt.installationRoot,
            receipt.executable,
            receipt.executableSha256,
            receipt.product,
        )
    )

internal fun writeManagementReceipt(root: Path, receipt: ManagementReceipt) {
    Files.createDirectories(root)
    Files.writeString(receiptPath(root), encodeManagementReceipt(receipt))
}
