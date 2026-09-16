package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Only the installation manifest's current anchor may cross the otherwise strict physical-path boundary. */
internal object InstalledConfigurationAlias {
    fun read(
        path: Path,
        environment: Map<String, String>,
        readPinned: (Path, Map<String, String>) -> SavedConfigurationIngress = { pinned, selected ->
            InstalledSavedConfigurationIngress.read(pinned.toString(), selected)
        },
    ): SavedConfigurationIngress {
        if (!path.endsWith(Path.of("current/config/environment"))) return rejected()
        val alias = path.parent.parent
        return try {
            withActivationLock(alias, environment, readPinned)
        } catch (_: IOException) {
            rejected()
        } catch (_: SecurityException) {
            rejected()
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            rejected()
        } catch (_: SerializationException) {
            rejected()
        } catch (_: UnsupportedOperationException) {
            rejected()
        }
    }

    private fun withActivationLock(
        alias: Path,
        environment: Map<String, String>,
        readPinned: (Path, Map<String, String>) -> SavedConfigurationIngress,
    ): SavedConfigurationIngress {
        val root = alias.parent
        val lock = root.resolve("activation.lock")
        if (root.toRealPath() != root || !Files.isRegularFile(lock, NOFOLLOW_LINKS) || lock.toRealPath() != lock)
            return rejected()
        return FileChannel.open(lock, READ, NOFOLLOW_LINKS).use { channel ->
            val activation = channel.tryLock(0, Long.MAX_VALUE, true) ?: return rejected()
            activation.use { readLocked(alias, environment, readPinned) }
        }
    }

    private fun readLocked(
        alias: Path,
        environment: Map<String, String>,
        readPinned: (Path, Map<String, String>) -> SavedConfigurationIngress,
    ): SavedConfigurationIngress {
        val before = Files.readAttributes(alias, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isSymbolicLink) return rejected()
        val target = Files.readSymbolicLink(alias)
        if (target.isAbsolute || target.nameCount != 2) return rejected()
        if (target.getName(0).toString() != "versions") return rejected()
        val installation = alias.parent.resolve(target)
        if (
            installation.toRealPath() != installation ||
                Files.getOwner(alias, NOFOLLOW_LINKS) != Files.getOwner(installation, NOFOLLOW_LINKS)
        )
            return rejected()
        val installationBefore = Files.readAttributes(installation, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val configuration = installation.resolve("config/environment")
        if (Files.getOwner(configuration, NOFOLLOW_LINKS) != Files.getOwner(installation, NOFOLLOW_LINKS))
            return rejected()
        if (!admittedManifest(installation, alias, target, configuration)) return rejected()
        val loaded = readPinned(configuration, environment)
        val after = Files.readAttributes(alias, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val installationAfter = Files.readAttributes(installation, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (
            !sameFile(installationBefore, installationAfter) ||
                !sameFile(before, after) ||
                Files.readSymbolicLink(alias) != target
        ) {
            return SavedConfigurationIngress.Rejected(
                SavedConfigurationIngressRejection.File(SavedConfigurationIngressFailure.CHANGED_DURING_READ)
            )
        }
        return loaded
    }

    private fun admittedManifest(installation: Path, alias: Path, target: Path, configuration: Path): Boolean {
        val path = installation.resolve("installation.json")
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (
            !before.isRegularFile ||
                path.toRealPath() != path ||
                before.size() > ControlDistributionLimits.maximumManifestBytes
        )
            return false
        if (Files.getOwner(path, NOFOLLOW_LINKS) != Files.getOwner(installation, NOFOLLOW_LINKS)) return false
        val content =
            Files.newInputStream(path, NOFOLLOW_LINKS).use {
                it.readNBytes(ControlDistributionLimits.maximumManifestBytes + 1)
            }
        if (content.size > ControlDistributionLimits.maximumManifestBytes) return false
        // This projection reads the existing manifest's selector ownership; payload verification remains with its
        // owner.
        val manifest =
            manifestJson.decodeFromString(AliasInstallationManifest.serializer(), content.toString(Charsets.UTF_8))
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return sameFile(before, after) && manifest.owns(installation, alias, target, configuration)
    }

    private fun sameFile(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.fileKey() == after.fileKey() &&
            before.lastModifiedTime() == after.lastModifiedTime() &&
            before.size() == after.size()

    private fun rejected() =
        SavedConfigurationIngress.Rejected(
            SavedConfigurationIngressRejection.File(SavedConfigurationIngressFailure.INVALID_INSTALLATION)
        )

    private val manifestJson = Json { ignoreUnknownKeys = true }
}

@Serializable
private data class AliasInstallationManifest(
    val schemaVersion: Int,
    val semanticVersion: String,
    val installationRoot: String,
    val payloadIdentity: String,
    val configuration: String,
    val externalAnchors: List<AliasInstallationAnchor>,
) {
    fun owns(installation: Path, alias: Path, target: Path, configurationPath: Path): Boolean =
        schemaVersion in setOf(1, 2) &&
            installationRoot == installation.toString() &&
            configuration == configurationPath.toString() &&
            payloadIdentity.matches(Regex("sha256:[0-9a-f]{64}")) &&
            installation.fileName.toString() == "$semanticVersion-${payloadIdentity.removePrefix("sha256:")}" &&
            externalAnchors.filter { it.kind == "current" } ==
                listOf(AliasInstallationAnchor("current", alias.toString(), target.toString()))
}

@Serializable
private data class AliasInstallationAnchor(val kind: String, val path: String, val expectedLinkTarget: String? = null)
