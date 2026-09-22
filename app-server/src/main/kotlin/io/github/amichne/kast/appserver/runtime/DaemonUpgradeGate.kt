package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface DaemonUpgradeControl {
    fun prepare(
        candidate: UpgradeCandidate,
        blockers: () -> Set<UpgradeBlocker>,
    ): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>

    fun observe(id: UpgradeRequestId): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>

    fun cancel(id: UpgradeRequestId): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>

    fun commit(id: UpgradeRequestId): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>

    fun <T> manage(action: () -> T): Refinement<T, DaemonUpgradeFailure>

    data object Unavailable : DaemonUpgradeControl {
        override fun prepare(candidate: UpgradeCandidate, blockers: () -> Set<UpgradeBlocker>) = rejected()

        override fun observe(id: UpgradeRequestId) = rejected()

        override fun cancel(id: UpgradeRequestId) = rejected()

        override fun commit(id: UpgradeRequestId) = rejected()

        override fun <T> manage(action: () -> T) = Refinement.Refined(action())

        private fun rejected() = Refinement.Rejected(DaemonUpgradeFailure.UNAVAILABLE)
    }
}

/** Shared by lazy host admission, session ingress and local management for one daemon generation. */
internal class DaemonUpgradeGate(private val observer: DaemonUpgradeObserver = DaemonUpgradeObserver.Stderr) :
    DaemonUpgradeControl {
    val transition = Mutex()
    private val owner = DaemonUpgradeAdmission()

    /** Caller holds [transition] across admission and registration of any resulting asynchronous work. */
    fun admitWork(): Refinement<Unit, DaemonUpgradeFailure> = owner.admitWork()

    override fun prepare(candidate: UpgradeCandidate, blockers: () -> Set<UpgradeBlocker>) = runBlocking {
        transition.withLock {
            owner.prepare(candidate, blockers()).also { observer.observe(it.observation(DaemonUpgradeStage.PREPARE)) }
        }
    }

    override fun observe(id: UpgradeRequestId) = runBlocking {
        transition.withLock { owner.observe(id).also { observer.observe(it.observation(DaemonUpgradeStage.OBSERVE)) } }
    }

    override fun cancel(id: UpgradeRequestId) = runBlocking {
        transition.withLock { owner.cancel(id).also { observer.observe(it.observation(DaemonUpgradeStage.CANCEL)) } }
    }

    override fun commit(id: UpgradeRequestId) = runBlocking {
        transition.withLock { owner.commit(id).also { observer.observe(it.observation(DaemonUpgradeStage.COMMIT)) } }
    }

    override fun <T> manage(action: () -> T): Refinement<T, DaemonUpgradeFailure> = runBlocking {
        transition.withLock {
            when (val admission = owner.admitWork()) {
                is Refinement.Rejected -> admission
                is Refinement.Refined -> Refinement.Refined(action())
            }
        }
    }
}
