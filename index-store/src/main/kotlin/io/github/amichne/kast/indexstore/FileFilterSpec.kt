package io.github.amichne.kast.indexstore

import java.nio.file.FileSystems

/**
 * Generic file-path filter for metrics queries.
 *
 * [fileGlob] is a glob pattern applied to the full file path (e.g. `**&#47;*Test.kt`).
 * [folderPrefix] is a path prefix filter - only files whose path starts with this
 * prefix are included (e.g. `/src/main/kotlin`).
 *
 * Both filters are combined with AND semantics when both are non-null.
 */
data class FileFilterSpec(
    val fileGlob: String? = null,
    val folderPrefix: String? = null,
) {
    private val globMatcher by lazy {
        fileGlob?.let {
            val pattern = if (it.startsWith("glob:") || it.startsWith("regex:")) it else "glob:$it"
            FileSystems.getDefault().getPathMatcher(pattern)
        }
    }

    /** Returns true if [path] passes all configured filters. */
    fun matches(path: String?): Boolean {
        if (path == null) return false
        if (folderPrefix != null) {
            val normalized = if (folderPrefix.endsWith("/")) folderPrefix else "$folderPrefix/"
            if (!path.startsWith(normalized)) return false
        }
        if (globMatcher != null) {
            val filePath = java.nio.file.Path.of(path)
            if (!globMatcher!!.matches(filePath)) return false
        }
        return true
    }

    val isEmpty: Boolean get() = fileGlob == null && folderPrefix == null
}

/** Filters a list retaining only items where [pathExtractor] returns a path that passes [filter]. */
fun <T> List<T>.filterByPath(filter: FileFilterSpec, pathExtractor: (T) -> String?): List<T> =
    if (filter.isEmpty) this else filter { item -> filter.matches(pathExtractor(item)) }
