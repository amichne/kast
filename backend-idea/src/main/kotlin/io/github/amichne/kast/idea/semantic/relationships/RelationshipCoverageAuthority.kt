package io.github.amichne.kast.idea

import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation

internal fun interface RelationshipCoverageAuthority {
    fun assess(completion: FamilyCompletion): RelationshipSearchCoverage

    fun assess(
        completion: FamilyCompletion,
        declarationFile: String,
    ): RelationshipSearchCoverage = assess(completion)

    fun assess(
        completion: FamilyCompletion,
        searchScope: GlobalSearchScope,
    ): RelationshipSearchCoverage = assess(completion)

    enum class FamilyCompletion {
        COMPLETE,
        RESUMABLE,
        INCOMPLETE,
    }

    companion object {
        fun proven(): RelationshipCoverageAuthority = RelationshipCoverageAuthority { completion ->
            when (completion) {
                FamilyCompletion.COMPLETE -> RelationshipSearchCoverage.complete()
                FamilyCompletion.RESUMABLE -> RelationshipSearchCoverage.resumable()
                FamilyCompletion.INCOMPLETE -> RelationshipSearchCoverage.limited(
                    RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
                )
            }
        }
    }
}
