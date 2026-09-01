package io.github.amichne.kast.cli

internal const val RUNTIME_PROCESS_MODE_ENVIRONMENT = "KAST_ENABLE_LAUNCHD"

/** Closed installed-process policy for the sidecar's outer lifecycle authority. */
internal sealed interface RuntimeProcessMode {
    data object Direct : RuntimeProcessMode
    data object Launchd : RuntimeProcessMode
}

internal enum class RuntimeProcessModeFailure {
    INVALID_ENVIRONMENT_VALUE,
}

internal sealed interface RuntimeProcessModeAdmission {
    data class Admitted(
        val mode: RuntimeProcessMode,
    ) : RuntimeProcessModeAdmission

    data class Rejected(
        val failure: RuntimeProcessModeFailure,
    ) : RuntimeProcessModeAdmission
}

/** Refines the optional launchd flag before either process effect can be selected. */
internal object RuntimeProcessModeEnvironment {
    /**
     * Proof transition: `KAST_ENABLE_LAUNCHD? -> RuntimeProcessModeAdmission`.
     *
     * Absence and `0` establish direct launch. Only `1` establishes launchd authority. Every
     * other representation remains a closed failure instead of being interpreted heuristically.
     */
    fun admit(configured: String?): RuntimeProcessModeAdmission = when (configured) {
        null, "0" -> RuntimeProcessModeAdmission.Admitted(RuntimeProcessMode.Direct)
        "1" -> RuntimeProcessModeAdmission.Admitted(RuntimeProcessMode.Launchd)
        else -> RuntimeProcessModeAdmission.Rejected(
            RuntimeProcessModeFailure.INVALID_ENVIRONMENT_VALUE,
        )
    }
}

/** Matched process-start and lifecycle-observation capabilities for one admitted mode. */
internal data class RuntimeProcessCapabilities(
    val starter: RuntimeProcessStarter,
    val authority: RuntimeProcessAuthority,
)

internal fun RuntimeProcessMode.capabilities(): RuntimeProcessCapabilities = when (this) {
    RuntimeProcessMode.Direct -> RuntimeProcessCapabilities(
        JdkRuntimeProcessStarter,
        JdkRuntimeProcessAuthority,
    )
    RuntimeProcessMode.Launchd -> RuntimeProcessCapabilities(
        LaunchdRuntimeProcessStarter,
        LaunchdRuntimeProcessAuthority,
    )
}
