package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerQualifiedIdentity
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationRuntimeType
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** K2 traversal stage at which an exact topology compiler identity failed to round-trip. */
enum class TopologyIdentityStage {
    REFERENCE_TARGET,
    DIRECT_OVERRIDE,
}

/** Whether one file extraction was evaluated in this read epoch or returned from its cache. */
enum class TopologyCacheDisposition {
    COMPUTED,
    REUSED,
}

/** Detached structured compiler projection retained on both sides of an identity comparison. */
data class TopologyCompilerProjectionEvidence private constructor(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: CanonicalCompilerQualifiedIdentity,
    val signature: CanonicalCompilerSignature,
    val identity: CompilerSymbolIdentity,
) {
    companion object {
        /**
         * Proof transition: `CompilerGroundedSymbolEvidence ->
         * TopologyCompilerProjectionEvidence`.
         *
         * Preserves the already-proven kind, qualified identity, structured signature, and
         * derived identity without reopening any raw compiler or PSI boundary.
         */
        fun from(evidence: CompilerGroundedSymbolEvidence): TopologyCompilerProjectionEvidence =
            TopologyCompilerProjectionEvidence(
                evidence.kind,
                evidence.signature.qualifiedIdentity,
                evidence.signature,
                evidence.compilerIdentity,
            )
    }
}

/** Exact structured fields whose retained compiler projections disagree. */
enum class TopologyCompilerProjectionComponent {
    KIND,
    QUALIFIED_IDENTITY,
    SIGNATURE_KIND,
    RECEIVER,
    CONTEXT_RECEIVERS,
    VALUE_PARAMETERS,
    TYPE_PARAMETER_COUNT,
    RETURN_TYPE,
    IDENTITY,
}

/** Non-empty structural difference between two detached compiler projections. */
class TopologyCompilerProjectionDelta private constructor(
    val components: Set<TopologyCompilerProjectionComponent>,
) {
    init {
        check(components.isNotEmpty())
    }

    companion object {
        internal fun different(
            components: Set<TopologyCompilerProjectionComponent>,
        ): TopologyCompilerProjectionDelta = TopologyCompilerProjectionDelta(components)
    }

    override fun equals(other: Any?): Boolean =
        other is TopologyCompilerProjectionDelta && components == other.components

    override fun hashCode(): Int = components.hashCode()

    override fun toString(): String = "TopologyCompilerProjectionDelta(components=$components)"
}

/** Total comparison of two structured compiler projections. */
sealed interface TopologyCompilerProjectionComparison {
    data object Equivalent : TopologyCompilerProjectionComparison

    data class Different internal constructor(
        val delta: TopologyCompilerProjectionDelta,
    ) : TopologyCompilerProjectionComparison

    companion object {
        fun between(
            registry: TopologyCompilerProjectionEvidence,
            live: TopologyCompilerProjectionEvidence,
        ): TopologyCompilerProjectionComparison {
            val components = linkedSetOf<TopologyCompilerProjectionComponent>()
            if (registry.kind != live.kind) {
                components += TopologyCompilerProjectionComponent.KIND
            }
            if (registry.qualifiedIdentity != live.qualifiedIdentity) {
                components += TopologyCompilerProjectionComponent.QUALIFIED_IDENTITY
            }
            compareSignatures(registry.signature, live.signature, components)
            if (registry.identity != live.identity) {
                components += TopologyCompilerProjectionComponent.IDENTITY
            }
            return if (components.isEmpty()) {
                Equivalent
            } else {
                Different(TopologyCompilerProjectionDelta.different(components))
            }
        }
    }
}

enum class TopologyIdentityMismatchEvidenceFailure {
    MATCHING_COMPILER_IDENTITIES,
}

/**
 * Complete detached evidence for one failed registry-to-live K2 identity comparison.
 *
 * The private constructor requires a proven non-empty [delta], so a mismatch cannot be created
 * from equivalent projections. Raw PSI and K2 values remain at the IntelliJ extraction boundary.
 */
data class TopologyIdentityMismatchEvidence private constructor(
    val stage: TopologyIdentityStage,
    val sourceFile: TopologySourceFile,
    val sourceOccurrence: ExactDeclarationTextRange,
    val targetFile: TopologySourceFile,
    val targetDeclarationRange: ExactDeclarationTextRange,
    val registryProjection: TopologyCompilerProjectionEvidence,
    val liveProjection: TopologyCompilerProjectionEvidence,
    val liveSymbolRuntimeType: ExactDeclarationRuntimeType,
    val psiDeclarationRuntimeType: ExactDeclarationRuntimeType,
    val delta: TopologyCompilerProjectionDelta,
) {
    companion object {
        /**
         * Proof transition: exact comparison context plus two compiler projections to either
         * `TopologyIdentityMismatchEvidence` or `MATCHING_COMPILER_IDENTITIES`.
         *
         * Establishes that every diagnostic field belongs to the same failed compiler-identity
         * comparison and that the structural delta is non-empty. The closed matching-identity case
         * never reaches this constructor.
         */
        fun admit(
            stage: TopologyIdentityStage,
            sourceFile: TopologySourceFile,
            sourceOccurrence: ExactDeclarationTextRange,
            targetFile: TopologySourceFile,
            targetDeclarationRange: ExactDeclarationTextRange,
            registryProjection: TopologyCompilerProjectionEvidence,
            liveProjection: TopologyCompilerProjectionEvidence,
            liveSymbolRuntimeType: ExactDeclarationRuntimeType,
            psiDeclarationRuntimeType: ExactDeclarationRuntimeType,
        ): Refinement<
            TopologyIdentityMismatchEvidence,
            TopologyIdentityMismatchEvidenceFailure,
        > {
            if (registryProjection.identity == liveProjection.identity) {
                return Refinement.Rejected(
                    TopologyIdentityMismatchEvidenceFailure.MATCHING_COMPILER_IDENTITIES,
                )
            }
            return when (
                val comparison = TopologyCompilerProjectionComparison.between(
                    registryProjection,
                    liveProjection,
                )
            ) {
                TopologyCompilerProjectionComparison.Equivalent -> Refinement.Rejected(
                    TopologyIdentityMismatchEvidenceFailure.MATCHING_COMPILER_IDENTITIES,
                )
                is TopologyCompilerProjectionComparison.Different -> Refinement.Refined(
                    TopologyIdentityMismatchEvidence(
                        stage,
                        sourceFile,
                        sourceOccurrence,
                        targetFile,
                        targetDeclarationRange,
                        registryProjection,
                        liveProjection,
                        liveSymbolRuntimeType,
                        psiDeclarationRuntimeType,
                        comparison.delta,
                    ),
                )
            }
        }
    }
}

private fun compareSignatures(
    registry: CanonicalCompilerSignature,
    live: CanonicalCompilerSignature,
    components: MutableSet<TopologyCompilerProjectionComponent>,
) {
    when {
        registry is CanonicalCompilerSignature.Function &&
            live is CanonicalCompilerSignature.Function -> {
            compareReceiver(registry.receiver, live.receiver, components)
            if (registry.contextReceivers != live.contextReceivers) {
                components += TopologyCompilerProjectionComponent.CONTEXT_RECEIVERS
            }
            if (registry.valueParameters != live.valueParameters) {
                components += TopologyCompilerProjectionComponent.VALUE_PARAMETERS
            }
            if (registry.typeParameterCount != live.typeParameterCount) {
                components += TopologyCompilerProjectionComponent.TYPE_PARAMETER_COUNT
            }
        }
        registry is CanonicalCompilerSignature.Property &&
            live is CanonicalCompilerSignature.Property -> {
            compareReceiver(registry.receiver, live.receiver, components)
            if (registry.contextReceivers != live.contextReceivers) {
                components += TopologyCompilerProjectionComponent.CONTEXT_RECEIVERS
            }
            if (registry.returnType != live.returnType) {
                components += TopologyCompilerProjectionComponent.RETURN_TYPE
            }
        }
        registry::class != live::class -> {
            components += TopologyCompilerProjectionComponent.SIGNATURE_KIND
        }
    }
}

private fun compareReceiver(
    registry: CanonicalCompilerReceiver,
    live: CanonicalCompilerReceiver,
    components: MutableSet<TopologyCompilerProjectionComponent>,
) {
    if (registry != live) components += TopologyCompilerProjectionComponent.RECEIVER
}
