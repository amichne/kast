package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One public shell invocation forwarded by a thin frontend on the coordinator's private socket.
 *
 * The envelope is typed; [PublicInvocationRequest.arguments], the caller environment, stdin bytes and the produced
 * output stay opaque here. The coordinator's public shell owner lowers, admits and presents them, so a frontend never
 * reproduces lowering rules or semantic admission.
 */
object PublicInvocationProtocol {
    const val version = 1
    const val route = "/kast-public-invocation"
    const val maximumRequestBytes = 1_048_576
    /** How long a requested input may take to arrive; a caller holding stdin open must not hold a connection slot. */
    val inputLimit: ElapsedTimeLimitMillis =
        (ElapsedTimeLimitMillis.parse(30_000) as Refinement.Refined<ElapsedTimeLimitMillis>).value
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
}

@Serializable
internal data class PublicInvocationRequest(
    @Required val version: Int,
    /** The caller's absolute, normalized working directory. */
    val directory: String,
    val arguments: List<String>,
    /** The complete caller environment; the coordinator projects only the settings its owners govern. */
    val environment: Map<String, String>,
) {
    override fun toString(): String = "PublicInvocationRequest(version=$version, arguments=${arguments.size})"
}

/** Server messages. A request receives zero or one [InputRequired] and then exactly one terminal message. */
@Serializable
internal sealed interface PublicInvocationReply {
    /** The admitted command reads stdin; the frontend answers with one binary frame of at most [maximumBytes]. */
    @Serializable @SerialName("input_required") data class InputRequired(val maximumBytes: Int) : PublicInvocationReply

    /** [output] is the complete presented document; the frontend owns its delivery and the process status. */
    @Serializable
    @SerialName("completed")
    data class Completed(
        val coordinator: PublicInvocationCoordinator,
        val exitCode: Int,
        val output: String,
        val diagnostics: List<String>,
    ) : PublicInvocationReply

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: PublicInvocationFailure) : PublicInvocationReply
}

/** The serving coordinator generation and process, so callers can prove warm reuse. */
@Serializable internal data class PublicInvocationCoordinator(val serviceGeneration: String, val processId: Long)

/**
 * No failure has a workspace effect. [INPUT_EXPIRED] cancels the owner while it awaits request input, which precedes
 * every session call; every other failure is decided before the owner runs.
 */
@Serializable
internal enum class PublicInvocationFailure {
    INVALID_REQUEST,
    UNSUPPORTED_VERSION,
    CAPACITY_EXCEEDED,
    LIFECYCLE_TRANSITION,
    UNAVAILABLE,
    INPUT_EXPIRED,
}

/** A request whose envelope and working directory were admitted; argument meaning is still unadmitted. */
class AdmittedPublicInvocation
private constructor(val directory: Path, val arguments: List<String>, val environment: Map<String, String>) {
    companion object {
        /** The caller's directory must be absolute and normalized; nothing else is interpreted here. */
        fun admit(
            directory: Path,
            arguments: List<String>,
            environment: Map<String, String>,
        ): AdmittedPublicInvocation? =
            if (directory.isAbsolute && directory.normalize() == directory)
                AdmittedPublicInvocation(directory, arguments.toList(), environment.toMap())
            else null
    }
}

/** Blocks the invocation worker until the frontend delivers stdin; at most one read per invocation. */
fun interface PublicInvocationInput {
    fun read(maximumBytes: Int): ByteArray
}

class PublicInvocationOutcome(val exitCode: Int, val output: String, val diagnostics: List<String>)

/**
 * The public shell owner inside the coordinator. [semantic] reaches this coordinator's own executor in process, with
 * the same runtime-settings admission and result projection as the semantic route, for one installation binding and the
 * environment that binding admitted.
 */
fun interface PublicInvocationExecutor {
    fun invoke(
        invocation: AdmittedPublicInvocation,
        semantic: (installation: Path, environment: Map<String, String>) -> DaemonOperationClient,
        input: PublicInvocationInput,
    ): PublicInvocationOutcome
}

/**
 * The thin-frontend route's owner. A requested input that does not arrive within [inputLimit] cancels the invocation,
 * so a caller that never closes stdin cannot hold a lease or a connection slot indefinitely.
 */
class PublicInvocations(
    val executor: PublicInvocationExecutor,
    val inputLimit: ElapsedTimeLimitMillis = PublicInvocationProtocol.inputLimit,
)
