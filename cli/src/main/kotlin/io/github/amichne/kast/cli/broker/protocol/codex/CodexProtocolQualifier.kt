package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.provider.BrokerExecutable
import io.github.amichne.kast.cli.broker.provider.BrokerProcessExecution
import io.github.amichne.kast.cli.broker.provider.BrokerProcessExecutor
import io.github.amichne.kast.cli.broker.provider.BrokerProcessRequest
import io.github.amichne.kast.cli.broker.provider.JdkBrokerProcessExecutor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.HexFormat
import java.util.UUID

internal enum class CodexProtocolOptionsFailure {
    CODEX_EXECUTABLE_REJECTED,
    CODEX_HOME_REJECTED,
    TEMPORARY_ROOT_REJECTED,
    INVALID_LIMIT,
}

internal class CodexProtocolQualificationOptions private constructor(
    val codexExecutable: BrokerExecutable,
    val codexHome: CanonicalBrokerDirectory,
    val temporaryRoot: CanonicalBrokerDirectory,
    val processExecutor: BrokerProcessExecutor,
    val maximumSchemaBytes: Int,
    val maximumSchemaFiles: Int,
    val timeoutMillis: Long,
) {
    companion object {
        internal fun admit(
            codexExecutable: Path,
            codexHome: Path,
            temporaryRoot: Path,
            processExecutor: BrokerProcessExecutor = JdkBrokerProcessExecutor,
            maximumSchemaBytes: Int = 16 * 1_024 * 1_024,
            maximumSchemaFiles: Int = 2_048,
            timeoutMillis: Long = 30_000,
        ): Refinement<CodexProtocolQualificationOptions, CodexProtocolOptionsFailure> {
            val executable = when (val admitted = BrokerExecutable.admit(codexExecutable)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    CodexProtocolOptionsFailure.CODEX_EXECUTABLE_REJECTED,
                )
            }
            val home = CanonicalBrokerDirectory.admit(codexHome)
                ?: return Refinement.Rejected(CodexProtocolOptionsFailure.CODEX_HOME_REJECTED)
            val temporary = CanonicalBrokerDirectory.admit(temporaryRoot)
                ?: return Refinement.Rejected(CodexProtocolOptionsFailure.TEMPORARY_ROOT_REJECTED)
            if (maximumSchemaBytes <= 0 || maximumSchemaFiles <= 0 || timeoutMillis <= 0) {
                return Refinement.Rejected(CodexProtocolOptionsFailure.INVALID_LIMIT)
            }
            return Refinement.Refined(
                CodexProtocolQualificationOptions(
                    executable,
                    home,
                    temporary,
                    processExecutor,
                    maximumSchemaBytes,
                    maximumSchemaFiles,
                    timeoutMillis,
                ),
            )
        }
    }
}

@JvmInline
internal value class CodexVersion private constructor(val value: String) {
    companion object {
        internal fun admit(raw: String): CodexVersion? = raw.trim().takeIf { version ->
            version.isNotEmpty() && version.length <= 512 &&
                version.none { character ->
                    character == '\n' || character == '\r' || character == '\u0000'
                }
        }?.let(::CodexVersion)
    }
}

@JvmInline
internal value class CodexProtocolDigest private constructor(val value: String) {
    companion object {
        internal fun derive(files: List<CollectedCodexSchema>): CodexProtocolDigest {
            val digest = MessageDigest.getInstance("SHA-256")
            files.forEach { file ->
                digest.update(file.relativePath.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(file.bytes)
                digest.update(0)
            }
            return CodexProtocolDigest("sha256:${HexFormat.of().formatHex(digest.digest())}")
        }
    }
}

internal enum class CodexProtocolQualificationFailure {
    VERSION_UNAVAILABLE,
    VERSION_INVALID,
    SCHEMA_DIRECTORY_REJECTED,
    SCHEMA_GENERATION_REJECTED,
    SCHEMA_ENTRY_REJECTED,
    SCHEMA_FILE_LIMIT_EXCEEDED,
    SCHEMA_BYTE_LIMIT_EXCEEDED,
    MISSING_REQUIRED_SCHEMA,
    AMBIGUOUS_REQUIRED_SCHEMA,
    INVALID_REQUIRED_SCHEMA,
    CONTRACT_COMPILATION_REJECTED,
    TEMPORARY_RETIREMENT_REJECTED,
    TIMED_OUT,
}

internal sealed interface CodexProtocolQualification {
    data class Qualified(
        val version: CodexVersion,
        val protocolDigest: CodexProtocolDigest,
        val schemaFileCount: Int,
        val contracts: CodexProtocolContracts,
    ) : CodexProtocolQualification

    data class Rejected(
        val failure: CodexProtocolQualificationFailure,
    ) : CodexProtocolQualification
}

internal object CodexProtocolQualifier {
    internal suspend fun qualify(
        options: CodexProtocolQualificationOptions,
    ): CodexProtocolQualification {
        val version = when (
            val execution = execute(
                options,
                listOf("--version"),
                MAXIMUM_COMMAND_OUTPUT_BYTES,
            )
        ) {
            is BoundedCodexExecution.Completed -> {
                if (execution.result.exitCode != 0) {
                    return rejected(CodexProtocolQualificationFailure.VERSION_UNAVAILABLE)
                }
                CodexVersion.admit(execution.result.stdout)
                    ?: return rejected(CodexProtocolQualificationFailure.VERSION_INVALID)
            }
            BoundedCodexExecution.Rejected ->
                return rejected(CodexProtocolQualificationFailure.VERSION_UNAVAILABLE)
            BoundedCodexExecution.TimedOut ->
                return rejected(CodexProtocolQualificationFailure.TIMED_OUT)
        }
        val schemaDirectory = try {
            Files.createTempDirectory(
                options.temporaryRoot.path,
                "kast-codex-protocol-${UUID.randomUUID()}-",
            ).toRealPath()
        } catch (_: IOException) {
            return rejected(CodexProtocolQualificationFailure.SCHEMA_DIRECTORY_REJECTED)
        } catch (_: SecurityException) {
            return rejected(CodexProtocolQualificationFailure.SCHEMA_DIRECTORY_REJECTED)
        }
        var result: CodexProtocolQualification = try {
            val generation = execute(
                options,
                listOf(
                    "app-server",
                    "generate-json-schema",
                    "--experimental",
                    "--out",
                    schemaDirectory.toString(),
                ),
                MAXIMUM_COMMAND_OUTPUT_BYTES,
            )
            when (generation) {
                is BoundedCodexExecution.Completed -> if (generation.result.exitCode == 0) {
                    qualifyGeneratedSchemas(options, schemaDirectory, version)
                } else {
                    rejected(CodexProtocolQualificationFailure.SCHEMA_GENERATION_REJECTED)
                }
                BoundedCodexExecution.Rejected ->
                    rejected(CodexProtocolQualificationFailure.SCHEMA_GENERATION_REJECTED)
                BoundedCodexExecution.TimedOut ->
                    rejected(CodexProtocolQualificationFailure.TIMED_OUT)
            }
        } catch (_: RuntimeException) {
            rejected(CodexProtocolQualificationFailure.SCHEMA_ENTRY_REJECTED)
        }
        if (!retireTemporaryTree(schemaDirectory)) {
            result = rejected(CodexProtocolQualificationFailure.TEMPORARY_RETIREMENT_REJECTED)
        }
        return result
    }

    private suspend fun execute(
        options: CodexProtocolQualificationOptions,
        arguments: List<String>,
        maximumOutputBytes: Int,
    ): BoundedCodexExecution = try {
        val request = when (
            val admission = BrokerProcessRequest.admit(
                executable = options.codexExecutable,
                arguments = arguments,
                workingDirectory = options.temporaryRoot,
                maximumOutputBytes = maximumOutputBytes,
                timeoutMillis = options.timeoutMillis,
                environment = mapOf("CODEX_HOME" to options.codexHome.path.toString()),
            )
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return BoundedCodexExecution.Rejected
        }
        val execution = withTimeout(options.timeoutMillis) {
            options.processExecutor.execute(request)
        }
        if (execution is BrokerProcessExecution.Completed) {
            BoundedCodexExecution.Completed(execution)
        } else {
            BoundedCodexExecution.Rejected
        }
    } catch (_: TimeoutCancellationException) {
        BoundedCodexExecution.TimedOut
    }

    private fun qualifyGeneratedSchemas(
        options: CodexProtocolQualificationOptions,
        root: Path,
        version: CodexVersion,
    ): CodexProtocolQualification {
        val collection = collectSchemas(root, options)
        val files = when (collection) {
            is CodexSchemaCollection.Collected -> collection.files
            is CodexSchemaCollection.Rejected -> return rejected(collection.failure)
        }
        val documents = linkedMapOf<CodexOwnedSchema, JsonObject>()
        CodexOwnedSchema.entries.forEach { required ->
            val matches = files.filter { file ->
                Path.of(file.relativePath).fileName.toString() == required.fileName
            }
            if (matches.isEmpty()) {
                return rejected(CodexProtocolQualificationFailure.MISSING_REQUIRED_SCHEMA)
            }
            if (matches.size != 1) {
                return rejected(CodexProtocolQualificationFailure.AMBIGUOUS_REQUIRED_SCHEMA)
            }
            val document = try {
                Json.parseToJsonElement(matches.single().bytes.toString(StandardCharsets.UTF_8))
                    as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return rejected(CodexProtocolQualificationFailure.INVALID_REQUIRED_SCHEMA)
            documents[required] = document
        }
        val contracts = when (val definition = CodexProtocolContracts.define(documents)) {
            is Validation.Validated -> definition.value
            is Validation.Rejected -> return rejected(
                CodexProtocolQualificationFailure.CONTRACT_COMPILATION_REJECTED,
            )
        }
        return CodexProtocolQualification.Qualified(
            version,
            CodexProtocolDigest.derive(files),
            files.size,
            contracts,
        )
    }

    private fun collectSchemas(
        root: Path,
        options: CodexProtocolQualificationOptions,
    ): CodexSchemaCollection {
        val paths = mutableListOf<Path>()
        val pending = ArrayDeque<Path>()
        pending.add(root)
        try {
            while (pending.isNotEmpty()) {
                val directory = pending.removeLast()
                Files.newDirectoryStream(directory).use { entries ->
                    entries.forEach { entry ->
                        val attributes = Files.readAttributes(
                            entry,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        when {
                            attributes.isSymbolicLink -> return rejectedCollection(
                                CodexProtocolQualificationFailure.SCHEMA_ENTRY_REJECTED,
                            )
                            attributes.isDirectory -> pending.add(entry)
                            attributes.isRegularFile && entry.fileName.toString().endsWith(".json") -> {
                                paths.add(entry)
                                if (paths.size > options.maximumSchemaFiles) {
                                    return rejectedCollection(
                                        CodexProtocolQualificationFailure.SCHEMA_FILE_LIMIT_EXCEEDED,
                                    )
                                }
                            }
                            else -> return rejectedCollection(
                                CodexProtocolQualificationFailure.SCHEMA_ENTRY_REJECTED,
                            )
                        }
                    }
                }
            }
            val ordered = paths.sortedBy { path -> root.relativize(path).toString() }
            val files = mutableListOf<CollectedCodexSchema>()
            var totalBytes = 0L
            ordered.forEach { path ->
                val read = readExactFile(path, options.maximumSchemaBytes - totalBytes)
                    ?: return rejectedCollection(
                        CodexProtocolQualificationFailure.SCHEMA_ENTRY_REJECTED,
                    )
                totalBytes += read.size
                if (totalBytes > options.maximumSchemaBytes) {
                    return rejectedCollection(
                        CodexProtocolQualificationFailure.SCHEMA_BYTE_LIMIT_EXCEEDED,
                    )
                }
                files += CollectedCodexSchema(
                    root.relativize(path).toString().replace(path.fileSystem.separator, "/"),
                    read,
                )
            }
            return CodexSchemaCollection.Collected(files)
        } catch (_: IOException) {
            return rejectedCollection(CodexProtocolQualificationFailure.SCHEMA_ENTRY_REJECTED)
        } catch (_: SecurityException) {
            return rejectedCollection(CodexProtocolQualificationFailure.SCHEMA_ENTRY_REJECTED)
        }
    }

    private fun readExactFile(path: Path, remainingBytes: Long): ByteArray? {
        if (remainingBytes < 0) return ByteArray((remainingBytes + 1).coerceAtLeast(1).toInt())
        val before = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!before.isRegularFile || before.isSymbolicLink || before.fileKey() == null) return null
        val output = ByteArrayOutputStream()
        val channel = Files.newByteChannel(
            path,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        )
        channel.use { source ->
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > remainingBytes) {
                    return ByteArray((remainingBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        val after = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return output.toByteArray().takeIf {
            after.isRegularFile && !after.isSymbolicLink && after.fileKey() == before.fileKey()
        }
    }

    private fun retireTemporaryTree(root: Path): Boolean = try {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun rejected(
        failure: CodexProtocolQualificationFailure,
    ): CodexProtocolQualification.Rejected = CodexProtocolQualification.Rejected(failure)

    private fun rejectedCollection(
        failure: CodexProtocolQualificationFailure,
    ): CodexSchemaCollection.Rejected = CodexSchemaCollection.Rejected(failure)

    private const val MAXIMUM_COMMAND_OUTPUT_BYTES = 1 * 1_024 * 1_024
}

internal data class CollectedCodexSchema(
    val relativePath: String,
    val bytes: ByteArray,
)

private sealed interface CodexSchemaCollection {
    data class Collected(val files: List<CollectedCodexSchema>) : CodexSchemaCollection
    data class Rejected(
        val failure: CodexProtocolQualificationFailure,
    ) : CodexSchemaCollection
}

private sealed interface BoundedCodexExecution {
    data class Completed(val result: BrokerProcessExecution.Completed) : BoundedCodexExecution
    data object Rejected : BoundedCodexExecution
    data object TimedOut : BoundedCodexExecution
}
