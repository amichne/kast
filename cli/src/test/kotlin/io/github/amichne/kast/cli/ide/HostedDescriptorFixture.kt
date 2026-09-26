package io.github.amichne.kast.cli.ide

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Fixed host descriptor shapes shared by CLI boundary tests. */
internal object HostedDescriptorFixture {
    private val json = Json { encodeDefaults = true }
    private val operations =
        listOf(
            Operation.DESCRIBE,
            Operation.CLASS_LOOKUP,
            Operation.DIRECT_SUPERTYPE,
            Operation.QUERY_RUN,
            Operation.SYMBOL_DISCOVER,
            Operation.SYMBOL_INSPECT,
            Operation.SOURCE_READ,
            Operation.TRAVERSAL_RUN,
            Operation.DIAGNOSTIC_CHECK,
            Operation.CHANGE_PLAN,
            Operation.CHANGE_APPROVAL_PREPARE,
            Operation.CHANGE_APPLY,
            Operation.CHANGE_RECOVER,
        )

    fun status(root: String, host: UUID): String = json.encodeToString(Status(root = root, host = host.toString()))

    fun endpoint(root: String, socket: String, host: UUID, protocol: Int = 3): String =
        json.encodeToString(Endpoint(protocol = protocol, root = root, socket = socket, host = host.toString()))

    @Serializable
    private data class Status(
        val root: String,
        val host: String,
        val type: String = "KAST_IDE_HOST",
        val protocol: Int = 3,
        val hostPid: Int = 123,
        val querySchema: String = "kast.query.run.v2",
        val operations: List<Operation> = HostedDescriptorFixture.operations,
        val indexAuthority: String = "existing_ide_kotlin_stub_index",
    )

    @Serializable
    private data class Endpoint(
        val protocol: Int,
        val root: String,
        val socket: String,
        val host: String,
        val type: String = "KAST_IDE_ENDPOINT",
        val hostPid: Int = 123,
        val querySchema: String = "kast.query.run.v2",
        val operations: List<Operation> = HostedDescriptorFixture.operations,
    )

    @Serializable
    private enum class Operation {
        DESCRIBE,
        CLASS_LOOKUP,
        DIRECT_SUPERTYPE,
        QUERY_RUN,
        SYMBOL_DISCOVER,
        SYMBOL_INSPECT,
        SOURCE_READ,
        TRAVERSAL_RUN,
        DIAGNOSTIC_CHECK,
        CHANGE_PLAN,
        CHANGE_APPROVAL_PREPARE,
        CHANGE_APPLY,
        CHANGE_RECOVER,
    }
}
