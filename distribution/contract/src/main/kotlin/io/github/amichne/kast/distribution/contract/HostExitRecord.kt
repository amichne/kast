package io.github.amichne.kast.distribution.contract

import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Which acknowledged stop path asked the managed Host to exit. */
@Serializable
enum class HostExitReason {
    /** The Host's own idle limit elapsed with no admitted or native work. */
    IDLE,
    /** Control settled the Host and acknowledged an explicit stop. */
    SETTLED,
}

/** Whether every open project was in smart mode when the Host recorded its exit. */
@Serializable
enum class HostExitIndexState {
    SMART,
    DUMB,
}

/**
 * Evidence that a managed Host settled, acknowledged a stop, and requested its normal, vetoable exit. It does not prove
 * that the process finished exiting or that indexes were flushed. The Host retracts it when admission resumes, and
 * Control deletes it before every launch, so a surviving record belongs to the latest launch of that private state. The
 * record's location, a generation's private state directory, binds it to that generation and runtime.
 */
@Serializable
data class HostExitRecord(
    val reason: HostExitReason,
    val index: HostExitIndexState,
    val hostLifetime: String,
    @Required @EncodeDefault val schemaVersion: Int = SCHEMA_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val FILE_NAME: String = "host-exit.json"

        /** The record lives directly in the private Host state directory, beside `home`, `system`, and `log`. */
        fun path(state: Path): Path = state.resolve(FILE_NAME)
    }
}
