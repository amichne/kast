package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path

internal fun canonicalLifecycleRoot(raw: String): Refinement<CanonicalWorkspaceRoot, IdeLifecycleFailure> =
    try {
        val path = Path.of(raw)
        if (!path.isAbsolute || !Files.isDirectory(path)) Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
        else
            when (val root = CanonicalWorkspaceRoot.fromCanonicalPath(path.toRealPath())) {
                is Refinement.Refined -> root
                is Refinement.Rejected -> Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
            }
    } catch (_: java.io.IOException) {
        Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
    } catch (_: IllegalArgumentException) {
        Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
    }
