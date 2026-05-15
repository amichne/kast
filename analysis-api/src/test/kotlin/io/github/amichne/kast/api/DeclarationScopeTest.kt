package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
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
    fun `Symbol without declarationScope remains backward compatible`() {
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
    fun `SourceSnippet round-trips through JSON`() {
        val snippet = SourceSnippet(
            startLine = 3,
            endLine = 5,
            focusLine = 4,
            sourceText = "fun greet(name: String) = greeting(name)",
            truncated = true,
        )

        val encoded = json.encodeToString(SourceSnippet.serializer(), snippet)
        val decoded = json.decodeFromString(SourceSnippet.serializer(), encoded)

        assertEquals(snippet, decoded)
    }

    @Test
    fun `Symbol with surrounding context round-trips`() {
        val sibling = Symbol(
            fqName = "sample.helper",
            kind = SymbolKind.FUNCTION,
            location = Location(
                filePath = "/tmp/Sample.kt",
                startOffset = 50,
                endOffset = 56,
                startLine = 5,
                startColumn = 5,
                preview = "fun helper()",
            ),
        )
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
            surroundingMembers = listOf(sibling),
            surroundingLines = SourceSnippet(
                startLine = 1,
                endLine = 6,
                focusLine = 2,
                sourceText = "package sample\n\nfun greet()\nfun helper()",
            ),
        )

        val encoded = json.encodeToString(Symbol.serializer(), symbol)
        val decoded = json.decodeFromString(Symbol.serializer(), encoded)

        assertEquals(symbol, decoded)
        assertEquals(1, decoded.surroundingMembers!!.size)
        assertEquals(2, decoded.surroundingLines!!.focusLine)
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
    fun `SymbolQuery with surrounding context options round-trips`() {
        val query = SymbolQuery(
            position = FilePosition(filePath = "/tmp/Sample.kt", offset = 42),
            includeSurroundingMembers = true,
            surroundingLines = 4,
        )

        val encoded = json.encodeToString(SymbolQuery.serializer(), query)
        val decoded = json.decodeFromString(SymbolQuery.serializer(), encoded)

        assertEquals(true, decoded.includeSurroundingMembers)
        assertEquals(4, decoded.surroundingLines)
    }

    @Test
    fun `SymbolQuery defaults surrounding context to off`() {
        val query = SymbolQuery(
            position = FilePosition(filePath = "/tmp/Sample.kt", offset = 42),
        )

        assertEquals(false, query.includeSurroundingMembers)
        assertEquals(0, query.surroundingLines)
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
    fun `schema version is now 3`() {
        assertEquals(3, SCHEMA_VERSION)
    }
}
