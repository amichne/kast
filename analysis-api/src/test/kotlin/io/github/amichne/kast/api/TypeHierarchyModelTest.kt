package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TypeHierarchyModelTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `type hierarchy query serializes with defaults`() {
        val query = TypeHierarchyQuery(
            position = FilePosition(
                filePath = "/tmp/Sample.kt",
                offset = 12,
            ),
        )

        val encoded = json.encodeToString(TypeHierarchyQuery.serializer(), query)
        val decoded = json.decodeFromString(TypeHierarchyQuery.serializer(), encoded)

        assertEquals(TypeHierarchyDirection.BOTH, decoded.direction)
        assertEquals(3, decoded.depth)
        assertEquals(256, decoded.maxResults)
    }

    @Test
    fun `type hierarchy result includes schema version`() {
        val result = TypeHierarchyResult(
            root = TypeHierarchyNode(
                symbol = sampleSymbol(),
                children = emptyList(),
            ),
            stats = TypeHierarchyStats(
                totalNodes = 1,
                maxDepthReached = 0,
                truncated = false,
            ),
        )

        assertEquals(SCHEMA_VERSION, result.schemaVersion)
    }

    @Test
    fun `symbol with supertypes serializes correctly`() {
        val symbol = sampleSymbol(
            fqName = "sample.Foo",
            supertypes = listOf("kotlin.Any"),
        )

        val encoded = json.encodeToString(Symbol.serializer(), symbol)
        val decoded = json.decodeFromString(Symbol.serializer(), encoded)

        assertEquals(listOf("kotlin.Any"), decoded.supertypes)
    }

    @Test
    fun `symbol without supertypes omits field in JSON`() {
        val symbol = sampleSymbol()

        val encoded = json.encodeToString(Symbol.serializer(), symbol)

        assertFalse(encoded.contains("supertypes"))
    }

    private fun sampleSymbol(
        fqName: String = "sample.greet",
        supertypes: List<String>? = null,
    ): Symbol = Symbol(
        fqName = fqName,
        kind = SymbolKind.FUNCTION,
        location = Location(
            filePath = "/tmp/Sample.kt",
            startOffset = 0,
            endOffset = 5,
            startLine = 1,
            startColumn = 1,
            preview = "sample",
        ),
        supertypes = supertypes,
    )
}
