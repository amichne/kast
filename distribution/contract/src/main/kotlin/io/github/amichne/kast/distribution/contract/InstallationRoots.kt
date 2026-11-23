package io.github.amichne.kast.distribution.contract

import java.nio.file.Path

/** Why [InstallationLayout.admit] selected no version root. */
enum class InstallationLayoutFailure {
    /** The selected version entry is absent, a symbolic link, not a directory, or unobservable. */
    VERSION_UNAVAILABLE,

    /** The selected version entry resolves to a path other than its place beneath the physical management root. */
    VERSION_DISPLACED,

    /**
     * The code source lies in a version root that the management root no longer selects. The current layout selects the
     * fixed entry `R/installation` and never yields this failure; stage 8 of the lifecycle design yields it when
     * `R/current` names another version.
     */
    STALE_VERSION,
}

/**
 * `S`: the share directory, `HOME/.local/share`. It holds the management root, the lifecycle lock, and the workspace
 * and runtime-base stores beside the management root.
 */
@JvmInline
value class ShareRoot private constructor(val path: Path) {
    /** The lifecycle lock and the stores, derived from this share directory. */
    val share: InstallationLayout.Share
        get() = InstallationLayout.Share(path)

    companion object {
        /** The share directory at [path], as admitted by the caller. */
        fun of(path: Path): ShareRoot = ShareRoot(path)
    }
}

/**
 * `R`: the management root, `HOME/.local/share/kast`. It holds the lifecycle records, the stores beside it, and the
 * version root.
 */
@JvmInline
value class ManagementRoot private constructor(val path: Path) {
    /** Lifecycle records, derived from this management root. */
    val management: InstallationLayout.Management
        get() = InstallationLayout.Management(path)

    /**
     * `S`: the share directory that holds this management root. In the current and the target layout it is `R`'s
     * parent.
     */
    val shareRoot: ShareRoot
        get() = ShareRoot.of(requireNotNull(path.parent) { "management root has no share directory: $path" })

    /** The lifecycle lock and the stores beside this management root. */
    val share: InstallationLayout.Share
        get() = shareRoot.share

    companion object {
        /** The management root at [path], as admitted by the caller. */
        fun of(path: Path): ManagementRoot = ManagementRoot(path)
    }
}

/** `V`: the version root that holds one release payload. In the current layout it is `R/installation`. */
@JvmInline value class VersionRoot internal constructor(val path: Path)
