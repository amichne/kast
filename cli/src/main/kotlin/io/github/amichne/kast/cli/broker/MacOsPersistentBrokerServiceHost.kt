package io.github.amichne.kast.cli.broker

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal fun interface BrokerSocketProbe {
    fun probe(path: Path): BrokerSocketReachability
}

internal enum class BrokerSocketReachability { REACHABLE, UNREACHABLE, REJECTED }

internal fun interface BrokerSocketPathObserver {
    fun observe(path: Path): BrokerSocketPathObservation
}

/** Finite Unix socket-path evidence established without following symbolic links. */
internal sealed interface BrokerSocketPathObservation {
    /** The leaf or its immediate parent is absent beneath an admitted canonical directory. */
    data object Absent : BrokerSocketPathObservation

    data object Socket : BrokerSocketPathObservation
    data object WrongType : BrokerSocketPathObservation
    data object Rejected : BrokerSocketPathObservation
}

internal object JdkBrokerSocketPathObserver : BrokerSocketPathObserver {
    override fun observe(path: Path): BrokerSocketPathObservation {
        if (!path.isAbsolute || path.normalize() != path) {
            return BrokerSocketPathObservation.Rejected
        }
        val parent = path.parent ?: return BrokerSocketPathObservation.Rejected
        when (admitParent(parent)) {
            BrokerSocketParentAdmission.Absent -> return BrokerSocketPathObservation.Absent
            BrokerSocketParentAdmission.Rejected -> return BrokerSocketPathObservation.Rejected
            BrokerSocketParentAdmission.Admitted -> Unit
        }
        val mode = try {
            Files.getAttribute(path, UNIX_MODE_ATTRIBUTE, LinkOption.NOFOLLOW_LINKS) as? Int
                ?: return BrokerSocketPathObservation.Rejected
        } catch (_: NoSuchFileException) {
            return BrokerSocketPathObservation.Absent
        } catch (_: IOException) {
            return BrokerSocketPathObservation.Rejected
        } catch (_: UnsupportedOperationException) {
            return BrokerSocketPathObservation.Rejected
        } catch (_: SecurityException) {
            return BrokerSocketPathObservation.Rejected
        }
        return if (mode and UNIX_FILE_TYPE_MASK == UNIX_SOCKET_FILE_TYPE) {
            BrokerSocketPathObservation.Socket
        } else {
            BrokerSocketPathObservation.WrongType
        }
    }

    private fun admitParent(parent: Path): BrokerSocketParentAdmission = try {
        if (
            parent.toRealPath() == parent &&
            Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
        ) {
            BrokerSocketParentAdmission.Admitted
        } else {
            BrokerSocketParentAdmission.Rejected
        }
    } catch (_: NoSuchFileException) {
        if (Files.isSymbolicLink(parent)) {
            BrokerSocketParentAdmission.Rejected
        } else {
            val existingParent = parent.parent ?: return BrokerSocketParentAdmission.Rejected
            if (canonicalDirectory(existingParent)) {
                BrokerSocketParentAdmission.Absent
            } else {
                BrokerSocketParentAdmission.Rejected
            }
        }
    } catch (_: IOException) {
        BrokerSocketParentAdmission.Rejected
    } catch (_: SecurityException) {
        BrokerSocketParentAdmission.Rejected
    }

    private fun canonicalDirectory(path: Path): Boolean = try {
        path.toRealPath() == path && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private const val UNIX_MODE_ATTRIBUTE = "unix:mode"
    private const val UNIX_FILE_TYPE_MASK = 0xF000
    private const val UNIX_SOCKET_FILE_TYPE = 0xC000
}

private enum class BrokerSocketParentAdmission { Admitted, Absent, Rejected }

internal object JdkBrokerSocketProbe : BrokerSocketProbe {
    private val pathObserver: BrokerSocketPathObserver = JdkBrokerSocketPathObserver

    override fun probe(path: Path): BrokerSocketReachability {
        when (pathObserver.observe(path)) {
            BrokerSocketPathObservation.Absent -> return BrokerSocketReachability.UNREACHABLE
            BrokerSocketPathObservation.WrongType,
            BrokerSocketPathObservation.Rejected,
                -> return BrokerSocketReachability.REJECTED
            BrokerSocketPathObservation.Socket -> Unit
        }
        return try {
            runBlocking(Dispatchers.IO) { exchangeInitialize(path) }
        } catch (failure: Exception) {
            if (failure.hasCause<ConnectException>()) {
                BrokerSocketReachability.UNREACHABLE
            } else {
                when (pathObserver.observe(path)) {
                    BrokerSocketPathObservation.Absent -> BrokerSocketReachability.UNREACHABLE
                    BrokerSocketPathObservation.Socket,
                    BrokerSocketPathObservation.WrongType,
                    BrokerSocketPathObservation.Rejected,
                        -> BrokerSocketReachability.REJECTED
                }
            }
        }
    }

    private suspend fun exchangeInitialize(path: Path): BrokerSocketReachability {
        val client = HttpClient(CIO) {
            install(WebSockets) { maxFrameSize = MAXIMUM_READINESS_FRAME_BYTES }
        }
        return try {
            withTimeoutOrNull(READINESS_EXCHANGE_TIMEOUT_MILLIS) {
                val session = client.webSocketSession {
                    url("ws://localhost/rpc")
                    unixSocket(path.toString())
                }
                try {
                    session.send(READINESS_INITIALIZE_REQUEST)
                    while (true) {
                        val frame = session.incoming.receiveCatching().getOrNull()
                            ?: return@withTimeoutOrNull BrokerSocketReachability.REJECTED
                        val message = (frame as? Frame.Text)?.readText()
                            ?: return@withTimeoutOrNull BrokerSocketReachability.REJECTED
                        val document = parseObject(message)
                            ?: return@withTimeoutOrNull BrokerSocketReachability.REJECTED
                        val responseId = (document["id"] as? JsonPrimitive)?.contentOrNull
                        if (responseId != READINESS_REQUEST_ID) continue
                        return@withTimeoutOrNull if (
                            document["method"] == null &&
                            document.containsKey("result") &&
                            !document.containsKey("error")
                        ) {
                            BrokerSocketReachability.REACHABLE
                        } else {
                            BrokerSocketReachability.REJECTED
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    BrokerSocketReachability.REJECTED
                } finally {
                    session.close()
                }
            } ?: BrokerSocketReachability.REJECTED
        } finally {
            client.close()
        }
    }

    private fun parseObject(message: String): JsonObject? = try {
        Json.parseToJsonElement(message) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <reified Failure : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { current -> current.cause }
            .any { current -> current is Failure }

    private const val READINESS_REQUEST_ID = "kast-readiness-v1"
    private const val READINESS_EXCHANGE_TIMEOUT_MILLIS = 3_000L
    private const val MAXIMUM_READINESS_FRAME_BYTES = 1024L * 1024L
    private val READINESS_INITIALIZE_REQUEST =
        """{"id":"$READINESS_REQUEST_ID","method":"initialize","params":{"clientInfo":{"name":"kast-readiness","version":"$VENDORED_BROKER_VERSION"},"capabilities":{"experimentalApi":true}}}"""
}
