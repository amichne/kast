package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

internal fun RelationEndpoint.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        // Strong authority and opaque imported model belong to the admitted epoch owner, not each detached endpoint.
        g.node(lease) { 0L }
            .saturatedAdd(valueIdentity.storageBytes(g))
            .saturatedAdd(g.node(file) { g.text(file.stableValue) })
            .saturatedAdd(g.text(name.value))
            .saturatedAdd(g.text(compilerIdentity.value))
            .saturatedAdd(g.text(fingerprint.value))
            .saturatedAdd(scope.storageBytes(g))
            .saturatedAdd(constraints.storageBytes(g))
            .saturatedAdd(signature.storageBytes(g))
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(
                g.node(qualifiedIdentity) {
                    when (val identity = qualifiedIdentity) {
                        is ExactDeclarationQualifiedIdentity.Available -> g.text(identity.value)
                        ExactDeclarationQualifiedIdentity.Unavailable -> 0L
                    }
                }
            )
            .saturatedAdd(
                when (this) {
                    is RelationEndpoint.Subject -> g.node(selector) { g.text(selector.fingerprint.value) }
                    is RelationEndpoint.Resolved -> g.node(evidence) { 0L }
                }
            )
    }

internal fun CanonicalCompilerSignature.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(qualifiedIdentity.value)
            .saturatedAdd(
                when (this) {
                    is CanonicalCompilerSignature.Function ->
                        receiver
                            .storageBytes(g)
                            .saturatedAdd(g.collection(contextReceivers) { g.text(it.value) })
                            .saturatedAdd(g.collection(valueParameters) { g.text(it.value) })
                    is CanonicalCompilerSignature.Property ->
                        receiver
                            .storageBytes(g)
                            .saturatedAdd(g.collection(contextReceivers) { g.text(it.value) })
                            .saturatedAdd(g.text(returnType.value))
                    is CanonicalCompilerSignature.ClassLike,
                    is CanonicalCompilerSignature.TypeAlias -> 0L
                }
            )
    }

private fun CanonicalCompilerReceiver.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            CanonicalCompilerReceiver.Absent -> 0L
            is CanonicalCompilerReceiver.Present -> g.text(type.value)
        }
    }

internal fun SymbolSearchScope.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is SymbolSearchScope.ExactFile -> g.text(file.value)
            is SymbolSearchScope.Module -> g.text(module.value)
            is SymbolSearchScope.SourceSet ->
                g.node(project) {
                        g.text(project.buildRoot.value).saturatedAdd(g.text(project.projectPath.value))
                    }
                    .saturatedAdd(g.text(sourceSet.value))
            is SymbolSearchScope.GradleProject ->
                g.node(project) {
                    g.text(project.buildRoot.value).saturatedAdd(g.text(project.projectPath.value))
                }
            is SymbolSearchScope.Workspace -> 0L
        }
    }

internal fun SymbolDiscoveryConstraints.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        (directory?.let { g.node(it) { g.text(it.directory.value) } } ?: 0L)
            .saturatedAdd(packageName?.let { g.node(it) { g.text(it.packageName.value) } } ?: 0L)
            .saturatedAdd(
                declarationKinds?.let { g.node(it) { g.collection(it.values) { kind -> g.node(kind) { 0L } } } } ?: 0L
            )
            .saturatedAdd(sourceSets.storageBytes(g))
    }

private fun SymbolDiscoverySourceSets.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            SymbolDiscoverySourceSets.All -> 0L
            is SymbolDiscoverySourceSets.Exact -> g.collection(values) { g.text(it.value) }
        }
    }

internal fun RelationSearchBoundary.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            RelationSearchBoundary.RETAINED_SUBJECT,
            RelationSearchBoundary.WORKSPACE_EXPANSION -> 0L
            is RelationSearchBoundary.Explicit ->
                scope
                    .storageBytes(g)
                    .saturatedAdd(directory?.let { g.node(it) { g.text(it.directory.value) } } ?: 0L)
                    .saturatedAdd(sourceSets.storageBytes(g))
        }
    }

internal fun ValueSite.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        enclosing
            .storageBytes(g)
            .saturatedAdd(role.storageBytes(g))
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(identity.storageBytes(g))
            .saturatedAdd(g.node(basis) { 0L })
    }

internal fun ValueRole.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is ValueRole.Argument -> call.storageBytes(g)
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> 0L
        }
    }

internal fun ValueInvocation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        enclosing
            .storageBytes(g)
            .saturatedAdd(callable.storageBytes(g))
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(g.node(basis) { 0L })
            .saturatedAdd(
                g.node(identity) {
                    identity.owner
                        .storageBytes(g)
                        .saturatedAdd(identity.callable.storageBytes(g))
                        .saturatedAdd(g.node(identity.basis) { 0L })
                        .saturatedAdd(g.node(identity.range) { 0L })
                }
            )
    }

internal fun ValueSiteIdentity.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        owner
            .storageBytes(g)
            .saturatedAdd(g.node(basis) { 0L })
            .saturatedAdd(role.storageBytes(g))
            .saturatedAdd(g.node(range) { 0L })
    }

private fun ValueDeclarationIdentity.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(compiler.value)
            .saturatedAdd(g.node(file) { g.text(file.stableValue) })
            .saturatedAdd(g.node(range) { 0L })
    }

internal fun ValueTransfer.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source
            .storageBytes(g)
            .saturatedAdd(target.storageBytes(g))
            .saturatedAdd(g.node(evidence) { evidence.retainedBytes })
    }

internal fun ValueFlowStep.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source
            .storageBytes(g)
            .saturatedAdd(domain.storageBytes(g))
            .saturatedAdd(g.receiptStorage(receipts))
            .saturatedAdd(g.collection(transfers) { it.storageBytes(g) })
            .saturatedAdd(g.collection(obligations) { g.node(it) { it.site.storageBytes(g) } })
    }

internal fun RelationRequest.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        subject
            .storageBytes(g)
            .saturatedAdd(boundary.storageBytes(g))
            .saturatedAdd(searchScope.storageBytes(g))
            .saturatedAdd(searchConstraints.storageBytes(g))
            .saturatedAdd(g.text(scopeFingerprint.value))
            .saturatedAdd(g.node(budget) { g.node(budget.resources) { 0L } })
            .saturatedAdd(
                g.node(position) {
                    when (val current = position) {
                        RelationReadPosition.Start -> 0L
                        is RelationReadPosition.Resume ->
                            g.node(current.continuation) {
                                g.node(current.continuation.providerState) {
                                        current.continuation.providerState.retainedBytes
                                    }
                                    .saturatedAdd(
                                        g.collection(current.continuation.retainedLimitations) { g.node(it) { 0L } }
                                    )
                                    .saturatedAdd(g.text(current.continuation.fingerprint.value))
                            }
                    }
                }
            )
    }
