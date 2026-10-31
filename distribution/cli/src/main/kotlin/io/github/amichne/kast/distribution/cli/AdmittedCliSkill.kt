package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal const val CLI_SKILL_RELATIVE_PATH = "$AGENT_TOOLS_RELATIVE_PATH/plugins/kast/skills/kast"

internal class AdmittedCliSkill private constructor(private val files: List<SkillFile>) {
    internal fun materialize(directory: Path) {
        for (file in files) {
            val path = directory.resolve(file.relative)
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString(if (file.executable) "rwxr-xr-x" else "rw-r--r--"),
            )
        }
    }

    internal fun identical(directory: Path): Boolean {
        val actual =
            when (val read = readSkillDirectory(directory)) {
                is SkillDirectoryRead.Read -> read
                SkillDirectoryRead.Rejected -> return false
            }
        val names = files.map { it.relative }
        if (actual.files.map { directory.relativize(it).toString() }.toSet() != names.toSet()) return false
        if (expectedSkillDirectories(names) != actual.directories) return false
        return files.all { file -> Files.readAllBytes(directory.resolve(file.relative)).contentEquals(file.bytes) }
    }

    companion object {
        fun admit(root: Path): SkillAdmission {
            return try {
                requireOwnedExecutable(root)
                val installation = selectedInstallation(root)
                val manifest =
                    when (val read = readBundledManifest(installation)) {
                        is BundledManifestRead.Read -> read.manifest
                        BundledManifestRead.Invalid,
                        BundledManifestRead.Unavailable ->
                            return SkillAdmission.Rejected(ProvisionFailure.PAYLOAD_REJECTED)
                    }
                when (val inventory = SkillInventory.admit(installation, manifest)) {
                    SkillInventoryRead.Rejected -> SkillAdmission.Rejected(ProvisionFailure.PAYLOAD_REJECTED)
                    is SkillInventoryRead.Admitted ->
                        when (val captured = inventory.inventory.capture()) {
                            SkillCaptureRead.Rejected -> SkillAdmission.Rejected(ProvisionFailure.PAYLOAD_REJECTED)
                            is SkillCaptureRead.Captured -> SkillAdmission.Admitted(AdmittedCliSkill(captured.files))
                        }
                }
            } catch (_: ManagementRejected) {
                SkillAdmission.Rejected(ProvisionFailure.INSTALLATION_UNAVAILABLE)
            } catch (_: IOException) {
                SkillAdmission.Rejected(ProvisionFailure.PAYLOAD_REJECTED)
            } catch (_: SecurityException) {
                SkillAdmission.Rejected(ProvisionFailure.PAYLOAD_REJECTED)
            }
        }
    }
}

private class SkillFile(val relative: String, val bytes: ByteArray, val executable: Boolean)

private sealed interface SkillCaptureRead {
    data class Captured(val files: List<SkillFile>) : SkillCaptureRead

    data object Rejected : SkillCaptureRead
}

private sealed interface SkillInventoryRead {
    data class Admitted(val inventory: SkillInventory) : SkillInventoryRead

    data object Rejected : SkillInventoryRead
}

private class SkillInventory
private constructor(
    private val installation: Path,
    private val manifest: BundledManifest,
    private val entries: List<BundledPayload>,
) {
    fun capture(): SkillCaptureRead {
        val files = mutableListOf<SkillFile>()
        for (entry in entries) {
            if (!payloadOwned(installation, manifest, entry.path)) return SkillCaptureRead.Rejected
            val bytes = Files.readAllBytes(installation.resolve(entry.path))
            if (entry.sha256 != "sha256:${skillDigest(bytes)}") return SkillCaptureRead.Rejected
            files +=
                SkillFile(
                    entry.path.removePrefix("$CLI_SKILL_RELATIVE_PATH/"),
                    bytes,
                    entry.mode and EXECUTABLE_BITS != 0,
                )
        }
        return SkillCaptureRead.Captured(files.toList())
    }

    companion object {
        fun admit(installation: Path, manifest: BundledManifest): SkillInventoryRead {
            val directory = installation.resolve(CLI_SKILL_RELATIVE_PATH)
            val paths =
                when (val read = readSkillDirectory(directory)) {
                    is SkillDirectoryRead.Read -> read.files
                    SkillDirectoryRead.Rejected -> return SkillInventoryRead.Rejected
                }
            if (directory.toRealPath() != directory) return SkillInventoryRead.Rejected
            val entries = manifest.payloadFiles.filter { it.path.startsWith("$CLI_SKILL_RELATIVE_PATH/") }
            val actual = paths.map { installation.relativize(it).toString() }.toSet()
            if (!completeSkillInventory(entries, actual)) return SkillInventoryRead.Rejected
            if (paths.sumOf(Files::size) > ControlDistributionLimits.maximumPayloadBytes)
                return SkillInventoryRead.Rejected
            return SkillInventoryRead.Admitted(SkillInventory(installation, manifest, entries.toList()))
        }
    }
}

private fun completeSkillInventory(entries: List<BundledPayload>, actual: Set<String>): Boolean =
    entries.size == actual.size &&
        entries.map { it.path }.toSet() == actual &&
        "$CLI_SKILL_RELATIVE_PATH/SKILL.md" in actual

private fun skillDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val EXECUTABLE_BITS = 73

internal sealed interface SkillAdmission {
    data class Admitted(val skill: AdmittedCliSkill) : SkillAdmission

    data class Rejected(val reason: ProvisionFailure) : SkillAdmission
}
