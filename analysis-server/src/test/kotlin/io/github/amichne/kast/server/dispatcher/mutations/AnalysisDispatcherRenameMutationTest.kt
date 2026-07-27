package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisDispatcherRenameMutationTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `symbol rename dispatches rename apply and diagnostics`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastRenameResponse>(
            method = "symbol/rename",
            params = json.encodeToJsonElement(
                KastRenameRequest.serializer(),
                KastRenameBySymbolRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                    newName = "hello",
                ),
            ),
        )

        val success = result as KastRenameSuccessResponse
        assertEquals(true, success.ok)
        assertEquals(1, success.affectedFiles.size)
        assertTrue(file.readText().contains("fun hello()"))
    }

    @Test
    fun `selector handle renames exact subject without name resolution`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val file = sampleFile()
        val selector = KastExactSymbolSelector(
            fqName = "sample.greet",
            declarationFile = file.toString(),
            declarationStartOffset = file.readText().indexOf("greet"),
            kind = SymbolKind.FUNCTION,
            containingType = "sample",
        )
        var resolveCalls = 0
        var renameCalls = 0
        val backend = object : AnalysisBackend by delegate {
            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
                resolveCalls += 1
                return delegate.resolveSymbol(query)
            }

            override suspend fun rename(query: ParsedRenameQuery): RenameResult {
                renameCalls += 1
                return delegate.rename(query)
            }
        }
        val selectorHandle = assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            backend.selectorHandles.issue(
                selector = selector,
                allowedFamilies = setOf(SelectorOperationFamily.RENAME),
            ),
        ).handle.value
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/rename",
                    params = json.encodeToJsonElement(
                        KastRenameRequest.serializer(),
                        KastRenameBySelectorHandleRequest(
                            workspaceRoot = tempDir.toString(),
                            selectorHandle = selectorHandle,
                            newName = "hello",
                        ),
                    ),
                ),
            )
        }
        val rpc = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastRenameResponse.serializer(), rpc.result)

        assertInstanceOf(KastRenameSuccessResponse::class.java, result)
        assertTrue(file.readText().contains("fun hello()"))
        assertEquals(0, resolveCalls)
        assertEquals(1, renameCalls)
    }

    @Test
    fun `rename backend cannot omit affected files to bypass refresh preflight`() {
        val backend = MissingRefreshRenameBackend(FakeAnalysisBackend.sample(tempDir))
        val file = sampleFile()
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/rename",
                    params = json.encodeToJsonElement(
                        KastRenameRequest.serializer(),
                        KastRenameBySymbolRequest(
                            workspaceRoot = tempDir.toString(),
                            symbol = "greet",
                            fileHint = file.toString(),
                            newName = "hello",
                        ),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals("CAPABILITY_NOT_SUPPORTED", error.error.data?.code)
        assertEquals(0, backend.applyCalls)
        assertTrue(file.readText().contains("fun greet()"))
    }

    @Test
    fun `symbol write and validate insert computes file hashes before apply`() {
        val backend = CapturingApplyEditsBackend(FakeAnalysisBackend.sample(tempDir))
        val file = sampleFile()
        val originalContent = file.readText()
        val content = "\nfun added() = Unit\n"
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/write-and-validate",
                    params = json.encodeToJsonElement(
                        KastWriteAndValidateRequest.serializer(),
                        KastWriteAndValidateInsertAtOffsetRequest(
                            workspaceRoot = tempDir.toString(),
                            filePath = file.toString(),
                            offset = originalContent.length,
                            content = content,
                        ),
                    ),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(
            KastWriteAndValidateResponse.serializer(),
            response.result,
        )

        val success = result as KastWriteAndValidateSuccessResponse
        assertEquals(true, success.ok)
        assertEquals(1, success.appliedEdits)
        assertEquals(
            listOf(file.toString() to FileHashing.sha256(originalContent)),
            backend.appliedFileHashes,
        )
        assertTrue(file.readText().endsWith(content))
    }

    @Test
    fun `symbol replace declaration dispatches declaration scope edit`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("replacement-content.kt")
        Files.writeString(contentFile, "fun greet() = \"bye\"")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/replace-declaration",
            params = json.encodeToJsonElement(
                KastReplaceDeclarationRequest.serializer(),
                KastReplaceDeclarationBySymbolRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    contentFile = contentFile.toString(),
                    fileHint = targetFile.toString(),
                    kind = WrapperNamedSymbolKind.FUNCTION,
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.REPLACE_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertEquals(1, success.editCount)
        assertTrue(targetFile.readText().contains("fun greet() = \"bye\""))
        assertFalse(targetFile.readText().contains("fun greet() = \"hi\""))
    }

    @Test
    fun `selector handle replaces exact declaration without named discovery`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val targetFile = sampleFile()
        val declarationOffset = targetFile.readText().indexOf("greet")
        val replacementFile = tempDir.resolve("handle-replacement-content.kt")
        Files.writeString(replacementFile, "fun greet() = \"handle\"")
        var workspaceSymbolCalls = 0
        val resolvedPositions = mutableListOf<Pair<String, Int>>()
        val backend = object : AnalysisBackend by delegate {
            override suspend fun workspaceSymbolSearch(
                query: ParsedWorkspaceSymbolQuery,
            ): WorkspaceSymbolResult {
                workspaceSymbolCalls += 1
                return delegate.workspaceSymbolSearch(query)
            }

            override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
                resolvedPositions += query.position.filePath.value to query.position.offset.value
                return delegate.resolveSymbol(query)
            }
        }
        val selectorHandle = assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            backend.selectorHandles.issue(
                selector = KastExactSymbolSelector(
                    fqName = "sample.greet",
                    declarationFile = targetFile.toString(),
                    declarationStartOffset = declarationOffset,
                    kind = SymbolKind.FUNCTION,
                    containingType = "sample",
                ),
                allowedFamilies = setOf(SelectorOperationFamily.REPLACE_DECLARATION),
            ),
        ).handle.value
        val dispatcher = RpcAnalysisDispatcher(backend = backend, config = AnalysisServerConfig())

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/replace-declaration",
                    params = json.encodeToJsonElement(
                        KastReplaceDeclarationRequest.serializer(),
                        KastReplaceDeclarationBySelectorHandleRequest(
                            workspaceRoot = tempDir.toString(),
                            selectorHandle = selectorHandle,
                            contentFile = replacementFile.toString(),
                        ),
                    ),
                ),
            )
        }
        val rpc = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(KastScopeMutationResponse.serializer(), rpc.result)

        assertInstanceOf(KastScopeMutationSuccessResponse::class.java, result)
        assertTrue(targetFile.readText().contains("fun greet() = \"handle\""))
        assertEquals(0, workspaceSymbolCalls)
        assertEquals(listOf(targetFile.toString() to declarationOffset), resolvedPositions)
    }

    @Test
    fun `symbol replace declaration resolves fully qualified names through simple-name search`() {
        val targetFile = sampleFile()
        val contentFile = tempDir.resolve("fq-replacement-content.kt")
        Files.writeString(contentFile, "fun greet() = \"fq\"")

        val result = dispatchSuccess<KastScopeMutationResponse>(
            method = "symbol/replace-declaration",
            params = json.encodeToJsonElement(
                KastReplaceDeclarationRequest.serializer(),
                KastReplaceDeclarationBySymbolRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "sample.greet",
                    contentFile = contentFile.toString(),
                    kind = WrapperNamedSymbolKind.FUNCTION,
                ),
            ),
        )

        val success = result as KastScopeMutationSuccessResponse
        assertEquals(KastScopeMutationOperation.REPLACE_DECLARATION, success.operation)
        assertEquals(true, success.applied)
        assertEquals(1, success.editCount)
        assertTrue(targetFile.readText().contains("fun greet() = \"fq\""))
        assertFalse(targetFile.readText().contains("fun greet() = \"hi\""))
    }
}
