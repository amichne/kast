package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.nio.file.Path

/** The exact graph site whose native membership admits a callback result. */
sealed interface CallbackResultSite {
    val occurrence: RelationOccurrence

    data class Occurrence(override val occurrence: RelationOccurrence) : CallbackResultSite

    data class Invocation(val invocation: CallbackParameterInvocation) : CallbackResultSite {
        override val occurrence = invocation.occurrence
    }

    data class DirectInvocation(val binding: CallbackDirectInvocationBinding) : CallbackResultSite {
        override val occurrence = binding.occurrence
    }

    data class Target(val target: CompilerGroundedSymbolEvidence) : CallbackResultSite {
        override val occurrence = RelationOccurrence.fromSource(target.file, target.range)
    }

    data class AnonymousTarget(val body: RelationCallableBody.Anonymous) : CallbackResultSite {
        override val occurrence = RelationOccurrence.fromSource(body.file, body.range)
    }
}

enum class CallbackResultMembershipFailure {
    MEANING_MISMATCH,
    SITE_OUTSIDE_DOMAIN,
}

/** Native membership stays bound to the original request, current authority, and exact qualifying graph site. */
class CallbackResultMembership
private constructor(
    val subject: RelationEndpointFingerprint,
    val basis: SemanticReadAuthority,
    val domain: RelationScopeFingerprint,
    val meaning: RelationMeaning,
    val site: CallbackResultSite,
) {
    fun belongsTo(request: RelationRequest): Boolean =
        subject == request.subject.fingerprint &&
            basis == request.subject.lease &&
            domain == request.scopeFingerprint &&
            meaning == request.meaning

    fun canonicalProjection(): String =
        listOf(subject.value, domain.value, meaning.toString(), site.canonicalProjection()).joinToString("\u0000")

    fun supportsCallback(
        meaning: RelationMeaning,
        occurrence: RelationOccurrence,
        target: CompilerGroundedSymbolEvidence,
        flow: CallbackInvocationFlowRead,
    ): Boolean = site in CallbackResultSites.callback(meaning, occurrence, target, flow)

    fun supportsReference(meaning: RelationMeaning, reference: NamedCallbackReference): Boolean =
        site in CallbackResultSites.reference(meaning, reference)

    fun supportsSuppliers(inventory: CompleteCallbackSupplierInventory): Boolean =
        site in CallbackResultSites.suppliers(inventory)

    companion object {
        /** The native adapter must first admit this exact site through its selected compiled scope. */
        fun fromNativeScope(
            request: RelationRequest,
            site: CallbackResultSite,
        ): Refinement<CallbackResultMembership, CallbackResultMembershipFailure> {
            val legal =
                when (request.meaning) {
                    RelationMeaning.Callers ->
                        when (site) {
                            is CallbackResultSite.Occurrence,
                            is CallbackResultSite.Invocation,
                            is CallbackResultSite.DirectInvocation -> true
                            is CallbackResultSite.Target,
                            is CallbackResultSite.AnonymousTarget -> false
                        }
                    RelationMeaning.Callees ->
                        when (site) {
                            is CallbackResultSite.Target,
                            is CallbackResultSite.AnonymousTarget -> true
                            is CallbackResultSite.Occurrence,
                            is CallbackResultSite.Invocation,
                            is CallbackResultSite.DirectInvocation -> false
                        }
                    RelationMeaning.References,
                    RelationMeaning.TypeUses,
                    RelationMeaning.Implementations,
                    RelationMeaning.Inheritors,
                    RelationMeaning.Overrides -> false
                }
            if (!legal) return Refinement.Rejected(CallbackResultMembershipFailure.MEANING_MISMATCH)
            if (!site.insideDeclaredFile(request))
                return Refinement.Rejected(CallbackResultMembershipFailure.SITE_OUTSIDE_DOMAIN)
            return Refinement.Refined(
                CallbackResultMembership(
                    request.subject.fingerprint,
                    request.subject.lease,
                    request.scopeFingerprint,
                    request.meaning,
                    site,
                )
            )
        }
    }
}

/** An incomplete eligibility proof remains a finite obligation, never an empty complete result. */
sealed interface CallbackResultProjection {
    data class Admitted(val membership: CallbackResultMembership) : CallbackResultProjection

    data class Unproven(val cause: CallbackInvocationFlowCause) : CallbackResultProjection

    fun canonicalProjection(): String =
        when (this) {
            is Admitted -> "ADMITTED:${membership.canonicalProjection()}"
            is Unproven -> "UNPROVEN:${cause.name}"
        }
}

private fun CallbackResultSite.insideDeclaredFile(request: RelationRequest): Boolean {
    val file = occurrence.file as? SymbolDiscoveryFileIdentity.Workspace ?: return false
    val path = Path.of(file.path.value)
    val root = Path.of(request.subject.lease.workspaceRoot.value)
    if (!path.startsWith(root)) return false
    val selected = request.searchScope
    if (selected is SymbolSearchScope.ExactFile && file.path != selected.file) return false
    val directory = request.searchConstraints.directory ?: return true
    val requested = root.resolve(directory.directory.value).normalize()
    return when (directory.containment) {
        io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment.DIRECT -> path.parent == requested
        io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment.DESCENDANTS -> path.startsWith(requested)
    }
}

private fun CallbackResultSite.canonicalProjection(): String =
    when (this) {
        is CallbackResultSite.Occurrence -> "OCCURRENCE:${occurrence.file.stableValue}:${occurrence.range}"
        is CallbackResultSite.Invocation -> "INVOCATION:${invocation.canonicalProjection()}"
        is CallbackResultSite.DirectInvocation ->
            "DIRECT:${binding.occurrence}:${binding.owner.file.stableValue}:${binding.owner.range}:${binding.owner.compilerIdentity.value}"
        is CallbackResultSite.Target ->
            "TARGET:${target.file.stableValue}:${target.range}:${target.compilerIdentity.value}"
        is CallbackResultSite.AnonymousTarget ->
            "ANONYMOUS:${body.file.stableValue}:${body.range}:${body.compilerIdentity.value}"
    }
