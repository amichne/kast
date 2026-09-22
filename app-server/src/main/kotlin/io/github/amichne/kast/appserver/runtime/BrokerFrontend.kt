package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.AppServerAction
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.sync.withLock

internal interface BrokerFrontend : DaemonSessions {
    suspend fun connect(session: DefaultWebSocketServerSession)

    suspend fun close()
}

internal sealed interface BrokerFrontendAdmission {
    data class Prepared(val frontend: BrokerFrontend) : BrokerFrontendAdmission

    data object Rejected : BrokerFrontendAdmission
}

internal enum class BrokerFrontendObservation {
    PENDING,
    PREPARED,
    REJECTED,
    CLOSED,
}

/** Optional host qualification is performed once, when the first frontend actually attaches. */
internal class DeferredBrokerFrontend(private val prepare: suspend (DaemonUpgradeGate) -> BrokerFrontendAdmission) :
    BrokerFrontend {
    override val upgrades = DaemonUpgradeGate()
    private val transition = upgrades.transition

    private sealed interface State {
        data object Pending : State

        data class Prepared(val frontend: BrokerFrontend) : State

        data object Rejected : State

        data object Closed : State
    }

    @Volatile private var state: State = State.Pending

    internal fun observe(): BrokerFrontendObservation =
        when (state) {
            State.Pending -> BrokerFrontendObservation.PENDING
            is State.Prepared -> BrokerFrontendObservation.PREPARED
            State.Rejected -> BrokerFrontendObservation.REJECTED
            State.Closed -> BrokerFrontendObservation.CLOSED
        }

    override fun inspectSessions(): DaemonSessionInspection =
        when (val selected = state) {
            State.Pending -> DaemonSessionInspection.Pending
            State.Rejected -> DaemonSessionInspection.Rejected
            State.Closed -> DaemonSessionInspection.Closed
            is State.Prepared -> selected.frontend.inspectSessions()
        }

    override fun controlSession(action: AppServerAction.Control): ControlResult =
        when (val selected = state) {
            is State.Prepared -> selected.frontend.controlSession(action)
            State.Pending,
            State.Rejected,
            State.Closed -> ControlResult.Rejected(ControlFailure.HOST_UNAVAILABLE)
        }

    override fun upgradeBlockers(): Set<UpgradeBlocker> =
        when (val selected = state) {
            is State.Prepared -> selected.frontend.upgradeBlockers()
            State.Pending,
            State.Rejected,
            State.Closed -> emptySet()
        }

    override suspend fun connect(session: DefaultWebSocketServerSession) {
        val selected = transition.withLock {
            if (upgrades.admitWork() is io.github.amichne.kast.kernel.Refinement.Rejected) {
                session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Daemon update admission sealed"))
                return
            }
            if (state == State.Pending)
                state =
                    when (val admission = prepare(upgrades)) {
                        is BrokerFrontendAdmission.Prepared -> State.Prepared(admission.frontend)
                        BrokerFrontendAdmission.Rejected -> State.Rejected
                    }
            state
        }
        when (selected) {
            is State.Prepared -> selected.frontend.connect(session)
            State.Pending,
            State.Rejected,
            State.Closed -> session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Codex host unavailable"))
        }
    }

    override suspend fun close() = transition.withLock {
        val previous = state
        state = State.Closed
        if (previous is State.Prepared) previous.frontend.close()
    }
}
