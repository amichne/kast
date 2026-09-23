package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.HostedRequestEffect
import io.github.amichne.kast.protocol.wire.presentation.PreparedOperationRequest
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome

@kotlinx.serialization.Serializable
enum class ExistingIdeFailure {
    CONFIGURATION_REJECTED,
    INVALID_NAME,
    INVALID_REQUEST,
    HOST_UNAVAILABLE,
    DESCRIPTOR_REJECTED,
    RESPONSE_REJECTED,
    REQUEST_TOO_LARGE,
    DEADLINE_EXCEEDED,
    TRANSPORT_REJECTED,
    SCHEMA_UNAVAILABLE,
    OPERATION_UNSUPPORTED,
    APPROVAL_REQUIRED,
    APPROVAL_REJECTED,
}

private const val MAXIMUM_CLASS_NAME_BYTES = 512
private const val MAXIMUM_QUALIFIED_NAME_BYTES = 4096

private fun validIdentifier(raw: String): Boolean =
    (raw.first().isLetter() || raw.first() == '_') && raw.all { it.isLetterOrDigit() || it == '_' }

class ExistingIdeClassName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ExistingIdeClassName, ExistingIdeFailure> =
            if (raw.toByteArray(Charsets.UTF_8).size in 1..MAXIMUM_CLASS_NAME_BYTES && validIdentifier(raw)) {
                Refinement.Refined(ExistingIdeClassName(raw))
            } else Refinement.Rejected(ExistingIdeFailure.INVALID_NAME)
    }
}

sealed interface ExistingIdeOperation {
    data object Status : ExistingIdeOperation

    data class Classes(val name: ExistingIdeClassName) : ExistingIdeOperation

    data class Supertype(val name: ExistingIdeQualifiedClassName) : ExistingIdeOperation

    sealed interface Change : ExistingIdeOperation {
        val request: PreparedOperationRequest
    }

    class Plan private constructor(override val request: PreparedOperationRequest) : Change {
        companion object {
            fun admit(request: PreparedOperationRequest): Refinement<Plan, ExistingIdeFailure> =
                when (val demand = request.hostedEffect) {
                    is HostedRequestEffect.ChangePlan ->
                        if (
                            request.operation == CanonicalOperation.CHANGE_PLAN &&
                                demand.intent is ChangeIntentDocument.AddDeclaration
                        ) {
                            Refinement.Refined(Plan(request))
                        } else Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
                    else -> Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
                }
        }
    }

    class ApprovalPreparation constructor(val kind: HostedMutationOperation, val identity: HostedPlanIdentity) :
        ExistingIdeOperation

    class ApprovedMutation
    private constructor(
        override val request: PreparedOperationRequest,
        val kind: HostedMutationOperation,
        val identity: HostedPlanIdentity,
        val assertion: HostedApprovalAssertion,
    ) : Change {
        companion object {
            fun admit(
                request: PreparedOperationRequest,
                kind: HostedMutationOperation,
                identity: HostedPlanIdentity,
                assertion: HostedApprovalAssertion,
            ): Refinement<ApprovedMutation, ExistingIdeFailure> =
                if (request.operation == kind.canonical)
                    Refinement.Refined(ApprovedMutation(request, kind, identity, assertion))
                else Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
        }
    }

    class Read
    private constructor(
        val request: PreparedOperationRequest,
        val kind: ExistingIdeReadOperation,
    ) : ExistingIdeOperation {
        companion object {
            fun admit(request: PreparedOperationRequest): Refinement<Read, ExistingIdeFailure> =
                when (val kind = ExistingIdeReadOperation.admit(request.operation)) {
                    is Refinement.Refined -> Refinement.Refined(Read(request, kind.value))
                    is Refinement.Rejected -> kind
                }
        }
    }
}

/** Exactly the semantic reads supported by the existing-project host. */
@kotlinx.serialization.Serializable
enum class ExistingIdeReadOperation(val canonical: CanonicalOperation) {
    QUERY_RUN(CanonicalOperation.QUERY_RUN),
    SYMBOL_DISCOVER(CanonicalOperation.SYMBOL_DISCOVER),
    SYMBOL_INSPECT(CanonicalOperation.SYMBOL_INSPECT),
    SOURCE_READ(CanonicalOperation.SOURCE_READ),
    RELATION_READ(CanonicalOperation.RELATION_READ),
    TRAVERSAL_RUN(CanonicalOperation.TRAVERSAL_RUN),
    DIAGNOSTIC_CHECK(CanonicalOperation.DIAGNOSTIC_CHECK);

    companion object {
        fun admit(operation: CanonicalOperation): Refinement<ExistingIdeReadOperation, ExistingIdeFailure> =
            entries.singleOrNull { it.canonical == operation }?.let { Refinement.Refined(it) }
                ?: Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
    }
}

class ExistingIdeQualifiedClassName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ExistingIdeQualifiedClassName, ExistingIdeFailure> =
            if (
                raw.toByteArray(Charsets.UTF_8).size in 1..MAXIMUM_QUALIFIED_NAME_BYTES &&
                    raw.split('.').all { ExistingIdeClassName.parse(it) is Refinement.Refined }
            ) {
                Refinement.Refined(ExistingIdeQualifiedClassName(raw))
            } else Refinement.Rejected(ExistingIdeFailure.INVALID_NAME)
    }
}

/** The only runtime capability this path receives; it cannot demand an isolated worker. */
fun interface ExistingIdeClient {
    fun query(root: CanonicalRoot, operation: ExistingIdeOperation): ExistingIdeExchange
}

sealed interface ExistingIdeExchange {
    class Received constructor(val document: CanonicalJsonDocument) : ExistingIdeExchange

    class Semantic constructor(val outcome: ProjectedOperationOutcome) : ExistingIdeExchange

    class HostRejected constructor(val document: CanonicalJsonDocument) : ExistingIdeExchange

    data class Rejected(val failure: ExistingIdeFailure) : ExistingIdeExchange
}
