package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.skill.KastDiscoverRequest
import io.github.amichne.kast.api.contract.skill.KastResolveRequest
import io.github.amichne.kast.api.contract.skill.WrapperNamedSymbolKind
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeclarationScopeTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `DeclarationScope round-trips through JSON`() {
        val scope = DeclarationScope(
            startOffset = 10,
            endOffset = 50,
            startLine = 2,
            endLine = 5,
            sourceText = "fun greet(name: String): String = \"hi\"",
        )

        val encoded = json.encodeToString(DeclarationScope.serializer(), scope)
        val decoded = json.decodeFromString(DeclarationScope.serializer(), encoded)

        assertEquals(scope, decoded)
    }

    @Test
    fun `DeclarationScope without sourceText omits field in JSON`() {
        val scope = DeclarationScope(
            startOffset = 0,
            endOffset = 20,
            startLine = 1,
            endLine = 1,
        )

        val encoded = json.encodeToString(DeclarationScope.serializer(), scope)
        val decoded = json.decodeFromString(DeclarationScope.serializer(), encoded)

        assertEquals(scope, decoded)
        assertNull(decoded.sourceText)
    }

    @Test
    fun `Symbol with declarationScope round-trips`() {
        val symbol = Symbol(
            fqName = "sample.greet",
            kind = SymbolKind.FUNCTION,
            location = Location(
                filePath = "/tmp/Sample.kt",
                startOffset = 15,
                endOffset = 20,
                startLine = 2,
                startColumn = 5,
                preview = "fun greet()",
            ),
            declarationScope = DeclarationScope(
                startOffset = 10,
                endOffset = 50,
                startLine = 2,
                endLine = 4,
                sourceText = "fun greet(name: String): String = \"hi\"",
            ),
        )

        val encoded = json.encodeToString(Symbol.serializer(), symbol)
        val decoded = json.decodeFromString(Symbol.serializer(), encoded)

        assertEquals(symbol, decoded)
        assertNotNull(decoded.declarationScope)
        assertEquals(10, decoded.declarationScope!!.startOffset)
    }

    @Test
    fun `Symbol omits declarationScope by default`() {
        val symbol = Symbol(
            fqName = "sample.greet",
            kind = SymbolKind.FUNCTION,
            location = Location(
                filePath = "/tmp/Sample.kt",
                startOffset = 15,
                endOffset = 20,
                startLine = 2,
                startColumn = 5,
                preview = "fun greet()",
            ),
        )

        val encoded = json.encodeToString(Symbol.serializer(), symbol)
        val decoded = json.decodeFromString(Symbol.serializer(), encoded)

        assertEquals(symbol, decoded)
        assertNull(decoded.declarationScope)
    }

    @Test
    fun `SymbolQuery with includeDeclarationScope round-trips`() {
        val query = SymbolQuery(
            position = FilePosition(filePath = "/tmp/Sample.kt", offset = 42),
            includeDeclarationScope = true,
        )

        val encoded = json.encodeToString(SymbolQuery.serializer(), query)
        val decoded = json.decodeFromString(SymbolQuery.serializer(), encoded)

        assertEquals(true, decoded.includeDeclarationScope)
    }

    @Test
    fun `SymbolQuery defaults includeDeclarationScope to false`() {
        val query = SymbolQuery(
            position = FilePosition(filePath = "/tmp/Sample.kt", offset = 42),
        )

        assertEquals(false, query.includeDeclarationScope)
    }

    @Test
    fun `WorkspaceSymbolQuery with includeDeclarationScope round-trips`() {
        val query = WorkspaceSymbolQuery(
            pattern = "MyClass",
            includeDeclarationScope = true,
        )

        val encoded = json.encodeToString(WorkspaceSymbolQuery.serializer(), query)
        val decoded = json.decodeFromString(WorkspaceSymbolQuery.serializer(), encoded)

        assertEquals(true, decoded.includeDeclarationScope)
    }

    @Test
    fun `WorkspaceSymbolQuery defaults includeDeclarationScope to false`() {
        val query = WorkspaceSymbolQuery(pattern = "test")

        assertEquals(false, query.includeDeclarationScope)
    }

    @Test
    fun `KastDiscoverRequest round-trips context fields`() {
        val query = KastDiscoverRequest(
            workspaceRoot = "/workspace",
            symbol = "key",
            fileHint = "/workspace/src/Event.kt",
            line = 42,
            codeSnippet = "val key = event.key",
            kind = WrapperNamedSymbolKind.PROPERTY,
            containingType = "com.example.Event",
            maxResults = 3,
            includeDeclarationScope = true,
        )

        val encoded = json.encodeToString(KastDiscoverRequest.serializer(), query)
        val decoded = json.decodeFromString(KastDiscoverRequest.serializer(), encoded)

        assertEquals(query, decoded)
    }

    @Test
    fun `KastDiscoverRequest defaults are bounded for agent discovery`() {
        val query = KastDiscoverRequest(symbol = "key")

        assertEquals(10, query.maxResults)
        assertEquals(false, query.includeDeclarationScope)
    }

    @Test
    fun `KastResolveRequest round-trips context flags`() {
        val query = KastResolveRequest(
            workspaceRoot = "/workspace",
            symbol = "key",
            fileHint = "/workspace/src/Event.kt",
            kind = WrapperNamedSymbolKind.PROPERTY,
            containingType = "com.example.Event",
            includeDeclarationScope = true,
            includeDocumentation = true,
            surroundingLines = 4,
            includeSurroundingMembers = true,
        )

        val encoded = json.encodeToString(KastResolveRequest.serializer(), query)
        val decoded = json.decodeFromString(KastResolveRequest.serializer(), encoded)

        assertEquals(query, decoded)
    }

    @Test
    fun `KastResolveRequest context flags default off`() {
        val query = KastResolveRequest(symbol = "key")

        assertEquals(false, query.includeDeclarationScope)
        assertEquals(false, query.includeDocumentation)
        assertNull(query.surroundingLines)
        assertEquals(false, query.includeSurroundingMembers)
    }

    @Test
    fun `schema version is now 4`() {
        assertEquals(4, SCHEMA_VERSION)
    }
}
