package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.PermanentIdentityFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CanonicalOperationResolution
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import io.github.amichne.kast.protocol.registry.HostedVariants
import kotlinx.serialization.Serializable

@Serializable
data class HostedCapabilityCandidate(
    val operationId: String,
    val intents: List<String>,
)

enum class HostedCapabilityIntent(val identity: String) {
    ADD_DECLARATION("add-declaration"),
}

data class HostedCapability private constructor(
    val operation: CanonicalOperation,
    val intents: Set<HostedCapabilityIntent>,
) {
    internal fun candidate(): HostedCapabilityCandidate = HostedCapabilityCandidate(
        operation.id.value,
        intents.sortedBy(HostedCapabilityIntent::ordinal).map(HostedCapabilityIntent::identity),
    )

    companion object {
        internal fun create(
            operation: CanonicalOperation,
            intents: Set<HostedCapabilityIntent>,
        ): HostedCapability = HostedCapability(operation, intents)
    }
}

sealed interface HostedCapabilitySetAdmission {
    data class Admitted(val capabilities: HostedCapabilitySet) : HostedCapabilitySetAdmission
    data class Rejected(val failure: HostedCapabilitySetFailure) : HostedCapabilitySetAdmission
}

sealed interface HostedCapabilitySetFailure {
    data class MalformedOperationId(
        val failure: PermanentIdentityFailure,
    ) : HostedCapabilitySetFailure

    data class UnknownOperation(
        val operationId: OperationId,
    ) : HostedCapabilitySetFailure

    data class UnsupportedIntent(
        val operation: CanonicalOperation,
    ) : HostedCapabilitySetFailure

    data class DuplicateOperation(
        val operation: CanonicalOperation,
    ) : HostedCapabilitySetFailure

    data class DuplicateIntent(
        val operation: CanonicalOperation,
        val intent: HostedCapabilityIntent,
    ) : HostedCapabilitySetFailure

    data object CanonicalProjectionMismatch : HostedCapabilitySetFailure
}

/** Exact generated hosted capability descriptor retained by an admitted endpoint. */
class HostedCapabilitySet private constructor(
    val capabilities: List<HostedCapability>,
) {
    fun supports(operation: CanonicalOperation): Boolean =
        capabilities.any { it.operation == operation }

    fun supports(
        operation: CanonicalOperation,
        intent: HostedCapabilityIntent,
    ): Boolean = capabilities.any { it.operation == operation && intent in it.intents }

    internal fun candidates(): List<HostedCapabilityCandidate> =
        capabilities.map(HostedCapability::candidate)

    companion object {
        fun admit(raw: List<HostedCapabilityCandidate>): HostedCapabilitySetAdmission {
            val admitted = ArrayList<HostedCapability>(raw.size)
            val observedOperations = LinkedHashSet<CanonicalOperation>()
            raw.forEach { candidate ->
                val operationId = when (val parsed = OperationId.parse(candidate.operationId)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return HostedCapabilitySetAdmission.Rejected(
                        HostedCapabilitySetFailure.MalformedOperationId(parsed.failure),
                    )
                }
                val operation = when (val resolution = CanonicalOperation.resolve(operationId)) {
                    is CanonicalOperationResolution.Known -> resolution.operation
                    is CanonicalOperationResolution.Unknown ->
                        return HostedCapabilitySetAdmission.Rejected(
                            HostedCapabilitySetFailure.UnknownOperation(resolution.id),
                        )
                }
                if (!observedOperations.add(operation)) {
                    return HostedCapabilitySetAdmission.Rejected(
                        HostedCapabilitySetFailure.DuplicateOperation(operation),
                    )
                }
                val intents = LinkedHashSet<HostedCapabilityIntent>()
                candidate.intents.forEach { identity ->
                    val intent = HostedCapabilityIntent.entries.singleOrNull {
                        it.identity == identity
                    } ?: return HostedCapabilitySetAdmission.Rejected(
                        HostedCapabilitySetFailure.UnsupportedIntent(operation),
                    )
                    if (!intents.add(intent)) {
                        return HostedCapabilitySetAdmission.Rejected(
                            HostedCapabilitySetFailure.DuplicateIntent(operation, intent),
                        )
                    }
                }
                admitted += HostedCapability.create(operation, intents)
            }
            return if (admitted == CanonicalHostedCapabilities.capabilities) {
                HostedCapabilitySetAdmission.Admitted(HostedCapabilitySet(admitted))
            } else {
                HostedCapabilitySetAdmission.Rejected(
                    HostedCapabilitySetFailure.CanonicalProjectionMismatch,
                )
            }
        }
    }
}

/** One in-process projection of the canonical definition authority for endpoint publication. */
object CanonicalHostedCapabilities {
    val capabilities: List<HostedCapability> = HostedOperationProjection.publicDefinitions.map {
        definition ->
        val intents = when (val variants = definition.hostedVariants) {
            is HostedVariants.Intents -> variants.intents.mapTo(linkedSetOf()) { intent ->
                when (intent.identity) {
                    HostedCapabilityIntent.ADD_DECLARATION.identity ->
                        HostedCapabilityIntent.ADD_DECLARATION
                    else -> error("Canonical hosted intent has no wire representation")
                }
            }
            HostedVariants.None -> emptySet()
        }
        HostedCapability.create(definition.operation, intents)
    }

    val candidates: List<HostedCapabilityCandidate> =
        capabilities.map(HostedCapability::candidate)

    val operations: Set<CanonicalOperation> = capabilities.mapTo(linkedSetOf()) {
        it.operation
    }
}
