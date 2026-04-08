package io.github.amichne.kast.server

import io.github.amichne.kast.api.ValidationException

private fun validateAbsoluteFilePath(filePath: String) {
    val path = java.nio.file.Path.of(filePath)
    if (!path.isAbsolute) {
        throw ValidationException(
            message = "File paths must be absolute",
            details = mapOf("filePath" to filePath),
        )
    }
}
