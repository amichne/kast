package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

internal data class FakeAnalysisBackendSpec(
    val workspaceRoot: Path,
    val symbol: Symbol,
    val symbolAnchors: List<Location>,
    val referenceLocations: List<Location>,
    val diagnosticsByFile: Map<String, List<Diagnostic>>,
    val typeHierarchyRootSymbol: Symbol,
    val typeHierarchyAnchors: List<Location>,
    val typeHierarchySupertypeSymbol: Symbol,
    val typeHierarchySubtypeSymbol: Symbol,
    val limits: ServerLimits,
    val backendName: String,
)

internal fun sampleFakeAnalysisBackendSpec(
    workspaceRoot: Path,
    limits: ServerLimits = ServerLimits(
        maxResults = 100,
        requestTimeoutMillis = 30_000,
        maxConcurrentRequests = 4,
    ),
    backendName: String = "fake",
): FakeAnalysisBackendSpec {
    val sourceDirectory = workspaceRoot.resolve("src")
    Files.createDirectories(sourceDirectory)
    val file = sourceDirectory.resolve("Sample.kt")
    val content = """
        package sample

        fun greet() = "hi"

        fun use() = greet()
    """.trimIndent() + "\n"
    file.writeText(content)
    val typeFile = sourceDirectory.resolve("Types.kt")
    val typeContent = """
        package sample

        interface Greeter
        open class FriendlyGreeter : Greeter
        class LoudGreeter : FriendlyGreeter()
    """.trimIndent() + "\n"
    typeFile.writeText(typeContent)

    val declarationOffset = content.indexOf("greet")
    val referenceOffset = content.lastIndexOf("greet")
    val symbolLocation = referenceLocation(file.toString(), declarationOffset)
    val referenceLocation = referenceLocation(file.toString(), referenceOffset)
    val typeHierarchySupertypeLocation = declarationLocation(
        filePath = typeFile.toString(),
        token = "Greeter",
        content = typeContent,
        line = 3,
        column = 11,
    )
    val typeHierarchyRootLocation = declarationLocation(
        filePath = typeFile.toString(),
        token = "FriendlyGreeter",
        content = typeContent,
        line = 4,
        column = 12,
    )
    val typeHierarchySubtypeLocation = declarationLocation(
        filePath = typeFile.toString(),
        token = "LoudGreeter",
        content = typeContent,
        line = 5,
        column = 7,
    )
    val symbol = Symbol(
        fqName = "sample.greet",
        kind = SymbolKind.FUNCTION,
        location = symbolLocation,
        returnType = "String",
        parameters = listOf(
            ParameterInfo(
                name = "name",
                type = "String",
            ),
        ),
        documentation = "/** Greets the provided name. */",
        containingDeclaration = "sample",
    )
    val typeHierarchyRootSymbol = Symbol(
        fqName = "sample.FriendlyGreeter",
        kind = SymbolKind.CLASS,
        location = typeHierarchyRootLocation,
        containingDeclaration = "sample",
        supertypes = listOf("sample.Greeter"),
    )
    val typeHierarchySupertypeSymbol = Symbol(
        fqName = "sample.Greeter",
        kind = SymbolKind.INTERFACE,
        location = typeHierarchySupertypeLocation,
        containingDeclaration = "sample",
    )
    val typeHierarchySubtypeSymbol = Symbol(
        fqName = "sample.LoudGreeter",
        kind = SymbolKind.CLASS,
        location = typeHierarchySubtypeLocation,
        containingDeclaration = "sample",
        supertypes = listOf("sample.FriendlyGreeter"),
    )

    return FakeAnalysisBackendSpec(
        workspaceRoot = workspaceRoot,
        symbol = symbol,
        symbolAnchors = listOf(symbolLocation, referenceLocation),
        referenceLocations = listOf(referenceLocation),
        diagnosticsByFile = emptyMap(),
        typeHierarchyRootSymbol = typeHierarchyRootSymbol,
        typeHierarchyAnchors = listOf(typeHierarchyRootLocation),
        typeHierarchySupertypeSymbol = typeHierarchySupertypeSymbol,
        typeHierarchySubtypeSymbol = typeHierarchySubtypeSymbol,
        limits = limits,
        backendName = backendName,
    )
}

internal fun contractFakeAnalysisBackendSpec(
    fixture: AnalysisBackendContractFixture,
    limits: ServerLimits = ServerLimits(
        maxResults = 100,
        requestTimeoutMillis = 30_000,
        maxConcurrentRequests = 4,
    ),
    backendName: String = "fake",
): FakeAnalysisBackendSpec {
    val symbol = Symbol(
        fqName = fixture.symbolFqName,
        kind = SymbolKind.FUNCTION,
        location = fixture.declarationLocation,
        returnType = "String",
        parameters = listOf(ParameterInfo(name = "name", type = "String")),
        documentation = "/** Contract fixture symbol. */",
        containingDeclaration = "sample",
    )
    val typeHierarchyRootSymbol = Symbol(
        fqName = fixture.typeHierarchyRootFqName,
        kind = SymbolKind.CLASS,
        location = fixture.typeHierarchyRootLocation,
        containingDeclaration = "sample",
        supertypes = fixture.typeHierarchyRootSupertypes,
    )
    val typeHierarchySupertypeSymbol = Symbol(
        fqName = "sample.Greeter",
        kind = SymbolKind.INTERFACE,
        location = fixture.typeHierarchySupertypeLocation,
        containingDeclaration = "sample",
    )
    val typeHierarchySubtypeSymbol = Symbol(
        fqName = "sample.LoudGreeter",
        kind = SymbolKind.CLASS,
        location = fixture.typeHierarchySubtypeLocation,
        containingDeclaration = "sample",
        supertypes = listOf(fixture.typeHierarchyRootFqName),
    )

    return FakeAnalysisBackendSpec(
        workspaceRoot = fixture.workspaceRoot,
        symbol = symbol,
        symbolAnchors = listOf(
            fixture.declarationLocation,
            fixture.firstUsageLocation,
            fixture.secondUsageLocation,
        ),
        referenceLocations = fixture.referenceLocations,
        diagnosticsByFile = mapOf(
            fixture.brokenFile.toString() to listOf(
                Diagnostic(
                    location = Location(
                        filePath = fixture.brokenFile.toString(),
                        startOffset = 0,
                        endOffset = 0,
                        startLine = 3,
                        startColumn = 1,
                        preview = fixture.brokenPreview,
                    ),
                    severity = DiagnosticSeverity.ERROR,
                    message = "The fake contract fixture reports a syntax error",
                    code = "FAKE_PARSE_ERROR",
                ),
            ),
        ),
        typeHierarchyRootSymbol = typeHierarchyRootSymbol,
        typeHierarchyAnchors = listOf(fixture.typeHierarchyRootLocation),
        typeHierarchySupertypeSymbol = typeHierarchySupertypeSymbol,
        typeHierarchySubtypeSymbol = typeHierarchySubtypeSymbol,
        limits = limits,
        backendName = backendName,
    )
}

private fun referenceLocation(
    filePath: String,
    offset: Int,
): Location {
    val line = if (offset < 15) 2 else 4
    val column = if (offset < 15) 5 else 13
    return Location(
        filePath = filePath,
        startOffset = offset,
        endOffset = offset + "greet".length,
        startLine = line,
        startColumn = column,
        preview = "greet",
    )
}

private fun declarationLocation(
    filePath: String,
    token: String,
    content: String,
    line: Int,
    column: Int,
): Location {
    val offset = content.indexOf(token)
    return Location(
        filePath = filePath,
        startOffset = offset,
        endOffset = offset + token.length,
        startLine = line,
        startColumn = column,
        preview = content.lineSequence().drop(line - 1).first().trimEnd(),
    )
}
