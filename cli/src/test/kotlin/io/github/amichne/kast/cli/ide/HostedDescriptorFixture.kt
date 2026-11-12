package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
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
            Operation.SOURCE_READ,
            Operation.DIAGNOSTIC_CHECK,
            Operation.CHANGE_PLAN,
            Operation.CHANGE_APPLY,
            Operation.CHANGE_RECOVER,
        )

    val compatibility = HostedCompatibilityDocument("262.1.1", "262.1.1-IJ", "0.49.0", CanonicalHostedContract.document)

    fun status(root: String, host: UUID, hostPid: Long = 123): String =
        json.encodeToString(Status(root = root, host = host.toString(), hostPid = hostPid))

    fun endpoint(
        root: String,
        socket: String,
        host: UUID,
        protocol: Int = 3,
        application: IdeHostApplicationAdvertisement? = null,
    ): String =
        json.encodeToString(
            Endpoint(
                protocol = protocol,
                root = root,
                socket = socket,
                host = host.toString(),
                hostPid = application?.runtimeIdentity?.pid ?: 123,
                application = application,
            )
        )

    @Serializable
    private data class Status(
        val root: String,
        val host: String,
        val type: String = "KAST_IDE_HOST",
        val protocol: Int = 3,
        val hostPid: Long = 123,
        val querySchema: String = "kast.query.run.v10",
        val operations: List<Operation> = HostedDescriptorFixture.operations,
        val indexAuthority: String = "existing_ide_kotlin_stub_index",
        val compatibility: HostedCompatibilityDocument = HostedDescriptorFixture.compatibility,
    )

    @Serializable
    private data class Endpoint(
        val protocol: Int,
        val root: String,
        val socket: String,
        val host: String,
        val type: String = "KAST_IDE_ENDPOINT",
        val hostPid: Long = 123,
        val querySchema: String = "kast.query.run.v10",
        val operations: List<Operation> = HostedDescriptorFixture.operations,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val application: IdeHostApplicationAdvertisement? = null,
    )

    @Serializable
    private enum class Operation {
        DESCRIBE,
        CLASS_LOOKUP,
        DIRECT_SUPERTYPE,
        QUERY_RUN,
        SOURCE_READ,
        DIAGNOSTIC_CHECK,
        CHANGE_PLAN,
        CHANGE_APPLY,
        CHANGE_RECOVER,
    }
}
