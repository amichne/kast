package io.github.amichne.kast.runtime.telemetry

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointTelemetryOutput
import io.opentelemetry.exporter.logging.otlp.internal.traces.OtlpStdoutSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

enum class OpenTelemetryFileForwardingFailure {
    DIRECTORY_UNAVAILABLE,
    TRACE_FILE_UNAVAILABLE,
}

sealed interface OpenTelemetryFileForwardingOpening {
    data class Opened(
        val forwarding: OpenTelemetryFileForwarding,
    ) : OpenTelemetryFileForwardingOpening

    data class Rejected(
        val failure: OpenTelemetryFileForwardingFailure,
    ) : OpenTelemetryFileForwardingOpening
}

/** One admitted per-socket OTLP JSON-lines destination and its non-blocking trace adapter. */
class OpenTelemetryFileForwarding private constructor(
    val output: IdeEndpointTelemetryOutput,
    val observability: KastObservability,
    private val provider: SdkTracerProvider,
) {
    companion object {
        /**
         * Proof transition: `IdeEndpointTelemetryOutput -> OpenTelemetryFileForwardingOpening`.
         *
         * Establishes a private, non-symlinked destination before constructing an SDK whose
         * batch processor exports immutable completed spans off the operation thread. Directory
         * and file failures remain finite data; SDK and filesystem values stay inside this adapter
         * boundary.
         */
        fun open(output: IdeEndpointTelemetryOutput): OpenTelemetryFileForwardingOpening {
            val directory = Path.of(output.directoryPath.value)
            when (admitDirectory(directory)) {
                FileDestinationAdmission.Admitted -> Unit
                FileDestinationAdmission.Rejected -> return rejected(
                    OpenTelemetryFileForwardingFailure.DIRECTORY_UNAVAILABLE,
                )
            }
            val traceFile = Path.of(output.traceFilePath.value)
            when (admitTraceFile(traceFile)) {
                FileDestinationAdmission.Admitted -> Unit
                FileDestinationAdmission.Rejected -> return rejected(
                    OpenTelemetryFileForwardingFailure.TRACE_FILE_UNAVAILABLE,
                )
            }
            val stream = try {
                Files.newOutputStream(
                    traceFile,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: IOException) {
                return rejected(OpenTelemetryFileForwardingFailure.TRACE_FILE_UNAVAILABLE)
            } catch (_: SecurityException) {
                return rejected(OpenTelemetryFileForwardingFailure.TRACE_FILE_UNAVAILABLE)
            }
            val exporter = ClosingSpanExporter(
                OtlpStdoutSpanExporter.builder()
                    .setOutput(stream)
                    .setWrapperJsonObject(true)
                    .build(),
                stream,
            )
            val provider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build()
            val openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build()
            return OpenTelemetryFileForwardingOpening.Opened(
                OpenTelemetryFileForwarding(
                    output,
                    OpenTelemetryKastObservability.create(openTelemetry),
                    provider,
                ),
            )
        }

        private fun admitDirectory(path: Path): FileDestinationAdmission = try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (
                    !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(path)
                ) {
                    return FileDestinationAdmission.Rejected
                }
            } else {
                Files.createDirectory(
                    path,
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------"),
                    ),
                )
            }
            Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString("rwx------"),
            )
            FileDestinationAdmission.Admitted
        } catch (_: IOException) {
            FileDestinationAdmission.Rejected
        } catch (_: SecurityException) {
            FileDestinationAdmission.Rejected
        } catch (_: UnsupportedOperationException) {
            FileDestinationAdmission.Rejected
        }

        private fun admitTraceFile(path: Path): FileDestinationAdmission = try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(path)
                ) {
                    return FileDestinationAdmission.Rejected
                }
            } else {
                Files.newByteChannel(
                    path,
                    setOf(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------"),
                    ),
                ).use { }
            }
            Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString("rw-------"),
            )
            FileDestinationAdmission.Admitted
        } catch (_: IOException) {
            FileDestinationAdmission.Rejected
        } catch (_: SecurityException) {
            FileDestinationAdmission.Rejected
        } catch (_: UnsupportedOperationException) {
            FileDestinationAdmission.Rejected
        }

        private fun rejected(
            failure: OpenTelemetryFileForwardingFailure,
        ) = OpenTelemetryFileForwardingOpening.Rejected(failure)
    }

    internal fun forceFlush(): CompletableResultCode = provider.forceFlush()

    internal fun shutdown(): CompletableResultCode = provider.shutdown()
}

private enum class FileDestinationAdmission {
    Admitted,
    Rejected,
}

/**
 * Uses the pinned SDK's experimental OTLP JSON writer behind a Kast-owned stable boundary.
 * Migration owner: runtime/telemetry. Its emitted file shape is verified before dependency updates.
 */
private class ClosingSpanExporter(
    private val delegate: SpanExporter,
    private val output: OutputStream,
) : SpanExporter {
    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode =
        delegate.export(spans)

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown().whenComplete {
        try {
            output.close()
        } catch (_: IOException) {
            // The completed operation remains true when teardown can no longer write telemetry.
        }
    }
}
