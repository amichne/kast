package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import kotlin.ConsistentCopyVisibility
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RelationshipSearchCoverage {
    val identity: RelationshipCoverageStatus
    val projectScope: RelationshipCoverageStatus
    val sourceSetScope: RelationshipCoverageStatus
    val indexFreshness: RelationshipCoverageStatus
    val backend: RelationshipCoverageStatus
    val requestedFamily: RelationshipCoverageStatus
    val limitations: List<RelationshipSearchLimitation>

    @Serializable
    @SerialName("COMPLETE")
    @ConsistentCopyVisibility
    data class Complete private constructor(
        @DocField(description = "Whether the exact subject identity was proven.")
        override val identity: RelationshipCoverageStatus,
        @DocField(description = "Whether every project in the requested relationship scope was covered.")
        override val projectScope: RelationshipCoverageStatus,
        @DocField(description = "Whether every applicable source set was covered.")
        override val sourceSetScope: RelationshipCoverageStatus,
        @DocField(description = "Whether relationship indexes were ready and current.")
        override val indexFreshness: RelationshipCoverageStatus,
        @DocField(description = "Whether the semantic backend completed its required work.")
        override val backend: RelationshipCoverageStatus,
        @DocField(description = "Whether the requested relationship family completed.")
        override val requestedFamily: RelationshipCoverageStatus,
        @DocField(description = "Canonical reasons that qualify relationship completeness.")
        override val limitations: List<RelationshipSearchLimitation>,
    ) : RelationshipSearchCoverage {
        init {
            require(
                listOf(
                    identity,
                    projectScope,
                    sourceSetScope,
                    indexFreshness,
                    backend,
                    requestedFamily,
                ).all { status -> status == RelationshipCoverageStatus.COMPLETE },
            ) { "Complete relationship coverage requires every dimension to be complete" }
            require(limitations.isEmpty()) {
                "Complete relationship coverage cannot carry limitations"
            }
        }

        companion object {
            fun proven(): Complete = Complete(
                identity = RelationshipCoverageStatus.COMPLETE,
                projectScope = RelationshipCoverageStatus.COMPLETE,
                sourceSetScope = RelationshipCoverageStatus.COMPLETE,
                indexFreshness = RelationshipCoverageStatus.COMPLETE,
                backend = RelationshipCoverageStatus.COMPLETE,
                requestedFamily = RelationshipCoverageStatus.COMPLETE,
                limitations = emptyList(),
            )
        }
    }

    @Serializable
    @SerialName("RESUMABLE")
    @ConsistentCopyVisibility
    data class Resumable private constructor(
        @DocField(description = "Whether the exact subject identity was proven.")
        override val identity: RelationshipCoverageStatus,
        @DocField(description = "Whether every project in the requested relationship scope was covered.")
        override val projectScope: RelationshipCoverageStatus,
        @DocField(description = "Whether every applicable source set was covered.")
        override val sourceSetScope: RelationshipCoverageStatus,
        @DocField(description = "Whether relationship indexes were ready and current.")
        override val indexFreshness: RelationshipCoverageStatus,
        @DocField(description = "Whether the semantic backend completed its required work.")
        override val backend: RelationshipCoverageStatus,
        @DocField(description = "Whether the requested relationship family completed.")
        override val requestedFamily: RelationshipCoverageStatus,
        @DocField(description = "Canonical reasons that qualify relationship completeness.")
        override val limitations: List<RelationshipSearchLimitation>,
    ) : RelationshipSearchCoverage {
        init {
            require(
                listOf(identity, projectScope, sourceSetScope, indexFreshness, backend)
                    .all { status -> status == RelationshipCoverageStatus.COMPLETE },
            ) { "Resumable relationship coverage requires complete boundary dimensions" }
            require(requestedFamily == RelationshipCoverageStatus.IN_PROGRESS) {
                "Resumable relationship coverage requires an in-progress family search"
            }
            require(limitations == listOf(RelationshipSearchLimitation.FAMILY_SEARCH_IN_PROGRESS)) {
                "Resumable relationship coverage requires only its in-progress limitation"
            }
        }

        companion object {
            fun retained(): Resumable = Resumable(
                identity = RelationshipCoverageStatus.COMPLETE,
                projectScope = RelationshipCoverageStatus.COMPLETE,
                sourceSetScope = RelationshipCoverageStatus.COMPLETE,
                indexFreshness = RelationshipCoverageStatus.COMPLETE,
                backend = RelationshipCoverageStatus.COMPLETE,
                requestedFamily = RelationshipCoverageStatus.IN_PROGRESS,
                limitations = listOf(RelationshipSearchLimitation.FAMILY_SEARCH_IN_PROGRESS),
            )
        }
    }

    @Serializable
    @SerialName("LIMITED")
    @ConsistentCopyVisibility
    data class Limited private constructor(
        @DocField(description = "Whether the exact subject identity was proven.")
        override val identity: RelationshipCoverageStatus,
        @DocField(description = "Whether every project in the requested relationship scope was covered.")
        override val projectScope: RelationshipCoverageStatus,
        @DocField(description = "Whether every applicable source set was covered.")
        override val sourceSetScope: RelationshipCoverageStatus,
        @DocField(description = "Whether relationship indexes were ready and current.")
        override val indexFreshness: RelationshipCoverageStatus,
        @DocField(description = "Whether the semantic backend completed its required work.")
        override val backend: RelationshipCoverageStatus,
        @DocField(description = "Whether the requested relationship family completed.")
        override val requestedFamily: RelationshipCoverageStatus,
        @DocField(description = "Canonical reasons that qualify relationship completeness.")
        override val limitations: List<RelationshipSearchLimitation>,
    ) : RelationshipSearchCoverage {
        init {
            require(limitations.isNotEmpty()) {
                "Limited relationship coverage requires at least one limitation"
            }
            require(limitations == limitations.distinct().sortedBy(RelationshipSearchLimitation::ordinal)) {
                "Relationship limitations must be unique and canonical"
            }
            require(
                CoverageFacts.from(limitations) == CoverageFacts(
                    identity,
                    projectScope,
                    sourceSetScope,
                    indexFreshness,
                    backend,
                    requestedFamily,
                ),
            ) { "Limited relationship coverage facts must agree with their limitations" }
        }

        companion object {
            fun from(limitations: Collection<RelationshipSearchLimitation>): Limited {
                val canonical = limitations.distinct().sortedBy(RelationshipSearchLimitation::ordinal)
                val facts = CoverageFacts.from(canonical)
                return Limited(
                    identity = facts.identity,
                    projectScope = facts.projectScope,
                    sourceSetScope = facts.sourceSetScope,
                    indexFreshness = facts.indexFreshness,
                    backend = facts.backend,
                    requestedFamily = facts.requestedFamily,
                    limitations = canonical,
                )
            }
        }
    }

    companion object {
        fun complete(): Complete = Complete.proven()

        fun resumable(): Resumable = Resumable.retained()

        fun limited(
            first: RelationshipSearchLimitation,
            vararg additional: RelationshipSearchLimitation,
        ): Limited = Limited.from(listOf(first) + additional)
    }

    private data class CoverageFacts(
        val identity: RelationshipCoverageStatus,
        val projectScope: RelationshipCoverageStatus,
        val sourceSetScope: RelationshipCoverageStatus,
        val indexFreshness: RelationshipCoverageStatus,
        val backend: RelationshipCoverageStatus,
        val requestedFamily: RelationshipCoverageStatus,
    ) {
        companion object {
            fun from(limitations: Collection<RelationshipSearchLimitation>): CoverageFacts = CoverageFacts(
                identity = if (RelationshipSearchLimitation.IDENTITY_UNPROVEN in limitations) {
                    RelationshipCoverageStatus.UNAVAILABLE
                } else {
                    RelationshipCoverageStatus.COMPLETE
                },
                projectScope = if (RelationshipSearchLimitation.PROJECT_SCOPE_INCOMPLETE in limitations) {
                    RelationshipCoverageStatus.PARTIAL
                } else {
                    RelationshipCoverageStatus.COMPLETE
                },
                sourceSetScope = when {
                    RelationshipSearchLimitation.SOURCE_SET_EXCLUDED in limitations ->
                        RelationshipCoverageStatus.EXCLUDED
                    RelationshipSearchLimitation.SOURCE_SET_SCOPE_INCOMPLETE in limitations ->
                        RelationshipCoverageStatus.PARTIAL
                    else -> RelationshipCoverageStatus.COMPLETE
                },
                indexFreshness = when {
                    RelationshipSearchLimitation.INDEX_STALE in limitations ||
                        RelationshipSearchLimitation.GENERATION_CHANGED in limitations ->
                        RelationshipCoverageStatus.STALE
                    RelationshipSearchLimitation.INDEX_NOT_READY in limitations ->
                        RelationshipCoverageStatus.IN_PROGRESS
                    else -> RelationshipCoverageStatus.COMPLETE
                },
                backend = when {
                    RelationshipSearchLimitation.CANCELLED in limitations ->
                        RelationshipCoverageStatus.CANCELLED
                    limitations.any { limitation ->
                        limitation in setOf(
                            RelationshipSearchLimitation.BACKEND_UNAVAILABLE,
                            RelationshipSearchLimitation.TRAVERSAL_STATE_BUDGET_REACHED,
                            RelationshipSearchLimitation.CONTINUATION_EXPIRED,
                            RelationshipSearchLimitation.CONTINUATION_INVALID,
                        )
                    } -> RelationshipCoverageStatus.UNAVAILABLE
                    RelationshipSearchLimitation.BACKEND_INCOMPLETE in limitations ->
                        RelationshipCoverageStatus.PARTIAL
                    else -> RelationshipCoverageStatus.COMPLETE
                },
                requestedFamily = when {
                    RelationshipSearchLimitation.TIMED_OUT in limitations ->
                        RelationshipCoverageStatus.TIMED_OUT
                    RelationshipSearchLimitation.CANCELLED in limitations ->
                        RelationshipCoverageStatus.CANCELLED
                    RelationshipSearchLimitation.FAMILY_SEARCH_IN_PROGRESS in limitations ->
                        RelationshipCoverageStatus.IN_PROGRESS
                    limitations.any { limitation ->
                        limitation in setOf(
                            RelationshipSearchLimitation.INDEX_NOT_READY,
                            RelationshipSearchLimitation.BACKEND_UNAVAILABLE,
                            RelationshipSearchLimitation.CONTINUATION_EXPIRED,
                            RelationshipSearchLimitation.CONTINUATION_INVALID,
                        )
                    } -> RelationshipCoverageStatus.UNAVAILABLE
                    else -> RelationshipCoverageStatus.PARTIAL
                },
            )
        }
    }
}
