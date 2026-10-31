package support.tasks

import java.nio.file.Path

private val privateArtifactSegments = setOf(".venv", "__pycache__", ".pytest_cache", ".ruff_cache")

/** Private runtimes and Python caches never become distribution inputs or payloads. */
internal fun isPrivateDistributionArtifact(path: Path): Boolean =
    path.any { it.toString() in privateArtifactSegments } || path.fileName.toString().endsWith(".pyc")
