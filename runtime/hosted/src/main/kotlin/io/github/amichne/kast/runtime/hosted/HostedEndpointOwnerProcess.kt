package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A Linux zombie cannot execute; retain its kernel start ticks until retirement revalidation. */
internal sealed interface HostedEndpointOwnerProcess {
    data object Absent : HostedEndpointOwnerProcess

    data class Zombie(val startTicks: Long) : HostedEndpointOwnerProcess {
        init {
            require(startTicks > 0)
        }
    }

    data object Active : HostedEndpointOwnerProcess

    data object Unavailable : HostedEndpointOwnerProcess

    companion object {
        fun observe(pid: Long): HostedEndpointOwnerProcess {
            val observed = DeadHostedEndpointOwner.observeProcess(pid)
            val state =
                when (observed) {
                    Absent -> HostedEndpointOwnerState.ABSENT
                    is Zombie -> HostedEndpointOwnerState.ZOMBIE
                    Active -> HostedEndpointOwnerState.ACTIVE
                    Unavailable -> HostedEndpointOwnerState.UNAVAILABLE
                }
            Logger.getInstance(DeadHostedEndpointOwner::class.java)
                .info("kast_endpoint_owner " + Json.encodeToString(HostedEndpointOwnerObservation(pid, state)))
            return observed
        }
    }
}

/** Field 2 can contain spaces and parentheses; field 22 binds the exact zombie incarnation. */
internal fun parseLinuxOwnerProcess(pid: Long, stat: String): HostedEndpointOwnerProcess {
    if (!stat.startsWith("$pid (") || stat.length > MAXIMUM_PROCESS_STAT_CHARACTERS)
        return HostedEndpointOwnerProcess.Unavailable
    val end = stat.lastIndexOf(')')
    if (end < 0 || end + 2 >= stat.length || stat[end + 1] != ' ') return HostedEndpointOwnerProcess.Unavailable
    val fields = stat.substring(end + 2).trimEnd('\n').split(' ')
    if (fields.size <= PROCESS_START_TICKS_INDEX) return HostedEndpointOwnerProcess.Unavailable
    val start = fields[PROCESS_START_TICKS_INDEX].toLongOrNull()
    if (start == null || start <= 0) return HostedEndpointOwnerProcess.Unavailable
    return when (fields[0]) {
        "Z" -> HostedEndpointOwnerProcess.Zombie(start)
        "R",
        "S",
        "D",
        "T",
        "t",
        "I",
        "P",
        "X" -> HostedEndpointOwnerProcess.Active
        else -> HostedEndpointOwnerProcess.Unavailable
    }
}

@Serializable
internal enum class HostedEndpointOwnerState {
    ABSENT,
    ZOMBIE,
    ACTIVE,
    UNAVAILABLE,
}

@Serializable internal data class HostedEndpointOwnerObservation(val pid: Long, val state: HostedEndpointOwnerState)

private const val MAXIMUM_PROCESS_STAT_CHARACTERS = 4096
private const val PROCESS_START_TICKS_INDEX = 19
