package io.github.amichne.kast.distribution.contract

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** The 16-hex per-host coordinator directory name: a prefix of SHA-256 over the selected Codex home path. */
@JvmInline
value class CoordinatorHostProfile private constructor(val value: String) {
    companion object {
        private const val PROFILE_CHARACTERS = 16

        fun of(codexHome: String): CoordinatorHostProfile =
            CoordinatorHostProfile(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(codexHome.toByteArray(StandardCharsets.UTF_8))
                    )
                    .take(PROFILE_CHARACTERS)
            )
    }
}

/** The 64-hex workspace overlay directory name: SHA-256 over the workspace root path. */
@JvmInline
value class WorkspaceOverlayIdentity private constructor(val value: String) {
    companion object {
        fun of(workspaceRoot: Path): WorkspaceOverlayIdentity =
            WorkspaceOverlayIdentity(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(workspaceRoot.toString().toByteArray(StandardCharsets.UTF_8))
                    )
            )
    }
}
