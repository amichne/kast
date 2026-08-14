package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

import io.github.amichne.kast.idea.AdmittedWorkspaceContentIdentity
import java.nio.file.Path
import java.security.MessageDigest

internal class WorkspaceStateIdentityResolver(
    workspaceRoot: Path,
    private val admittedContentIdentity: () -> AdmittedWorkspaceContentIdentity,
    private val semanticEnvironmentIdentity: () -> String,
    private val indexingScopeIdentity: () -> String,
    externalBuildSemanticFiles: () -> Collection<Path> = { emptyList() },
    private val buildSemanticInputIdentity: () -> BuildSemanticInputIdentity =
        BuildSemanticInputIdentityResolver(workspaceRoot, externalBuildSemanticFiles)::resolve,
) {
    fun resolve(): WorkspaceStateIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "environment", semanticEnvironmentIdentity())
        update(digest, "scope", indexingScopeIdentity())
        update(digest, "admitted-content", admittedContentIdentity().value)
        update(digest, "build-inputs", buildSemanticInputIdentity().value)
        return WorkspaceStateIdentity(digest.digest().toHex())
    }

    private fun update(
        digest: MessageDigest,
        label: String,
        value: String,
    ) {
        digest.update(label.toByteArray())
        digest.update(FIELD_SEPARATOR)
        digest.update(value.toByteArray())
        digest.update(RECORD_SEPARATOR)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val FIELD_SEPARATOR = byteArrayOf(0)
        val RECORD_SEPARATOR = byteArrayOf(0xff.toByte())
    }
}
