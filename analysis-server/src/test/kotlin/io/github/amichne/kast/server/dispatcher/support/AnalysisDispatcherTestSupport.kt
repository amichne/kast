package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path

abstract class AnalysisDispatcherTestSupport {
    @TempDir
    lateinit var tempDir: Path

    protected val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    protected fun sampleFile(): Path = tempDir.resolve("src").resolve("Sample.kt")

    protected fun sampleTypeFile(): Path = tempDir.resolve("src").resolve("Types.kt")

    protected fun dispatcher(): RpcAnalysisDispatcher = RpcAnalysisDispatcher(
        backend = FakeAnalysisBackend.sample(tempDir),
        config = AnalysisServerConfig(),
    )

    protected inline fun <reified T> dispatchSuccess(
        method: String,
        params: JsonElement? = null,
    ): T {
        val response = dispatchRaw(method, params)
        val success = json.decodeFromJsonElement(
            JsonRpcSuccessResponse.serializer(),
            response,
        )
        return json.decodeFromJsonElement(
            serializer<T>(),
            success.result,
        )
    }

    protected inline fun <reified T> dispatchSuccessWithBackend(
        backend: AnalysisBackend,
        config: AnalysisServerConfig = AnalysisServerConfig(),
        method: String,
        params: JsonElement? = null,
    ): T {
        val raw = runBlocking {
            RpcAnalysisDispatcher(backend = backend, config = config).dispatch(
                JsonRpcRequest(id = JsonPrimitive(1), method = method, params = params),
            )
        }
        val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        return json.decodeFromJsonElement(serializer<T>(), success.result)
    }

    protected fun lookupSymbol(
        fqName: String,
        kind: SymbolKind,
        fileName: String,
        startOffset: Int = 10,
        containingDeclaration: String? = null,
    ): Symbol = Symbol(
        fqName = fqName,
        kind = kind,
        location = Location(
            filePath = tempDir.resolve(fileName).toString(),
            startOffset = startOffset,
            endOffset = startOffset + fqName.substringAfterLast('.').length,
            startLine = startOffset,
            startColumn = 1,
            preview = fqName,
        ),
        containingDeclaration = containingDeclaration,
    )

    protected fun Symbol.exactSelector(): KastExactSymbolSelector = KastExactSymbolSelector(
        fqName = fqName,
        declarationFile = location.filePath,
        declarationStartOffset = location.startOffset,
        kind = kind,
        containingType = containingDeclaration,
    )

    protected fun dispatchRaw(
        method: String,
        params: JsonElement? = null,
    ): JsonObject {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = method,
            params = params,
        )
        val raw = runBlocking {
            dispatcher().dispatch(request)
        }
        return json.parseToJsonElement(raw).jsonObject
    }

    protected fun classFileText(clazz: Class<*>): String =
        checkNotNull(clazz.getResourceAsStream("${clazz.simpleName}.class")) {
            "Missing class file resource for ${clazz.name}"
        }.use { input ->
            String(input.readBytes(), StandardCharsets.ISO_8859_1)
        }
}
