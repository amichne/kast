package support.architecture.gradle

import support.architecture.HostedReadExternalJarBytes
import java.io.File
import java.io.IOException
import java.nio.file.Files

internal sealed interface HostedReadExternalInputFailure {
    data class MalformedIdentity(val observed: String) : HostedReadExternalInputFailure
    data class DuplicateArtifactName(val observed: String) : HostedReadExternalInputFailure
    data class ArtifactSetMismatch(
        val declared: Set<String>,
        val observed: Set<String>,
    ) : HostedReadExternalInputFailure
    data class UnreadableArtifact(val observed: String) : HostedReadExternalInputFailure
}

internal sealed interface HostedReadExternalInputResult {
    data class Loaded(val jars: List<HostedReadExternalJarBytes>) :
        HostedReadExternalInputResult
    data class Rejected(val failure: HostedReadExternalInputFailure) :
        HostedReadExternalInputResult
}

/**
 * Proof transition: `(List<String>, Set<File>) -> HostedReadExternalInputResult`.
 *
 * Establishes an exact one-to-one association between resolved module coordinates and immutable
 * runtime artifact bytes. Malformed identities, duplicate names, mismatched files, and unreadable
 * artifacts remain closed [HostedReadExternalInputFailure] data. Gradle coordinates and files are
 * extracted only at this task/receipt boundary.
 */
internal fun loadHostedReadExternalInputs(
    identities: List<String>,
    files: Set<File>,
): HostedReadExternalInputResult {
    val parsed = mutableListOf<Pair<String, String>>()
    val declaredNames = linkedSetOf<String>()
    identities.forEach { identity ->
        val separator = identity.lastIndexOf('|')
        if (separator <= 0 || separator == identity.lastIndex) {
            return HostedReadExternalInputResult.Rejected(
                HostedReadExternalInputFailure.MalformedIdentity(identity),
            )
        }
        val parsedIdentity = identity.substring(0, separator) to identity.substring(separator + 1)
        if (!declaredNames.add(parsedIdentity.second)) return duplicateExternal(parsedIdentity.second)
        parsed += parsedIdentity
    }
    val observedNames = linkedSetOf<String>()
    files.forEach { file ->
        if (!observedNames.add(file.name)) return duplicateExternal(file.name)
    }
    val filesByName = files.associateBy(File::getName)
    if (declaredNames != filesByName.keys) {
        return HostedReadExternalInputResult.Rejected(
            HostedReadExternalInputFailure.ArtifactSetMismatch(
                declaredNames,
                filesByName.keys,
            ),
        )
    }
    val jars = parsed.sortedBy { "${it.first}|${it.second}" }.map { (componentId, name) ->
        val bytes = try {
            Files.readAllBytes(filesByName.getValue(name).toPath())
        } catch (_: IOException) {
            return HostedReadExternalInputResult.Rejected(
                HostedReadExternalInputFailure.UnreadableArtifact(name),
            )
        } catch (_: SecurityException) {
            return HostedReadExternalInputResult.Rejected(
                HostedReadExternalInputFailure.UnreadableArtifact(name),
            )
        }
        HostedReadExternalJarBytes.capture(componentId, name, bytes)
    }
    return HostedReadExternalInputResult.Loaded(jars)
}

private fun duplicateExternal(name: String) = HostedReadExternalInputResult.Rejected(
    HostedReadExternalInputFailure.DuplicateArtifactName(name),
)
