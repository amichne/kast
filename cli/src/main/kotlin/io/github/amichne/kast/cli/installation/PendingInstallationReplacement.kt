package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.InstallationFilesystemIdentity
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal sealed interface PendingInstallationReplacement {
    data object Absent : PendingInstallationReplacement

    data class Committed(val receipt: InstallationReplacementReceipt) : PendingInstallationReplacement

    data object RecoveryRequired : PendingInstallationReplacement
}

internal fun observePendingInstallationReplacement(root: Path): PendingInstallationReplacement {
    val directory = root.resolve("recovery/replacement")
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return PendingInstallationReplacement.Absent
    return try {
        val receipt = directory.resolve("receipt.json")
        if (!physicalReplacementDirectory(directory) || !validReplacementReceiptFile(receipt))
            return PendingInstallationReplacement.RecoveryRequired
        val document =
            replacementJson.decodeFromString(InstallationReplacementReceipt.serializer(), Files.readString(receipt))
        val slots = ReplacementSlots(root = root, directory = directory, installation = root.resolve("installation"))
        if (validCommittedReplacement(slots, document) && validPreviousReplacement(slots, document))
            PendingInstallationReplacement.Committed(document)
        else PendingInstallationReplacement.RecoveryRequired
    } catch (_: IOException) {
        PendingInstallationReplacement.RecoveryRequired
    } catch (_: SerializationException) {
        PendingInstallationReplacement.RecoveryRequired
    } catch (_: java.nio.file.InvalidPathException) {
        PendingInstallationReplacement.RecoveryRequired
    } catch (_: SecurityException) {
        PendingInstallationReplacement.RecoveryRequired
    }
}

private const val MAXIMUM_REPLACEMENT_RECEIPT_BYTES = 65_536

private data class ReplacementSlots(val root: Path, val directory: Path, val installation: Path)

private fun physicalReplacementDirectory(directory: Path): Boolean =
    Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && directory.toRealPath() == directory

private fun validReplacementReceiptFile(receipt: Path): Boolean =
    Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) &&
        receipt.toRealPath() == receipt &&
        Files.size(receipt) <= MAXIMUM_REPLACEMENT_RECEIPT_BYTES

private fun validCommittedReplacement(slots: ReplacementSlots, document: InstallationReplacementReceipt): Boolean =
    validReplacementHeader(slots.installation, document) &&
        physicalIdentity(slots.installation, document.installationIdentity)

private fun validReplacementHeader(installation: Path, document: InstallationReplacementReceipt): Boolean =
    document.schemaVersion == 1 &&
        document.stage in
            setOf(InstallationReplacementStage.PAYLOAD_COMMITTED, InstallationReplacementStage.FINALIZING) &&
        document.installation == installation.toString()

private fun validPreviousReplacement(slots: ReplacementSlots, document: InstallationReplacementReceipt): Boolean =
    when (val previous = document.previous) {
        PreviousInstallationPayload.None ->
            Files.notExists(slots.directory.resolve("payload"), LinkOption.NOFOLLOW_LINKS) &&
                Files.notExists(slots.directory.resolve("recovery"), LinkOption.NOFOLLOW_LINKS)
        is PreviousInstallationPayload.Physical -> validPhysicalPrevious(slots, previous, document.stage)
        is PreviousInstallationPayload.Legacy -> validLegacyPrevious(slots, previous, document.stage)
    }

private fun validPhysicalPrevious(
    slots: ReplacementSlots,
    previous: PreviousInstallationPayload.Physical,
    stage: InstallationReplacementStage,
): Boolean {
    if (!validPhysicalPreviousLocations(slots, previous)) return false
    return finalizablePhysicalIdentity(Path.of(previous.payload), previous.identity, stage)
}

private fun validPhysicalPreviousLocations(
    slots: ReplacementSlots,
    previous: PreviousInstallationPayload.Physical,
): Boolean =
    previous.installation == slots.installation.toString() &&
        previous.payload == slots.directory.resolve("payload").toString() &&
        previous.recovery == slots.directory.resolve("recovery").toString()

private fun validLegacyPrevious(
    slots: ReplacementSlots,
    previous: PreviousInstallationPayload.Legacy,
    stage: InstallationReplacementStage,
): Boolean {
    val target = Path.of(previous.target)
    if (!validLegacyReplacementTarget(target) || !validLegacyPreviousLocations(slots, previous, target)) return false
    return finalizableLegacySelector(slots.root.resolve("current"), target, stage) &&
        finalizablePhysicalIdentity(Path.of(previous.installation), previous.identity, stage)
}

private fun validLegacyReplacementTarget(target: Path): Boolean {
    if (target.isAbsolute || target.nameCount != 2) return false
    return target.getName(0).toString() == "versions" && target.getName(1).toString() !in setOf(".", "..")
}

private fun validLegacyPreviousLocations(
    slots: ReplacementSlots,
    previous: PreviousInstallationPayload.Legacy,
    target: Path,
): Boolean =
    previous.selector == slots.root.resolve("current").toString() &&
        previous.recovery == slots.directory.resolve("recovery").toString() &&
        validLegacyInstallationLocation(slots.root, previous.installation, target)

private fun validLegacyInstallationLocation(root: Path, installation: String, target: Path): Boolean =
    installation == root.resolve(target).normalize().toString() &&
        Path.of(installation).parent == root.resolve("versions")

private fun finalizableLegacySelector(selector: Path, target: Path, stage: InstallationReplacementStage): Boolean =
    exactLegacySelector(selector, target) || finalizedReplacementEntryAbsent(selector, stage)

private fun exactLegacySelector(selector: Path, target: Path): Boolean =
    Files.isSymbolicLink(selector) && Files.readSymbolicLink(selector) == target

private fun finalizablePhysicalIdentity(
    path: Path,
    expected: InstallationFilesystemIdentity,
    stage: InstallationReplacementStage,
): Boolean = physicalIdentity(path, expected) || finalizedReplacementEntryAbsent(path, stage)

private fun finalizedReplacementEntryAbsent(path: Path, stage: InstallationReplacementStage): Boolean =
    stage == InstallationReplacementStage.FINALIZING && Files.notExists(path, LinkOption.NOFOLLOW_LINKS)

private fun physicalIdentity(path: Path, expected: InstallationFilesystemIdentity): Boolean =
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
        path.toRealPath() == path &&
        observeInstallationFilesystemIdentity(path) == expected

private val replacementJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

internal fun observeInstallationFilesystemIdentity(path: Path): InstallationFilesystemIdentity =
    InstallationFilesystemIdentity(
        (Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
    )
