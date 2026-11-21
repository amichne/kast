package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.managed.ManagedRecoveryRemovalProof
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal fun retiredUninstallFixture(temporary: Path): RetiredFixture {
    val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
    val root = Files.createDirectories(home.resolve(".local/share/kast"))
    val executable = home.resolve(".local/bin/kast")
    Files.createDirectories(executable.parent)
    Files.writeString(executable, "owned executable")
    // The retired installation's payload is gone; its product identity comes from an unrelated fixture.
    val product = productFixture(Files.createDirectory(temporary.resolve("product"))).reference
    writeTestManagementReceipt(root, executable, product)
    Files.writeString(
        uninstallJournalPath(root),
        managementJson.encodeToString(
            FixtureRetirementRecord(
                "CONTROL_RETIRED",
                root.toString(),
                executable.toString(),
                sha256(executable),
                captureUninstallManagedRoot(root),
                ManagedRecoveryRemovalProof.Absent,
                captureUninstallManagedRoot(root),
                product = product,
            )
        ),
    )
    Files.setPosixFilePermissions(
        uninstallJournalPath(root),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
    )
    return RetiredFixture(root, home, executable)
}

internal data class RetiredFixture(
    val root: Path,
    val home: Path,
    val executable: Path,
)

@Serializable
internal data class FixtureRetirementRecord(
    val type: String,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val managedRootIdentity: InstallationFilesystemIdentity,
    val recoveryProof: ManagedRecoveryRemovalProof,
    val controlIdentity: InstallationFilesystemIdentity,
    val channel: String = "STABLE",
    val product: InstalledProductReference? = null,
)
