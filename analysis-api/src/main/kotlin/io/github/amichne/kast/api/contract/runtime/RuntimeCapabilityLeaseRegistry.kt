package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.client.RuntimeInstanceId
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.EnumMap

enum class RuntimeCapabilityLeaseKind {
    REQUEST,
    CONTINUATION,
}

@ConsistentCopyVisibility
data class RuntimeStopPermit internal constructor(
    val epoch: RuntimeInstanceId,
    internal val generation: Long,
)

sealed interface RuntimeStopPermitAdmission {
    data object Admitted : RuntimeStopPermitAdmission

    data object Superseded : RuntimeStopPermitAdmission
}

class RuntimeCapabilityLease internal constructor(
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

/** One process-owned registry for request and continuation capability lifetimes. */
class RuntimeCapabilityLeaseRegistry(
    private val epoch: RuntimeInstanceId,
    private val scheduler: RuntimeLeaseScheduler = RuntimeLeaseScheduler.system(),
) : AutoCloseable {
    private val lock = Any()
    private val active = EnumMap<RuntimeCapabilityLeaseKind, Long>(RuntimeCapabilityLeaseKind::class.java)
    private var generation = 0L
    private var scheduled: RuntimeLeaseSchedule? = null
    private var stopConsumer: ((RuntimeStopPermit) -> Unit)? = null
    private var closed = false

    /**
     * Proof transition: `RuntimeCapabilityLeaseKind -> RuntimeCapabilityLease`.
     *
     * Establishes one counted request or continuation capability for this
     * immutable runtime epoch and supersedes any unconsumed grace permit. The
     * returned capability is the only authority that can release that count.
     */
    fun acquire(kind: RuntimeCapabilityLeaseKind): RuntimeCapabilityLease = synchronized(lock) {
        check(!closed) { "Runtime capability lease registry is closed" }
        scheduled?.cancel()
        scheduled = null
        generation = Math.addExact(generation, 1)
        active[kind] = Math.addExact(active[kind] ?: 0L, 1)
        RuntimeCapabilityLease { release(kind) }
    }

    /** Installs the runtime boundary that consumes the registry's one-shot stop permit. */
    fun onStopPermit(consumer: (RuntimeStopPermit) -> Unit) = synchronized(lock) {
        check(stopConsumer == null) { "Runtime stop consumer is already installed" }
        stopConsumer = consumer
    }

    /**
     * Proof transition: `RuntimeStopPermit -> RuntimeStopPermitAdmission`.
     *
     * Admits only the permit for this epoch and the latest demand generation
     * while no capability lease is active. A newer demand, disposal, or a
     * foreign epoch produces closed [RuntimeStopPermitAdmission.Superseded]
     * data. Raw generation comparison remains inside this registry boundary.
     */
    fun admitStop(permit: RuntimeStopPermit): RuntimeStopPermitAdmission = synchronized(lock) {
        if (!closed &&
            active.isEmpty() &&
            permit.epoch == epoch &&
            permit.generation == generation
        ) {
            closed = true
            scheduled?.cancel()
            scheduled = null
            RuntimeStopPermitAdmission.Admitted
        } else {
            RuntimeStopPermitAdmission.Superseded
        }
    }

    private fun release(kind: RuntimeCapabilityLeaseKind) = synchronized(lock) {
        val count = active[kind] ?: 0L
        check(count > 0) { "Runtime capability lease underflow" }
        if (count == 1L) active.remove(kind) else active[kind] = count - 1
        if (active.isNotEmpty() || closed) return@synchronized
        val admittedGeneration = generation
        scheduled = scheduler.schedule(FIXED_IDLE_GRACE) {
            val permit = synchronized(lock) {
                if (closed || active.isNotEmpty() || generation != admittedGeneration) return@schedule
                scheduled = null
                RuntimeStopPermit(epoch, admittedGeneration)
            }
            stopConsumer?.invoke(permit)
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        scheduled?.cancel()
        scheduled = null
    }

    companion object {
        val FIXED_IDLE_GRACE: Duration = Duration.ofMinutes(5)
    }
}

fun interface RuntimeLeaseSchedule {
    fun cancel()
}

fun interface RuntimeLeaseScheduler {
    fun schedule(delay: Duration, action: () -> Unit): RuntimeLeaseSchedule

    companion object {
        fun system(): RuntimeLeaseScheduler {
            val executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "kast-runtime-idle-stop").apply { isDaemon = true }
            }
            return RuntimeLeaseScheduler { delay, action ->
                val future: ScheduledFuture<*> = executor.schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS)
                RuntimeLeaseSchedule { future.cancel(false) }
            }
        }
    }
}
