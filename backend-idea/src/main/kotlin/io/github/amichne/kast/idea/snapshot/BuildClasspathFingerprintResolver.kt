package io.github.amichne.kast.idea.snapshot

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import java.security.MessageDigest

object BuildClasspathFingerprintResolver {
    fun resolve(project: Project, workspaceIdentity: WorkspaceIdentity): BuildClasspathFingerprint {
        val entries = buildList {
            workspaceIdentity.gradleRoot?.let { gradleRoot ->
                add("settings:${gradleRoot.settingsFileHash.value}")
            }
            OrderEnumerator.orderEntries(project).recursively().classes().roots
                .mapTo(this) { root -> "classpath:${stableClasspathRootUrl(root.url, workspaceIdentity.workspaceRootPath)}" }
        }.sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(entries.joinToString("\n").toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
        return BuildClasspathFingerprint.parse(digest)
    }
}

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
