package io.github.amichne.kast.indexstore

import java.nio.file.Path

object SourceIndexFilePolicy {
    fun isEligible(path: Path): Boolean =
        path.fileName?.toString()?.endsWith(".kt") == true

    fun isEligible(path: String): Boolean =
        isEligible(Path.of(path))
}
