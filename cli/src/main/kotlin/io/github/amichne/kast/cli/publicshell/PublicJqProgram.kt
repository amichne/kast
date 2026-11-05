package io.github.amichne.kast.cli.publicshell

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.github.amichne.kast.distribution.output.JsonProjectionDocument
import io.github.amichne.kast.kernel.Refinement
import java.io.StringReader
import net.thisptr.jackson.jq.BuiltinFunctionLoader
import net.thisptr.jackson.jq.JsonQuery
import net.thisptr.jackson.jq.Scope
import net.thisptr.jackson.jq.Versions
import net.thisptr.jackson.jq.exception.JsonQueryException
import net.thisptr.jackson.jq.internal.javacc.ExpressionParserConstants
import net.thisptr.jackson.jq.internal.javacc.ExpressionParserTokenManager
import net.thisptr.jackson.jq.internal.javacc.SimpleCharStream
import net.thisptr.jackson.jq.internal.javacc.TokenMgrError

/** An embedded program whose syntax and numeric literals have been admitted before workspace effects. */
internal class PublicJqProgram private constructor(private val query: JsonQuery) {
    fun evaluate(document: String, maxBytes: Int): Refinement<JsonProjectionDocument, PublicJqFailure> {
        val mapper = exactJqMapper()
        val input =
            when (val admitted = parseExactJqJson(document, mapper)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> admitted.value
            }
        val root = Scope.newEmptyScope()
        // Core builtins only: no CLI I/O extensions, environment access or filesystem module loader.
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_7, root)
        installExactJsonConversions(root, mapper)
        val result = JqSingleResult(mapper, maxBytes)
        return try {
            query.apply(Scope.newChildScope(root), input, result::emit)
            result.finish()
        } catch (rejected: JqEmissionRejected) {
            Refinement.Rejected(rejected.failure)
        } catch (_: JsonQueryException) {
            // Discard any earlier emission, including errors after the first result. Never publish the payload.
            Refinement.Rejected(PublicJqFailure.EVALUATION_FAILED)
        } catch (_: JsonProcessingException) {
            Refinement.Rejected(PublicJqFailure.OUTPUT_INVALID)
        }
    }

    companion object {
        fun compile(program: String): Refinement<PublicJqProgram, PublicJqFailure> =
            try {
                when (val literals = admitJqLiterals(program)) {
                    is Refinement.Rejected -> literals
                    is Refinement.Refined ->
                        Refinement.Refined(PublicJqProgram(JsonQuery.compile(program, Versions.JQ_1_7)))
                }
            } catch (_: JsonQueryException) {
                Refinement.Rejected(PublicJqFailure.PROGRAM_INVALID)
            } catch (_: NumberFormatException) {
                Refinement.Rejected(PublicJqFailure.NUMERIC_VALUE_LOSS)
            }
    }
}

private fun exactJqMapper(): ObjectMapper =
    JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .build()

/** Use the pinned engine's lexer rather than a second jq grammar or a regular-expression approximation. */
private fun admitJqLiterals(program: String): Refinement<Unit, PublicJqFailure> {
    val tokens = ExpressionParserTokenManager(SimpleCharStream(StringReader(program)))
    return try {
        var token = tokens.nextToken
        while (token.kind != ExpressionParserConstants.EOF) {
            if (token.kind == ExpressionParserConstants.OPEN_INTERP)
                return Refinement.Rejected(PublicJqFailure.PROGRAM_UNSUPPORTED)
            if (token.kind == ExpressionParserConstants.INTEGER_LITERAL && token.image.toLongOrNull() == null)
                return Refinement.Rejected(PublicJqFailure.NUMERIC_VALUE_LOSS)
            if (token.kind == ExpressionParserConstants.FLOAT_LITERAL && !exactJqDoubleLiteral(token.image))
                return Refinement.Rejected(PublicJqFailure.NUMERIC_VALUE_LOSS)
            token = tokens.nextToken
        }
        Refinement.Refined(Unit)
    } catch (_: TokenMgrError) {
        Refinement.Rejected(PublicJqFailure.PROGRAM_INVALID)
    }
}

private class JqEmissionRejected(val failure: PublicJqFailure) : RuntimeException()

/** The stock conversions use a different mapper. Keep nested JSON and tonumber under the same exact codec guard. */
private fun installExactJsonConversions(scope: Scope, mapper: ObjectMapper) {
    scope.addFunction("fromjson", 0) { _, _, value, _, output, _ ->
        output.emit(parseJqString(value, mapper), null)
    }
    scope.addFunction("tonumber", 0) { _, _, value, _, output, _ ->
        val number = if (value.isNumber) value else parseJqString(value, mapper)
        if (!number.isNumber) throw JsonQueryException("tonumber requires a number")
        output.emit(number, null)
    }
}

private fun parseJqString(value: JsonNode, mapper: ObjectMapper): JsonNode {
    if (!value.isTextual) throw JsonQueryException("JSON conversion requires a string")
    return when (val parsed = parseExactJqJson(value.textValue(), mapper)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> rejectJqConversion(parsed.failure)
    }
}

private fun rejectJqConversion(failure: PublicJqFailure): Nothing =
    if (failure == PublicJqFailure.NUMERIC_VALUE_LOSS) throw JqEmissionRejected(failure)
    else throw JsonQueryException("Invalid JSON text")

private fun parseExactJqJson(text: String, mapper: ObjectMapper): Refinement<JsonNode, PublicJqFailure> {
    val original =
        when (val admitted = JsonProjectionDocument.parse(text)) {
            is Refinement.Rejected -> return Refinement.Rejected(PublicJqFailure.OUTPUT_INVALID)
            is Refinement.Refined -> admitted.value
        }
    return try {
        val node = mapper.readTree(text)
        if (original.reencoded(mapper.writeValueAsString(node)) is Refinement.Rejected)
            Refinement.Rejected(PublicJqFailure.NUMERIC_VALUE_LOSS)
        else Refinement.Refined(node)
    } catch (_: JsonProcessingException) {
        Refinement.Rejected(PublicJqFailure.OUTPUT_INVALID)
    }
}

private fun exactJqDoubleLiteral(text: String): Boolean {
    val original = text.toBigDecimalOrNull() ?: return false
    val encoded = text.toDoubleOrNull()?.takeIf(Double::isFinite)?.toString()?.toBigDecimalOrNull() ?: return false
    return original.compareTo(encoded) == 0
}

private sealed interface JqEmission {
    data object Empty : JqEmission

    data class Single(val document: JsonProjectionDocument) : JqEmission
}

private class JqSingleResult(private val mapper: ObjectMapper, private val maxBytes: Int) {
    private var state: JqEmission = JqEmission.Empty

    fun emit(value: JsonNode) {
        if (state is JqEmission.Single) throw JqEmissionRejected(PublicJqFailure.OUTPUT_CARDINALITY)
        rejectNonFiniteNumbers(value)
        state = JqEmission.Single(encode(value))
    }

    fun finish(): Refinement<JsonProjectionDocument, PublicJqFailure> =
        when (val captured = state) {
            JqEmission.Empty -> Refinement.Rejected(PublicJqFailure.OUTPUT_CARDINALITY)
            is JqEmission.Single -> Refinement.Refined(captured.document)
        }

    private fun encode(value: JsonNode): JsonProjectionDocument {
        val text = mapper.writeValueAsString(value)
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) throw JqEmissionRejected(PublicJqFailure.BYTE_LIMIT)
        return when (val parsed = JsonProjectionDocument.parse(text)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> throw JqEmissionRejected(PublicJqFailure.OUTPUT_INVALID)
        }
    }
}

private fun rejectNonFiniteNumbers(value: JsonNode) {
    val pending = ArrayDeque<JsonNode>()
    pending.add(value)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node.isFloatingPointNumber && !node.doubleValue().isFinite())
            throw JqEmissionRejected(PublicJqFailure.NUMERIC_VALUE_LOSS)
        if (node.isContainerNode) node.forEach { pending.add(it) }
    }
}
