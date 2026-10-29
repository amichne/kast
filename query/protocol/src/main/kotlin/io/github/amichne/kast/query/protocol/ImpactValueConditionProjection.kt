package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCastKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument
import io.github.amichne.kast.relation.contract.ReviewedCallbackResultNonNull
import io.github.amichne.kast.relation.contract.ReviewedGroupKeyRepresentative
import io.github.amichne.kast.relation.contract.ReviewedGroupMembership
import io.github.amichne.kast.relation.contract.ReviewedPredicateAccepts
import io.github.amichne.kast.relation.contract.ValueCastKind
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueConditionOrigin
import io.github.amichne.kast.relation.contract.ValueConditions

internal fun ValueConditions.impactDocument(): ImpactProjected<BoundedProtocolList<ImpactValueConditionDocument>> =
    facts.impactEach { condition ->
        when (condition) {
            is ReviewedGroupMembership -> condition.impactDocument()
            is ReviewedGroupKeyRepresentative -> condition.impactDocument()
            is ReviewedPredicateAccepts -> condition.impactDocument()
            is ReviewedCallbackResultNonNull -> condition.impactDocument()
            is ValueCondition.StringContentNonNull ->
                condition.origin
                    .impactDocument()
                    .impactZip(condition.useRange.impactDocument())
                    .impactZip(condition.entryRange.impactDocument())
                    .impactMap { (origin, entry) ->
                        ImpactValueConditionDocument.StringContentNonNull(origin.first, origin.second, entry)
                    }
            is ValueCondition.DeclaredCallbackResult ->
                condition.origin
                    .impactDocument()
                    .impactZip(condition.useRange.impactDocument())
                    .impactZip(condition.binding.impactDocument())
                    .impactMap { (origin, binding) ->
                        ImpactValueConditionDocument.DeclaredCallbackResult(origin.first, origin.second, binding)
                    }
            is ValueCondition.ReviewedCallbackNormalReturn -> condition.impactDocument()
            is ValueCondition.ReviewedCollectionNormalResult -> condition.impactDocument()
            is ValueCondition.ReviewedMappedKeySurvives -> condition.impactDocument()
            is ValueCondition.ReviewedPreservedKeySurvives -> condition.impactDocument()
            is ValueCondition.ReviewedEntryComponentStable -> condition.impactDocument()
            is ValueCondition.ReviewedEntryPrefixNormal -> condition.impactDocument()
            is ValueCondition.ReviewedEntryKeyUnique -> condition.impactDocument()
            is ValueCondition.StableLocal -> condition.impactDocument()
            is ValueCondition.StableReceiver ->
                condition.origin
                    .impactDocument()
                    .impactZip(condition.useRange.impactDocument())
                    .impactZip(condition.originalType.value.impactText())
                    .impactZip(condition.refinedType.value.impactText())
                    .impactMap { (original, refinedType) ->
                        val (occurrence, originalType) = original
                        ImpactValueConditionDocument.StableReceiver(
                            occurrence.first,
                            occurrence.second,
                            condition.kind.impactDocument(),
                            originalType,
                            refinedType,
                        )
                    }
            is ValueCondition.CastSuccess -> condition.impactDocument()
        }
    }

private fun ValueCondition.ReviewedCallbackNormalReturn.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedCallbackNormalReturn> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(binding.impactDocument()).impactMap {
        (origin, binding) ->
        ImpactValueConditionDocument.ReviewedCallbackNormalReturn(origin.first, origin.second, binding)
    }

private fun ValueCondition.StableLocal.impactDocument(): ImpactProjected<ImpactValueConditionDocument.StableLocal> =
    origin
        .impactDocument()
        .impactZip(useRange.impactDocument())
        .impactZip(version.impactDocument())
        .impactZip(originalType.value.impactText())
        .impactZip(refinedType.value.impactText())
        .impactMap { (original, refinedType) ->
            val (state, originalType) = original
            val (occurrence, version) = state
            ImpactValueConditionDocument.StableLocal(
                occurrence.first,
                occurrence.second,
                version,
                originalType,
                refinedType,
            )
        }

private fun ValueCondition.CastSuccess.impactDocument(): ImpactProjected<ImpactValueConditionDocument.CastSuccess> =
    origin
        .impactDocument()
        .impactZip(useRange.impactDocument())
        .impactZip(originalType.value.impactText())
        .impactZip(targetType.value.impactText())
        .impactMap { (original, targetType) ->
            val (occurrence, originalType) = original
            ImpactValueConditionDocument.CastSuccess(
                occurrence.first,
                occurrence.second,
                when (kind) {
                    ValueCastKind.UNSAFE -> ImpactCastKindDocument.UNSAFE
                    ValueCastKind.SAFE -> ImpactCastKindDocument.SAFE
                },
                originalType,
                targetType,
            )
        }

internal fun ValueConditionOrigin.impactDocument(): ImpactProjected<ImpactValueConditionOriginDocument> =
    execution
        .impactDocument()
        .impactZip(range.impactDocument())
        .impactZip(role.impactDocument())
        .impactZip(valuePath.impactDocument())
        .impactZip(callStack.impactDocument())
        .impactMap { (state, stack) ->
            val (occurrence, path) = state
            val (location, role) = occurrence
            ImpactValueConditionOriginDocument(location.first, location.second, role, path, stack)
        }

private fun ValueCondition.ReviewedCollectionNormalResult.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedCollectionNormalResult> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(binding.impactDocument()).impactMap {
        (origin, binding) ->
        ImpactValueConditionDocument.ReviewedCollectionNormalResult(
            origin.first,
            origin.second,
            binding,
        )
    }

private fun ValueCondition.ReviewedEntryKeyUnique.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedEntryKeyUnique> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(entry.impactDocument()).impactMap {
        (origin, entry) ->
        ImpactValueConditionDocument.ReviewedEntryKeyUnique(origin.first, origin.second, entry)
    }

private fun ValueCondition.ReviewedMappedKeySurvives.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedMappedKeySurvives> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(entry.impactDocument()).impactMap {
        (origin, entry) ->
        ImpactValueConditionDocument.ReviewedMappedKeySurvives(origin.first, origin.second, entry)
    }

private fun ValueCondition.ReviewedPreservedKeySurvives.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedPreservedKeySurvives> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(entry.impactDocument()).impactMap {
        (origin, entry) ->
        ImpactValueConditionDocument.ReviewedPreservedKeySurvives(origin.first, origin.second, entry)
    }

private fun ValueCondition.ReviewedEntryComponentStable.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedEntryComponentStable> =
    origin
        .impactDocument()
        .impactZip(useRange.impactDocument())
        .impactZip(binding.impactDocument())
        .impactZip(entry.impactDocument())
        .impactMap { (origin, entry) ->
            ImpactValueConditionDocument.ReviewedEntryComponentStable(
                origin.first.first,
                origin.first.second,
                origin.second,
                entry,
                slot.impactDocument(),
            )
        }

private fun ValueCondition.ReviewedEntryPrefixNormal.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedEntryPrefixNormal> =
    origin
        .impactDocument()
        .impactZip(useRange.impactDocument())
        .impactZip(binding.impactDocument())
        .impactZip(entry.impactDocument())
        .impactMap { (origin, entry) ->
            ImpactValueConditionDocument.ReviewedEntryPrefixNormal(
                origin.first.first,
                origin.first.second,
                origin.second,
                entry,
                slot.impactDocument(),
            )
        }

private fun ReviewedPredicateAccepts.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedPredicateAccepts> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(binding.impactDocument()).impactMap {
        (origin, binding) ->
        ImpactValueConditionDocument.ReviewedPredicateAccepts(origin.first, origin.second, binding)
    }

private fun ReviewedCallbackResultNonNull.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedCallbackResultNonNull> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(binding.impactDocument()).impactMap {
        (origin, binding) ->
        ImpactValueConditionDocument.ReviewedCallbackResultNonNull(origin.first, origin.second, binding)
    }
