package io.github.amichne.kast.indexstore.api.index

import io.github.amichne.kast.api.client.WorkspacePathPolicy
import java.nio.file.Path

object SourceIndexFilePolicy {
    fun isEligible(path: Path): Boolean =
        path.fileName?.toString()?.endsWith(".kt") == true &&
            !WorkspacePathPolicy.isHardExcluded(path)

    fun isEligible(path: String): Boolean =
        isEligible(Path.of(path))
}
