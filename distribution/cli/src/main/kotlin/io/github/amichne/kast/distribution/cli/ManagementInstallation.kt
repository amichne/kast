package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal fun sha256(path: Path): String {
    val started = System.nanoTime()
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            bytes += count
        }
    }
    ControlStageTrace.record(ControlStage.PAYLOAD_VERIFICATION, ControlStageOutcome.COMPLETED, started, bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal enum class ManagementRootFailure(val reason: String) {
    HOME_UNAVAILABLE("HOME is unavailable"),
    EMPTY_PATH("installation root is empty"),
    INVALID_PATH("installation root is invalid"),
    RELATIVE_PATH("installation root must be absolute"),
    UNNORMALIZED_PATH("installation root must be normalized"),
    ALTERNATE_INSTALLATION("installation root must be HOME/.local/share/kast"),
}

internal sealed interface ManagementRootResolution {
    class Selected private constructor(val root: Path) : ManagementRootResolution {
        companion object {
            fun admit(homeRaw: String, explicitRoot: String?): ManagementRootResolution {
                val home =
                    when (val admitted = ManagementPathAdmission.admit(homeRaw)) {
                        is ManagementPathAdmission.Rejected -> return Rejected(admitted.failure)
                        is ManagementPathAdmission.Admitted -> admitted.path
                    }
                val canonical = home.resolve(".local/share/kast")
                if (explicitRoot == null) return Selected(canonical)
                return when (val admitted = ManagementPathAdmission.admit(explicitRoot)) {
                    is ManagementPathAdmission.Rejected -> Rejected(admitted.failure)
                    is ManagementPathAdmission.Admitted ->
                        if (admitted.path == canonical) Selected(canonical)
                        else Rejected(ManagementRootFailure.ALTERNATE_INSTALLATION)
                }
            }
        }
    }

    data class Rejected(val failure: ManagementRootFailure) : ManagementRootResolution
}

private sealed interface ManagementPathAdmission {
    data class Admitted(val path: Path) : ManagementPathAdmission

    data class Rejected(val failure: ManagementRootFailure) : ManagementPathAdmission

    companion object {
        fun admit(raw: String): ManagementPathAdmission {
            if (raw.isEmpty()) return Rejected(ManagementRootFailure.EMPTY_PATH)
            val path =
                try {
                    Path.of(raw)
                } catch (_: IllegalArgumentException) {
                    return Rejected(ManagementRootFailure.INVALID_PATH)
                }
            return when {
                !path.isAbsolute -> Rejected(ManagementRootFailure.RELATIVE_PATH)
                path.normalize() != path -> Rejected(ManagementRootFailure.UNNORMALIZED_PATH)
                else -> Admitted(path)
            }
        }
    }
}
