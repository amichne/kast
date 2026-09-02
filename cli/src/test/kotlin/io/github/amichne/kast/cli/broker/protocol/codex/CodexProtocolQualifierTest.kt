package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.provider.BrokerProcessExecution
import io.github.amichne.kast.cli.broker.provider.BrokerProcessExecutor
import io.github.amichne.kast.cli.broker.provider.BrokerProcessRequest
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class CodexProtocolQualifierTest {
    @Test
    fun `contracts reject an initialize schema incompatible with broker refinement`() {
        val objectSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        val closedInitialize = Json.parseToJsonElement(
            """
            {
              "type":"object",
              "required":["clientInfo","capabilities"],
              "additionalProperties":false,
              "properties":{
                "clientInfo":{"type":"object"},
                "capabilities":{"type":"object","additionalProperties":false,"properties":{}}
              }
            }
            """.trimIndent(),
        ).jsonObject

        val definition = CodexProtocolContracts.define(
            CodexOwnedSchema.entries.associateWith { schema ->
                if (schema == CodexOwnedSchema.INITIALIZE_PARAMS) closedInitialize else objectSchema
            },
        )

        assertInstanceOf(
            io.github.amichne.kast.kernel.Validation.Rejected::class.java,
            definition,
        )
    }

    @Test
    fun `qualification compiles the exact installed experimental schemas`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val tempRoot = Files.createDirectory(temporary.resolve("temp")).toRealPath()
        val executor = SchemaGeneratingExecutor()
        val options = CodexProtocolQualificationOptions.admit(
            codex,
            codexHome,
            tempRoot,
            executor,
        ).refinedValue()

        val qualification = assertInstanceOf(
            CodexProtocolQualification.Qualified::class.java,
            CodexProtocolQualifier.qualify(options),
        )

        assertEquals("codex-cli 9.9.9", qualification.version.value)
        assertEquals(CodexOwnedSchema.entries.size, qualification.schemaFileCount)
        assertEquals(
            listOf("app-server", "generate-json-schema", "--experimental", "--out"),
            executor.requests.last().arguments.take(4),
        )
        assertEquals(codexHome.toString(), executor.requests.last().environment["CODEX_HOME"])
        CodexOwnedSchema.entries.forEach { schema ->
            assertInstanceOf(
                io.github.amichne.kast.kernel.Validation.Validated::class.java,
                qualification.contracts.admit(schema, buildJsonObject {}),
            )
        }
        assertEquals(emptyList<Path>(), Files.list(tempRoot).use { paths -> paths.toList() })
    }

    @Test
    fun `duplicate required basename fails closed`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val tempRoot = Files.createDirectory(temporary.resolve("temp")).toRealPath()
        val executor = SchemaGeneratingExecutor(duplicate = CodexOwnedSchema.INITIALIZE_PARAMS)
        val options = CodexProtocolQualificationOptions.admit(
            codex,
            codexHome,
            tempRoot,
            executor,
        ).refinedValue()

        val rejection = assertInstanceOf(
            CodexProtocolQualification.Rejected::class.java,
            CodexProtocolQualifier.qualify(options),
        )

        assertEquals(
            CodexProtocolQualificationFailure.AMBIGUOUS_REQUIRED_SCHEMA,
            rejection.failure,
        )
        assertEquals(emptyList<Path>(), Files.list(tempRoot).use { paths -> paths.toList() })
    }

    private class SchemaGeneratingExecutor(
        private val duplicate: CodexOwnedSchema? = null,
    ) : BrokerProcessExecutor {
        val requests = mutableListOf<BrokerProcessRequest>()

        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
            requests += request
            if (request.arguments == listOf("--version")) {
                return BrokerProcessExecution.Completed(0, "codex-cli 9.9.9\n", "")
            }
            val output = Path.of(request.arguments.last())
            CodexOwnedSchema.entries.forEach { schema ->
                Files.writeString(output.resolve(schema.fileName), """{"type":"object"}""")
            }
            duplicate?.let { schema ->
                val nested = Files.createDirectory(output.resolve("nested"))
                Files.writeString(nested.resolve(schema.fileName), """{"type":"object"}""")
            }
            return BrokerProcessExecution.Completed(0, "", "")
        }
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }
}
