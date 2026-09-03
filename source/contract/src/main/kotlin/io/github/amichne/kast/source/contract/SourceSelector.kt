package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val SOURCE_SELECTOR_FINGERPRINT_HEX_LENGTH = 64
private const val SOURCE_SELECTOR_HEX_RADIX = 16
private const val MAX_SOURCE_ENTITY_NAME_LENGTH = 512

/** Closed structural region meanings that one exact source selector may identify. */
enum class SourceRegionKind {
    ANCHOR,
    DECLARATION,
    CALLABLE_BODY,
    CLASS_BODY,
    FILE,
    WINDOW,
}

/** Closed structural entity meanings that one exact source selector may identify. */
enum class SourceEntityKind {
    DECLARATION_CLASSLIKE,
    DECLARATION_CONSTRUCTOR,
    DECLARATION_FUNCTION,
    DECLARATION_PROPERTY,
    DECLARATION_TYPE_ALIAS,
    VALUE_PARAMETER,
    CALL,
    CALLEE,
    REFERENCE,
}

enum class SourceEntityNameFailure {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
}

/** Explicit source-name availability retained in an entity selector fingerprint. */
sealed interface SourceEntityName {
    data object Unavailable : SourceEntityName

    @JvmInline
    value class Present internal constructor(
        val value: String,
    ) : SourceEntityName

    companion object {
        /**
         * Proof transition: `String -> Refinement<SourceEntityName.Present,
         * SourceEntityNameFailure>`.
         */
        fun present(
            raw: String,
        ): Refinement<Present, SourceEntityNameFailure> = when {
            raw.isBlank() -> Refinement.Rejected(SourceEntityNameFailure.BLANK)
            raw.length > MAX_SOURCE_ENTITY_NAME_LENGTH ->
                Refinement.Rejected(SourceEntityNameFailure.TOO_LONG)
            raw.any(Char::isISOControl) ->
                Refinement.Rejected(SourceEntityNameFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(Present(raw))
        }
    }
}

enum class SourceSelectorFingerprintFailure {
    INVALID_FORMAT,
}

/** Canonical lowercase SHA-256 identity of one complete source selection. */
@JvmInline
value class SourceSelectorFingerprint private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<SourceSelectorFingerprint,
         * SourceSelectorFingerprintFailure>`.
         */
        fun parse(
            raw: String,
        ): Refinement<SourceSelectorFingerprint, SourceSelectorFingerprintFailure> =
            if (
                raw.length == SOURCE_SELECTOR_FINGERPRINT_HEX_LENGTH &&
                raw.all { character -> character in '0'..'9' || character in 'a'..'f' }
            ) {
                Refinement.Refined(SourceSelectorFingerprint(raw))
            } else {
                Refinement.Rejected(SourceSelectorFingerprintFailure.INVALID_FORMAT)
            }
    }
}

enum class SourceSelectorIssueFailure {
    SNAPSHOT_MISMATCH,
    OUTSIDE_PARENT,
    FINGERPRINT_MISMATCH,
}

/** Exact structural source authority bound to one committed document snapshot. */
sealed interface SourceSelector {
    val snapshot: SourceSnapshot
    val range: SourceRange
    val fingerprint: SourceSelectorFingerprint

    class RootRegion internal constructor(
        override val range: SourceRange,
        val kind: SourceRegionKind,
        override val fingerprint: SourceSelectorFingerprint,
    ) : SourceSelector {
        override val snapshot: SourceSnapshot
            get() = range.snapshot
    }

    class NestedRegion internal constructor(
        override val range: SourceRange,
        val kind: SourceRegionKind,
        val parent: SourceSelector,
        override val fingerprint: SourceSelectorFingerprint,
    ) : SourceSelector {
        override val snapshot: SourceSnapshot
            get() = range.snapshot
    }

    class Entity internal constructor(
        override val range: SourceRange,
        val kind: SourceEntityKind,
        val name: SourceEntityName,
        val parent: SourceSelector,
        override val fingerprint: SourceSelectorFingerprint,
    ) : SourceSelector {
        override val snapshot: SourceSnapshot
            get() = range.snapshot
    }

    companion object {
        /** Issues a root region from an already snapshot-bounded interval. */
        fun issueRoot(
            range: SourceRange,
            kind: SourceRegionKind,
        ): RootRegion = RootRegion(
            range = range,
            kind = kind,
            fingerprint = sourceSelectorFingerprint(
                range = range,
                variant = "root-region",
                structuralKind = kind.name,
                parent = null,
                name = SourceEntityName.Unavailable,
            ),
        )

        /**
         * Proof transition: `(SourceSelector parent, SourceRange, SourceRegionKind) ->
         * Refinement<SourceSelector.NestedRegion, SourceSelectorIssueFailure>`.
         *
         * Establishes identical snapshot ownership and complete containment by the exact parent.
         */
        fun issueNested(
            parent: SourceSelector,
            range: SourceRange,
            kind: SourceRegionKind,
        ): Refinement<NestedRegion, SourceSelectorIssueFailure> =
            admitChild(parent, range) {
                NestedRegion(
                    range = range,
                    kind = kind,
                    parent = parent,
                    fingerprint = sourceSelectorFingerprint(
                        range = range,
                        variant = "nested-region",
                        structuralKind = kind.name,
                        parent = parent,
                        name = SourceEntityName.Unavailable,
                    ),
                )
            }

        /**
         * Proof transition: `(SourceSelector parent, NonEmptySourceRange, SourceEntityKind,
         * SourceEntityName) -> Refinement<SourceSelector.Entity, SourceSelectorIssueFailure>`.
         */
        fun issueEntity(
            parent: SourceSelector,
            range: NonEmptySourceRange,
            kind: SourceEntityKind,
            name: SourceEntityName,
        ): Refinement<Entity, SourceSelectorIssueFailure> =
            admitChild(parent, range.range) {
                Entity(
                    range = range.range,
                    kind = kind,
                    name = name,
                    parent = parent,
                    fingerprint = sourceSelectorFingerprint(
                        range = range.range,
                        variant = "entity",
                        structuralKind = kind.name,
                        parent = parent,
                        name = name,
                    ),
                )
            }

        /** Restores root authority only when every decoded fact reproduces its fingerprint. */
        fun restoreRoot(
            range: SourceRange,
            kind: SourceRegionKind,
            fingerprint: SourceSelectorFingerprint,
        ): Refinement<RootRegion, SourceSelectorIssueFailure> {
            val issued = issueRoot(range, kind)
            return if (issued.fingerprint == fingerprint) {
                Refinement.Refined(issued)
            } else {
                Refinement.Rejected(SourceSelectorIssueFailure.FINGERPRINT_MISMATCH)
            }
        }

        /** Restores nested authority only under the exact encoded parent. */
        fun restoreNested(
            parent: SourceSelector,
            range: SourceRange,
            kind: SourceRegionKind,
            fingerprint: SourceSelectorFingerprint,
        ): Refinement<NestedRegion, SourceSelectorIssueFailure> = when (
            val issued = issueNested(parent, range, kind)
        ) {
            is Refinement.Rejected -> issued
            is Refinement.Refined -> if (issued.value.fingerprint == fingerprint) {
                issued
            } else {
                Refinement.Rejected(SourceSelectorIssueFailure.FINGERPRINT_MISMATCH)
            }
        }

        /** Restores entity authority only under the exact encoded parent and entity identity. */
        fun restoreEntity(
            parent: SourceSelector,
            range: NonEmptySourceRange,
            kind: SourceEntityKind,
            name: SourceEntityName,
            fingerprint: SourceSelectorFingerprint,
        ): Refinement<Entity, SourceSelectorIssueFailure> = when (
            val issued = issueEntity(parent, range, kind, name)
        ) {
            is Refinement.Rejected -> issued
            is Refinement.Refined -> if (issued.value.fingerprint == fingerprint) {
                issued
            } else {
                Refinement.Rejected(SourceSelectorIssueFailure.FINGERPRINT_MISMATCH)
            }
        }

        private inline fun <Selection : SourceSelector> admitChild(
            parent: SourceSelector,
            range: SourceRange,
            issue: () -> Selection,
        ): Refinement<Selection, SourceSelectorIssueFailure> = when {
            range.snapshot != parent.snapshot ->
                Refinement.Rejected(SourceSelectorIssueFailure.SNAPSHOT_MISMATCH)
            range.startInclusive < parent.range.startInclusive ||
                range.endExclusive > parent.range.endExclusive ->
                Refinement.Rejected(SourceSelectorIssueFailure.OUTSIDE_PARENT)
            else -> Refinement.Refined(issue())
        }
    }
}

enum class SourceSelectorRevalidationFailure {
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SOURCE_STATE_MISMATCH,
    SOURCE_FILE_MISMATCH,
    DOCUMENT_IDENTITY_MISMATCH,
    DOCUMENT_LENGTH_MISMATCH,
}

/** Proof that one issued source selector still addresses the identical current snapshot. */
class RevalidatedSourceSelector private constructor(
    val selector: SourceSelector,
) {
    companion object {
        /**
         * Proof transition: `(SourceSelector, SourceSnapshot) -> Refinement<
         * RevalidatedSourceSelector, SourceSelectorRevalidationFailure>`.
         *
         * Compares every independently moving snapshot identity and fails closed without shifting
         * a range or recovering nearby text.
         */
        fun validate(
            selector: SourceSelector,
            current: SourceSnapshot,
        ): Refinement<RevalidatedSourceSelector, SourceSelectorRevalidationFailure> {
            val issued = selector.snapshot
            val failure = when {
                issued.lease.workspaceRoot != current.lease.workspaceRoot ->
                    SourceSelectorRevalidationFailure.WORKSPACE_ROOT_MISMATCH
                issued.lease.generation != current.lease.generation ->
                    SourceSelectorRevalidationFailure.STALE_GENERATION
                issued.sourceState != current.sourceState ->
                    SourceSelectorRevalidationFailure.SOURCE_STATE_MISMATCH
                issued.file != current.file ->
                    SourceSelectorRevalidationFailure.SOURCE_FILE_MISMATCH
                issued.textIdentity != current.textIdentity ->
                    SourceSelectorRevalidationFailure.DOCUMENT_IDENTITY_MISMATCH
                issued.length != current.length ->
                    SourceSelectorRevalidationFailure.DOCUMENT_LENGTH_MISMATCH
                else -> null
            }
            return if (failure == null) {
                Refinement.Refined(RevalidatedSourceSelector(selector))
            } else {
                Refinement.Rejected(failure)
            }
        }
    }
}

private fun sourceSelectorFingerprint(
    range: SourceRange,
    variant: String,
    structuralKind: String,
    parent: SourceSelector?,
    name: SourceEntityName,
): SourceSelectorFingerprint {
    val snapshot = range.snapshot
    val canonical = buildString {
        appendSelectorField(snapshot.lease.workspaceRoot.value)
        appendSelectorField(snapshot.lease.generation.value.toString())
        appendSelectorField(snapshot.sourceState.value)
        appendSelectorField(snapshot.file.path.value)
        appendSelectorField(snapshot.textIdentity.value)
        appendSelectorField(snapshot.length.value.toString())
        appendSelectorField(variant)
        appendSelectorField(structuralKind)
        appendSelectorField(range.startInclusive.value.toString())
        appendSelectorField(range.endExclusive.value.toString())
        appendSelectorField(parent?.fingerprint?.value.orEmpty())
        appendSelectorField(
            when (name) {
                SourceEntityName.Unavailable -> "unavailable"
                is SourceEntityName.Present -> "present:${name.value}"
            },
        )
    }
    val raw = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(SOURCE_SELECTOR_HEX_RADIX).padStart(2, '0')
        }
    return when (val parsed = SourceSelectorFingerprint.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("SHA-256 must produce a canonical selector fingerprint")
    }
}

private fun StringBuilder.appendSelectorField(value: String) {
    append(value.length)
    append(':')
    append(value)
    append(';')
}
