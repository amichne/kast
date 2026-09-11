package io.github.amichne.kast.appserver

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun writeServiceState(path: Path, document: BrokerServiceStateDocument) {
    val bytes =
        (BROKER_SERVICE_STATE_JSON.encodeToString(BrokerServiceStateDocument.serializer(), document) + "\n")
            .toByteArray(Charsets.UTF_8)
    FileChannel.open(
            path,
            setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        .use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
}

internal fun readServiceState(path: Path): BrokerServiceStateDocument =
    BROKER_SERVICE_STATE_JSON.decodeFromString(
        BrokerServiceStateDocument.serializer(),
        Files.readString(path),
    )

internal fun admittedServiceStateFileKey(
    path: Path,
    expected: BrokerServiceStateDocument,
): Any? {
    val attributes =
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    if (!attributes.isRegularFile || attributes.isSymbolicLink) return null
    val key = attributes.fileKey() ?: return null
    return key.takeIf { readServiceState(path) == expected }
}

internal const val BROKER_SERVICE_STATE_SCHEMA_VERSION = 3

internal val BROKER_SERVICE_STATE_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    classDiscriminator = "state"
}
