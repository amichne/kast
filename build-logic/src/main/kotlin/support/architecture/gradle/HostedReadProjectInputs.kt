package support.architecture.gradle

import support.architecture.HostedReadProjectJarBytes
import java.io.File
import java.io.IOException
import java.nio.file.Files

internal sealed interface HostedReadProjectInputFailure {
    data class MalformedIdentity(val observed: String) : HostedReadProjectInputFailure
    data class DuplicateArtifactName(val observed: String) : HostedReadProjectInputFailure
    data class ArtifactSetMismatch(val declared: Set<String>, val observed: Set<String>) :
        HostedReadProjectInputFailure
    data class UnreadableArtifact(val observed: String) : HostedReadProjectInputFailure
}

internal sealed interface HostedReadProjectInputResult {
    data class Loaded(val jars: List<HostedReadProjectJarBytes>) : HostedReadProjectInputResult
    data class Rejected(val failure: HostedReadProjectInputFailure) : HostedReadProjectInputResult
}

/**
 * Proof transition: `(List<String>, Set<File>) -> HostedReadProjectInputResult`.
 *
 * Establishes an exact one-to-one association between resolved project paths and immutable runtime
 * artifact bytes. Malformed identities, duplicate names, mismatched files, and unreadable
 * artifacts remain closed [HostedReadProjectInputFailure] data. Gradle project identities and
 * files are extracted only at this task/receipt boundary.
 */
internal fun loadHostedReadProjectInputs(
    identities: List<String>,
    files: Set<File>,
): HostedReadProjectInputResult {
    val parsed = mutableListOf<Pair<String, String>>()
    val declaredNames = linkedSetOf<String>()
    identities.forEach { identity ->
        val separator = identity.lastIndexOf('|')
        if (separator <= 0 || separator == identity.lastIndex) {
            return HostedReadProjectInputResult.Rejected(
                HostedReadProjectInputFailure.MalformedIdentity(identity),
            )
        }
        val parsedIdentity = identity.substring(0, separator) to identity.substring(separator + 1)
        if (!declaredNames.add(parsedIdentity.second)) return duplicate(parsedIdentity.second)
        parsed += parsedIdentity
    }
    val observedNames = linkedSetOf<String>()
    files.forEach { file ->
        if (!observedNames.add(file.name)) return duplicate(file.name)
    }
    val filesByName = files.associateBy(File::getName)
    if (declaredNames != filesByName.keys) {
        return HostedReadProjectInputResult.Rejected(
            HostedReadProjectInputFailure.ArtifactSetMismatch(
                declaredNames,
                filesByName.keys,
            ),
        )
    }
    val jars = parsed.sortedBy { "${it.first}|${it.second}" }.map { (projectPath, name) ->
        val bytes = try {
            Files.readAllBytes(filesByName.getValue(name).toPath())
        } catch (_: IOException) {
            return HostedReadProjectInputResult.Rejected(
                HostedReadProjectInputFailure.UnreadableArtifact(name),
            )
        } catch (_: SecurityException) {
            return HostedReadProjectInputResult.Rejected(
                HostedReadProjectInputFailure.UnreadableArtifact(name),
            )
        }
        HostedReadProjectJarBytes.capture(projectPath, name, bytes)
    }
    return HostedReadProjectInputResult.Loaded(jars)
}

private fun duplicate(name: String) = HostedReadProjectInputResult.Rejected(
    HostedReadProjectInputFailure.DuplicateArtifactName(name),
)
