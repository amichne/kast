@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.relationships

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.CallRelation
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyRelation
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.typeHierarchyDeclaration
import org.jetbrains.kotlin.analysis.api.analyze
import java.util.concurrent.CancellationException
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal fun KastPluginBackend.flattenCallRelations(
        root: io.github.amichne.kast.api.contract.CallNode,
        direction: io.github.amichne.kast.api.contract.CallDirection,
    ): List<CallRelation> {
        data class PendingCall(
            val node: io.github.amichne.kast.api.contract.CallNode,
            val depth: Int,
            val parent: io.github.amichne.kast.api.contract.SymbolIdentity,
        )

        val records = mutableListOf<CallRelation>()
        val rootIdentity = root.symbol.relationshipIdentity()
        val queue = ArrayDeque<PendingCall>()
        root.children.forEach { child -> queue += PendingCall(child, 1, rootIdentity) }
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val related = pending.node.symbol.relationshipIdentity()
            val callSite = pending.node.callSite
                ?: throw continuationConflict("malformedEvidence")
            val containing = if (direction == io.github.amichne.kast.api.contract.CallDirection.INCOMING) {
                related
            } else {
                pending.parent
            }
            records += CallRelation(
                relation = if (direction == io.github.amichne.kast.api.contract.CallDirection.INCOMING) {
                    CallRelation.Kind.CALLER
                } else {
                    CallRelation.Kind.CALLEE
                },
                relatedSymbol = related,
                callSite = callSite,
                depth = pending.depth,
                containingSymbol = ContainingSymbolEvidence.Known(containing),
            )
            pending.node.children.forEach { child ->
                queue += PendingCall(child, pending.depth + 1, related)
            }
        }
        return records
    }

internal fun KastPluginBackend.flattenHierarchyRelations(
        root: TypeHierarchyNode,
        direction: io.github.amichne.kast.api.contract.TypeHierarchyDirection,
    ): List<TypeHierarchyRelation> {
        data class PendingType(val node: TypeHierarchyNode, val depth: Int)

        val records = mutableListOf<TypeHierarchyRelation>()
        val queue = ArrayDeque<PendingType>()
        root.children.forEach { child -> queue += PendingType(child, 1) }
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val identity = pending.node.symbol.relationshipIdentity()
            records += TypeHierarchyRelation(
                relation = when (direction) {
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUPERTYPES ->
                        TypeHierarchyRelation.Kind.SUPERTYPE
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.SUBTYPES ->
                        TypeHierarchyRelation.Kind.SUBTYPE
                    io.github.amichne.kast.api.contract.TypeHierarchyDirection.BOTH ->
                        error("BOTH hierarchy traversal must be split before flattening")
                },
                relatedSymbol = identity,
                declarationLocation = pending.node.symbol.location,
                depth = pending.depth,
            )
            pending.node.children.forEach { child ->
                queue += PendingType(child, pending.depth + 1)
            }
        }
        return records
    }

internal fun KastPluginBackend.relationshipEvidence(
        completion: RelationshipCoverageAuthority.FamilyCompletion,
        knownMinimumCount: Int,
    ): RelationshipResultEvidence {
        val coverage = relationshipCoverageAuthority.assess(completion)
        return when {
            completion == RelationshipCoverageAuthority.FamilyCompletion.COMPLETE &&
                coverage is RelationshipSearchCoverage.Complete -> RelationshipResultEvidence.Complete(
                cardinality = ResultCardinality.Exact(knownMinimumCount),
                coverage = coverage,
            )
            completion == RelationshipCoverageAuthority.FamilyCompletion.RESUMABLE &&
                coverage is RelationshipSearchCoverage.Resumable -> RelationshipResultEvidence.Resumable(
                cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
                coverage = coverage,
            )
            coverage is RelationshipSearchCoverage.Limited -> RelationshipResultEvidence.Limited(
                cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
                coverage = coverage,
            )
            else -> RelationshipResultEvidence.Limited(
                cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
                coverage = RelationshipSearchCoverage.limited(
                    RelationshipSearchLimitation.BACKEND_INCOMPLETE,
                    RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
                ),
            )
        }
    }

internal fun KastPluginBackend.limitedReferenceEvidence(
        knownMinimumCount: Int,
        reason: ReferencePartialReason,
    ): RelationshipResultEvidence.Limited {
        val authorityLimitations = when (
            val coverage = relationshipCoverageAuthority.assess(
                RelationshipCoverageAuthority.FamilyCompletion.INCOMPLETE,
            )
        ) {
            is RelationshipSearchCoverage.Limited -> coverage.limitations
            is RelationshipSearchCoverage.Complete,
            is RelationshipSearchCoverage.Resumable,
            -> listOf(RelationshipSearchLimitation.BACKEND_INCOMPLETE)
        }
        return RelationshipResultEvidence.Limited(
            cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
            coverage = RelationshipSearchCoverage.Limited.from(
                authorityLimitations + reason.limitation +
                    RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
            ),
        )
    }

internal fun KastPluginBackend.completeRelationshipCoverageAdmission(
        selector: KastExactSymbolSelector,
        rootKind: RelationshipRootKind,
        requiredGeneration: Long? = null,
    ): CompleteRelationshipCoverageAdmission {
        if (requiredGeneration != null && psiGeneration() != requiredGeneration) {
            return limitedRelationshipCoverageAdmission(RelationshipSearchLimitation.GENERATION_CHANGED)
        }
        if (!relationshipSelectorMatches(selector, rootKind)) {
            return limitedRelationshipCoverageAdmission(RelationshipSearchLimitation.IDENTITY_UNPROVEN)
        }
        val coverage = relationshipCoverageAuthority.assess(
            RelationshipCoverageAuthority.FamilyCompletion.COMPLETE,
        )
        val generation = psiGeneration()
        if (requiredGeneration != null && generation != requiredGeneration) {
            return limitedRelationshipCoverageAdmission(RelationshipSearchLimitation.GENERATION_CHANGED)
        }
        return when (coverage) {
            is RelationshipSearchCoverage.Complete ->
                CompleteRelationshipCoverageAdmission.Proven(coverage, generation)
            is RelationshipSearchCoverage.Limited ->
                CompleteRelationshipCoverageAdmission.Limited(
                    RelationshipResultEvidence.Limited(
                        cardinality = ResultCardinality.KnownMinimum(0),
                        coverage = coverage,
                    ),
                )
            is RelationshipSearchCoverage.Resumable ->
                CompleteRelationshipCoverageAdmission.Limited(
                    RelationshipResultEvidence.Limited(
                        cardinality = ResultCardinality.KnownMinimum(0),
                        coverage = RelationshipSearchCoverage.limited(
                            RelationshipSearchLimitation.BACKEND_INCOMPLETE,
                            RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
                        ),
                    ),
                )
        }
    }

internal fun KastPluginBackend.relationshipSelectorMatches(
        selector: KastExactSymbolSelector,
        rootKind: RelationshipRootKind,
    ): Boolean = try {
        val selectorPath = NormalizedPath.parse(selector.declarationFile)
        val file = findKtFile(selectorPath.value)
        val resolved = resolveTarget(file, selector.declarationStartOffset)
        val target = when (rootKind) {
            RelationshipRootKind.CALLABLE -> resolved
            RelationshipRootKind.TYPE -> resolved.typeHierarchyDeclaration() ?: resolved
        }
        val symbol = analyze(file) {
            target.toSymbolModel(
                containingDeclaration = compilerContainingDeclarationName(target),
            )
        }
        selector.fqName == symbol.fqName &&
            selectorPath == NormalizedPath.parse(symbol.location.filePath) &&
            selector.declarationStartOffset == symbol.location.startOffset &&
            (selector.kind == null || selector.kind == symbol.kind) &&
            (selector.containingType == null || selector.containingType == symbol.containingDeclaration)
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }

internal fun KastPluginBackend.limitedRelationshipCoverageAdmission(
        limitation: RelationshipSearchLimitation,
    ): CompleteRelationshipCoverageAdmission.Limited = CompleteRelationshipCoverageAdmission.Limited(
        RelationshipResultEvidence.Limited(
            cardinality = ResultCardinality.KnownMinimum(0),
            coverage = RelationshipSearchCoverage.limited(
                limitation,
                RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
            ),
        ),
    )

internal enum class RelationshipRootKind {
        CALLABLE,
        TYPE,
    }

internal sealed interface CompleteRelationshipCoverageAdmission {
        data class Proven(
            val coverage: RelationshipSearchCoverage.Complete,
            val generation: Long,
        ) : CompleteRelationshipCoverageAdmission

        data class Limited(
            val evidence: RelationshipResultEvidence.Limited,
        ) : CompleteRelationshipCoverageAdmission
    }

internal fun KastPluginBackend.continuationConflict(reason: String): ConflictException = ConflictException(
        message = "Relationship traversal could not preserve bounded exact evidence",
        details = mapOf("continuationFailure" to reason),
    )
