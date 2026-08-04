package io.github.amichne.kast.idea.snapshot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.idea.SemanticPathContentIdentity
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object BuildClasspathFingerprintResolver {
    fun resolve(
        project: Project,
        workspaceIdentity: WorkspaceIdentity,
        isCancelled: () -> Boolean = { false },
    ): BuildClasspathFingerprint {
        val entries = buildList {
            add("workspace:${gitWorkspaceScope(workspaceIdentity.workspaceRootPath)}")
            workspaceIdentity.gradleRoot?.let { gradleRoot ->
                add("settings:${gradleRoot.settingsFileHash.value}")
            }
            classpathRootUrls(project)
                .mapTo(this) { rootUrl ->
                    buildString {
                        append("classpath:")
                        append(stableClasspathRootUrl(rootUrl, workspaceIdentity.workspaceRootPath))
                        append("\ncontent:")
                        append(classpathRootContentIdentity(rootUrl, isCancelled))
                    }
                }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(entries.joinToString("\n").toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
        return BuildClasspathFingerprint.parse(digest)
    }

    fun contentRoots(project: Project): Set<Path> =
        ApplicationManager.getApplication().runReadAction<Set<Path>> {
            classpathRootUrls(project).mapNotNullTo(linkedSetOf(), ::localClasspathRootPath)
        }

    private fun classpathRootUrls(project: Project): List<String> =
        OrderEnumerator.orderEntries(project)
            .recursively()
            .withoutModuleSourceEntries()
            .classes()
            .urls
            .toList()
}

private fun classpathRootContentIdentity(rootUrl: String, isCancelled: () -> Boolean): String {
    val protocol = rootUrl.substringBefore("://", missingDelimiterValue = "")
    if (protocol != StandardFileSystems.FILE_PROTOCOL && protocol != JarFileSystem.PROTOCOL) {
        return "virtual:$protocol"
    }
    val path = localClasspathRootPath(rootUrl)
        ?: return "unavailable:$protocol"
    return SemanticPathContentIdentity.resolve(path, isCancelled)
}

private fun localClasspathRootPath(rootUrl: String): Path? {
    val protocol = rootUrl.substringBefore("://", missingDelimiterValue = "")
    if (protocol != StandardFileSystems.FILE_PROTOCOL && protocol != JarFileSystem.PROTOCOL) return null
    val localPath = VfsUtilCore.urlToPath(rootUrl).substringBefore(JarFileSystem.JAR_SEPARATOR)
    return runCatching { Path.of(localPath).toAbsolutePath().normalize() }.getOrNull()
}

internal fun gitWorkspaceScope(workspaceRoot: java.nio.file.Path): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--show-prefix")
        .directory(workspaceRoot.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val output = process.inputStream.use { it.readAllBytes() }.toString(Charsets.UTF_8)
        .removeSuffix("\n")
        .removeSuffix("\r")
    output.takeIf { process.waitFor() == 0 } ?: ""
}.getOrDefault("")

internal fun stableClasspathRootUrl(url: String, workspaceRoot: java.nio.file.Path): String {
    val workspacePath = workspaceRoot.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
    if (workspacePath.isEmpty()) return url
    val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return url
    val end = start + workspacePath.length
    return if (url.startsWith(workspacePath, start) && (end == url.length || url[end] == '/' || url[end] == '!')) {
        url.replaceRange(start, end, "\$WORKSPACE")
    } else {
        url
    }
}
