package io.github.amichne.kast.api.wrapper

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WrapperContractModelTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        classDiscriminator = "type"
    }

    @Test
    fun `rename request serializes with caps cased type discriminator`() {
        val encoded = json.encodeToString<KastRenameRequest>(
            KastRenameBySymbolRequest(
                workspaceRoot = "/workspace",
                symbol = "sample.greet",
                fileHint = "/workspace/src/main/kotlin/sample/Greeter.kt",
                newName = "welcome",
            ),
        )

        assertTrue(encoded.contains(""""type":"RENAME_BY_SYMBOL_REQUEST""""))
    }

    @Test
    fun `write and validate request serializes with caps cased type discriminator`() {
        val encoded = json.encodeToString<KastWriteAndValidateRequest>(
            KastWriteAndValidateReplaceRangeRequest(
                workspaceRoot = "/workspace",
                filePath = "/workspace/src/main/kotlin/sample/Greeter.kt",
                startOffset = 10,
                endOffset = 20,
                content = "fun welcome() = Unit",
            ),
        )

        assertTrue(encoded.contains(""""type":"REPLACE_RANGE_REQUEST""""))
    }

    @Test
    fun `resolve success response serializes candidate count with camelCase`() {
        val encoded = json.encodeToString<KastResolveResponse>(
            KastResolveSuccessResponse(
                query = KastResolveQuery(workspaceRoot = "/workspace", symbol = "sample.greet"),
                symbol = Symbol(
                    fqName = "sample.Greeter",
                    kind = SymbolKind.CLASS,
                    location = Location(
                        filePath = "/workspace/src/main/kotlin/sample/Greeter.kt",
                        startOffset = 10,
                        endOffset = 17,
                        startLine = 1,
                        startColumn = 7,
                        preview = "class Greeter",
                    ),
                ),
                filePath = "/workspace/src/main/kotlin/sample/Greeter.kt",
                offset = 10,
                candidate = KastCandidate(line = 1, column = 7, context = "class Greeter"),
                candidateCount = 3,
                alternatives = listOf("sample.OtherGreeter"),
                logFile = "/tmp/log.txt",
            ),
        )

        assertTrue(encoded.contains(""""candidateCount":3"""))
        assertTrue(encoded.contains(""""alternatives":["sample.OtherGreeter"]"""))
    }
}
