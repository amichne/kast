package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.apply.BrokerApprovalVerificationKey
import io.github.amichne.kast.change.apply.LiveApprovalChallenge
import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.change.apply.LivePlanApprovalExpectation
import io.github.amichne.kast.change.apply.VerifiedLivePlanApproval
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

internal enum class HostedApprovalFailure {
    UNAVAILABLE,
    CAPACITY_EXHAUSTED,
    NOT_PENDING,
    INVALID_ASSERTION,
    KEY_CHANGED,
    RETIRED,
}

/** Project-owned, bounded and single-use challenges. Key enrollment happens only through explicit setup. */
internal class HostedChangeApprovals(
    private val owner: IdeReadHostLifetime,
    private val key: () -> Refinement<BrokerApprovalVerificationKey, HostedApprovalFailure>,
) : AutoCloseable {
    private data class Pending(
        val expectation: LivePlanApprovalExpectation,
        val key: BrokerApprovalVerificationKey,
    )

    private sealed interface State {
        data class Open(val pending: MutableMap<LiveApprovalChallenge, Pending>) : State

        data object Retired : State
    }

    private var state: State = State.Open(mutableMapOf())
    private val random = SecureRandom()

    @Synchronized
    fun prepare(
        plan: LiveAddDeclarationChangePlan,
        effect: LiveChangeEffect,
    ): Refinement<LiveApprovalChallenge, HostedApprovalFailure> {
        val open =
            when (val current = state) {
                is State.Open -> current
                State.Retired -> return Refinement.Rejected(HostedApprovalFailure.RETIRED)
            }
        val pinned =
            when (val loaded = key()) {
                is Refinement.Refined -> loaded.value
                is Refinement.Rejected -> return loaded
            }
        if (open.pending.size >= MAX_PENDING_APPROVALS)
            return Refinement.Rejected(HostedApprovalFailure.CAPACITY_EXHAUSTED)
        val bytes = ByteArray(APPROVAL_CHALLENGE_BYTES).also(random::nextBytes)
        val challenge =
            when (
                val parsed =
                    LiveApprovalChallenge.parse(bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) })
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
            }
        if (challenge in open.pending) return Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
        open.pending[challenge] =
            Pending(
                LivePlanApprovalExpectation(plan = plan, owner = owner, operation = effect, challenge = challenge),
                pinned,
            )
        return Refinement.Refined(challenge)
    }

    @Synchronized
    fun consume(
        plan: LiveAddDeclarationChangePlan,
        effect: LiveChangeEffect,
        assertion: String,
    ): Refinement<VerifiedLivePlanApproval, HostedApprovalFailure> {
        val open =
            when (val current = state) {
                is State.Open -> current
                State.Retired -> return Refinement.Rejected(HostedApprovalFailure.RETIRED)
            }
        val candidates =
            open.pending.values.filter {
                it.expectation.plan.planId == plan.planId && it.expectation.operation == effect
            }
        if (candidates.isEmpty()) return Refinement.Rejected(HostedApprovalFailure.NOT_PENDING)
        val pinned =
            when (val loaded = key()) {
                is Refinement.Refined -> loaded.value
                is Refinement.Rejected -> return loaded
            }
        val (current, retired) = candidates.partition { it.key.sameAuthority(pinned) }
        retired.forEach { open.pending.remove(it.expectation.challenge) }
        if (current.isEmpty()) return Refinement.Rejected(HostedApprovalFailure.KEY_CHANGED)
        for (pending in current) {
            when (val verified = VerifiedLivePlanApproval.verify(assertion, pending.key, pending.expectation)) {
                is Refinement.Refined -> {
                    open.pending.remove(verified.value.challenge)
                    return verified
                }
                is Refinement.Rejected -> Unit
            }
        }
        return Refinement.Rejected(HostedApprovalFailure.INVALID_ASSERTION)
    }

    @Synchronized
    override fun close() {
        state = State.Retired
    }
}

private const val MAX_PENDING_APPROVALS = 128
private const val MAX_APPROVAL_KEY_BYTES = 128
private const val APPROVAL_CHALLENGE_BYTES = 32

internal fun loadHostedApprovalKey(home: Path): Refinement<BrokerApprovalVerificationKey, HostedApprovalFailure> {
    val directory = home.resolve(".kast/approval")
    val publicKey = directory.resolve("broker.pub")
    return try {
        if (
            listOf(home, home.resolve(".kast"), directory, publicKey).any(Files::isSymbolicLink) ||
                !Files.isDirectory(directory, NOFOLLOW_LINKS) ||
                !Files.isRegularFile(publicKey, NOFOLLOW_LINKS)
        )
            return Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
        if (
            Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) != PosixFilePermissions.fromString("rwx------") ||
                Files.getPosixFilePermissions(publicKey, NOFOLLOW_LINKS) !=
                    PosixFilePermissions.fromString("rw-------") ||
                Files.size(publicKey) !in 1..MAX_APPROVAL_KEY_BYTES
        ) {
            return Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
        }
        val bytes = Files.newInputStream(publicKey, NOFOLLOW_LINKS).use { it.readNBytes(MAX_APPROVAL_KEY_BYTES + 1) }
        when (val decoded = BrokerApprovalVerificationKey.decodePinnedX509(bytes)) {
            is Refinement.Refined -> decoded
            is Refinement.Rejected -> Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
        }
    } catch (_: java.io.IOException) {
        Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
    } catch (_: SecurityException) {
        Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
    } catch (_: UnsupportedOperationException) {
        Refinement.Rejected(HostedApprovalFailure.UNAVAILABLE)
    }
}
