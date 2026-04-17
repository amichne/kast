package io.github.amichne.kast.api

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
}
