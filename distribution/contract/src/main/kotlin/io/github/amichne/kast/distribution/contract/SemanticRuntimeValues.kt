package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private val SHA256_PATTERN = Regex("sha256:[0-9a-f]{64}")
private val SAFE_ENTRY_PATTERN = Regex("[A-Za-z0-9._/-]+")
private val COMPATIBILITY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")

/** Exact SHA-256 identity of a semantic runtime. */
@JvmInline
value class SemanticRuntimeId private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<SemanticRuntimeId, SemanticRuntimeFailure>`.
         *
         * Establishes a canonical lowercase SHA-256 runtime identity. The closed expected failure
         * is [SemanticRuntimeFailure.MANIFEST_INVALID]. Raw text may leave only for endpoint,
         * store-path, wire, and metadata projection boundaries.
         */
        fun parse(raw: String): Refinement<SemanticRuntimeId, SemanticRuntimeFailure> =
            if (SHA256_PATTERN.matches(raw)) {
                Refinement.Refined(SemanticRuntimeId(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }

        /**
         * Proof transition: `canonical identity material -> SemanticRuntimeId`.
         *
         * Establishes the SHA-256 identity of the complete admitted compatibility and artifact
         * tuple. Raw identity material is permitted only inside manifest admission.
         */
        internal fun derive(identityMaterial: String): SemanticRuntimeId = SemanticRuntimeId(
            "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(identityMaterial.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
    }
}

/** Canonical SHA-256 digest for an acquired artifact or embedded plugin. */
@JvmInline
value class RuntimeDigest private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<RuntimeDigest, SemanticRuntimeFailure>`.
         *
         * Establishes a canonical lowercase SHA-256 digest. The closed expected failure is
         * [SemanticRuntimeFailure.MANIFEST_INVALID]. Raw text may leave only at digest comparison
         * and metadata projection boundaries.
         */
        fun parse(raw: String): Refinement<RuntimeDigest, SemanticRuntimeFailure> =
            if (SHA256_PATTERN.matches(raw)) {
                Refinement.Refined(RuntimeDigest(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }
    }
}

/** One admitted relative entry in the immutable runtime layout. */
@JvmInline
value class RuntimeLayoutEntry private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<RuntimeLayoutEntry, SemanticRuntimeFailure>`.
         *
         * Establishes a normalized, relative, non-escaping archive entry. The closed expected
         * failure is [SemanticRuntimeFailure.MANIFEST_INVALID]. Raw text may leave only at archive
         * and installed-layout filesystem boundaries.
         */
        fun parse(raw: String): Refinement<RuntimeLayoutEntry, SemanticRuntimeFailure> {
            val normalized = raw.removeSuffix("/")
            return if (
                normalized.isNotBlank() && SAFE_ENTRY_PATTERN.matches(raw) &&
                !raw.startsWith('/') &&
                normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
            ) {
                Refinement.Refined(RuntimeLayoutEntry(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }
        }
    }
}

@JvmInline
value class RuntimeProductVersion private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<RuntimeProductVersion, SemanticRuntimeFailure>`.
         *
         * Establishes one bounded compatibility token. [SemanticRuntimeFailure.MANIFEST_INVALID]
         * is the closed expected failure. Raw text is permitted only at manifest admission and
         * local version projection.
         */
        fun parse(raw: String): Refinement<RuntimeProductVersion, SemanticRuntimeFailure> =
            if (COMPATIBILITY_ID_PATTERN.matches(raw)) {
                Refinement.Refined(RuntimeProductVersion(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }
    }
}

@JvmInline
value class IntellijBuildIdentity private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<IntellijBuildIdentity, SemanticRuntimeFailure>`.
         *
         * Establishes one bounded IntelliJ build token. The closed expected failure is
         * [SemanticRuntimeFailure.MANIFEST_INVALID]. Raw text is permitted only at manifest and
         * runtime-identity projection boundaries.
         */
        fun parse(raw: String): Refinement<IntellijBuildIdentity, SemanticRuntimeFailure> =
            if (COMPATIBILITY_ID_PATTERN.matches(raw)) {
                Refinement.Refined(IntellijBuildIdentity(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }
    }
}

@JvmInline
value class KotlinPluginBuildIdentity private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<KotlinPluginBuildIdentity,
         * SemanticRuntimeFailure>`.
         *
         * Establishes one bounded Kotlin plugin build token. The closed expected failure is
         * [SemanticRuntimeFailure.MANIFEST_INVALID]. Raw text is permitted only at manifest and
         * runtime-identity projection boundaries.
         */
        fun parse(raw: String): Refinement<KotlinPluginBuildIdentity, SemanticRuntimeFailure> =
            if (COMPATIBILITY_ID_PATTERN.matches(raw)) {
                Refinement.Refined(KotlinPluginBuildIdentity(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }
    }
}

@JvmInline
value class RuntimeWireSchemaIdentity private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<RuntimeWireSchemaIdentity,
         * SemanticRuntimeFailure>`.
         *
         * Establishes one bounded global wire-schema token. The closed expected failure is
         * [SemanticRuntimeFailure.MANIFEST_INVALID]. Raw text is permitted only at manifest,
         * identity, and schema projection boundaries.
         */
        fun parse(raw: String): Refinement<RuntimeWireSchemaIdentity, SemanticRuntimeFailure> =
            if (COMPATIBILITY_ID_PATTERN.matches(raw)) {
                Refinement.Refined(RuntimeWireSchemaIdentity(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }
    }
}

@JvmInline
value class RuntimeArchiveSize private constructor(val bytes: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<RuntimeArchiveSize, SemanticRuntimeFailure>`.
         *
         * Establishes a strictly positive expected archive byte count. The closed expected failure
         * is [SemanticRuntimeFailure.MANIFEST_INVALID]. Raw bytes may leave only for acquisition
         * and expansion-bound comparisons.
         */
        fun parse(raw: Long): Refinement<RuntimeArchiveSize, SemanticRuntimeFailure> =
            if (raw > 0) {
                Refinement.Refined(RuntimeArchiveSize(raw))
            } else {
                Refinement.Rejected(SemanticRuntimeFailure.MANIFEST_INVALID)
            }
    }
}

/** Canonical manifest JSON produced only from an admitted manifest document. */
@JvmInline
value class CanonicalRuntimeManifestJson internal constructor(val value: String)

enum class RuntimePlatform(val wireValue: String) { MACOS("macos") }
enum class RuntimeArchitecture(val wireValue: String) { AARCH64("aarch64") }
