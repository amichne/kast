package conventions.jsoncontracts

import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/** Parses Kotlin without resolution. Imported/qualified names are syntactic evidence, not compiler binding proof. */
internal class KotlinJsonContractScanner : AutoCloseable {
    sealed interface Scan {
        data class Accepted(val findings: List<JsonContractFinding>) : Scan

        data class Rejected(val violation: JsonContractViolation.InvalidSource) : Scan
    }

    private val disposable = Disposer.newDisposable("kast-json-contracts")
    // The pinned legacy environment supplies PSI parsing only; no K1 semantic analysis is performed.
    @OptIn(K1Deprecation::class)
    private val environment =
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration.create(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    private val factory = KtPsiFactory(environment.project, false)

    fun scan(source: JsonContractSource): Scan {
        if (!validJsonContractPath(source.path)) return rejected(source, 1, JsonContractSourceFailure.INVALID_PATH)
        val file = factory.createFile(source.path.substringAfterLast('/'), source.content)
        val syntax = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        if (syntax != null)
            return rejected(source, line(source.content, syntax.textOffset), JsonContractSourceFailure.INVALID_KOTLIN)
        val imports = ImportedJsonCalls(file)
        val observed = linkedMapOf<JsonContractFingerprint, MutableList<Int>>()
        fun record(expression: KtExpression, kind: JsonContractExpressionKind) {
            val fingerprint =
                JsonContractFingerprint(source.path, namedScope(expression), kind, fingerprint(expression.text))
            observed.getOrPut(fingerprint) { mutableListOf() } += line(source.content, expression.textOffset)
        }
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    when (val call = imports.classify(expression)) {
                        is ImportedJsonCalls.Match.Found -> record(call.expression, call.kind)
                        ImportedJsonCalls.Match.Unrelated -> Unit
                    }
                    super.visitCallExpression(expression)
                }

                override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
                    if (!insideConcatenation(expression) && isJson(stringApproximation(expression))) {
                        record(expression, JsonContractExpressionKind.JSON_LITERAL)
                    }
                    super.visitStringTemplateExpression(expression)
                }

                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    if (
                        expression.operationToken == KtTokens.PLUS &&
                            !insideConcatenation(expression) &&
                            isJson(concatenation(expression))
                    ) {
                        record(expression, JsonContractExpressionKind.JSON_LITERAL)
                    }
                    super.visitBinaryExpression(expression)
                }
            }
        )
        return Scan.Accepted(
            observed.map { (fingerprint, lines) -> JsonContractFinding(fingerprint, lines.size, lines) }
        )
    }

    override fun close() = Disposer.dispose(disposable)

    private fun rejected(source: JsonContractSource, line: Int, reason: JsonContractSourceFailure) =
        Scan.Rejected(JsonContractViolation.InvalidSource(source.path, line, reason))
}

private class ImportedJsonCalls(file: KtFile) {
    sealed interface Match {
        data class Found(val expression: KtExpression, val kind: JsonContractExpressionKind) : Match

        data object Unrelated : Match
    }

    private val imported = buildMap {
        for (directive in file.importDirectives) {
            val importedPath = directive.importPath ?: continue
            if (importedPath.isAllUnder && importedPath.fqName.asString() == JSON_PACKAGE) {
                putAll(KNOWN_CALLS)
            } else {
                val kind = KNOWN_CALLS[importedPath.fqName.shortName().asString()] ?: continue
                if (importedPath.fqName.parent().asString() == JSON_PACKAGE) {
                    put(directive.aliasName ?: importedPath.fqName.shortName().asString(), kind)
                }
            }
        }
        if (file.packageFqName.asString() == JSON_PACKAGE) putAll(KNOWN_CALLS)
    }

    fun classify(call: KtCallExpression): Match {
        val name = call.calleeExpression?.text?.removeSurrounding("`") ?: return Match.Unrelated
        val parent = call.parent
        if (parent is KtDotQualifiedExpression && parent.selectorExpression == call) {
            if (normalized(parent.receiverExpression.text) != normalized(JSON_PACKAGE)) return Match.Unrelated
            val kind = KNOWN_CALLS[name] ?: return Match.Unrelated
            return Match.Found(parent, kind)
        }
        val kind = imported[name] ?: return Match.Unrelated
        return Match.Found(call, kind)
    }
}

private fun namedScope(expression: PsiElement): String {
    val names =
        generateSequence(expression.parent) { it.parent }
            .filterIsInstance<KtNamedDeclaration>()
            .mapNotNull { declaration ->
                val name = declaration.name ?: return@mapNotNull null
                when (declaration) {
                    is KtNamedFunction ->
                        "fun:$name(" +
                            declaration.valueParameters.joinToString(",") {
                                normalized(it.typeReference?.text ?: "<inferred>")
                            } +
                            ")" +
                            (declaration.receiverTypeReference?.text?.let(::normalized) ?: "")
                    is KtClassOrObject -> "type:$name"
                    is KtProperty -> "val:$name"
                    else -> "declaration:$name"
                }
            }
            .toList()
            .asReversed()
    return names.joinToString("/").ifEmpty { "<file>" }
}

private fun normalized(text: String): String {
    val lexer = KotlinLexer()
    lexer.start(text)
    val tokens = buildList {
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (type != KtTokens.WHITE_SPACE && type != KtTokens.SEMICOLON && !KtTokens.COMMENTS.contains(type)) {
                add(text.substring(lexer.tokenStart, lexer.tokenEnd))
            }
            lexer.advance()
        }
    }
    return buildString {
        tokens.forEachIndexed { index, token ->
            if (token != "," || tokens.getOrNull(index + 1) !in setOf(")", "]", ">")) {
                append(token.length).append(':').append(token)
            }
        }
    }
}

private fun fingerprint(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(normalized(text).toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(java.util.Locale.ROOT, it)
    }

private fun line(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

private fun insideConcatenation(expression: KtExpression): Boolean =
    (expression.parent as? KtBinaryExpression)?.operationToken == KtTokens.PLUS

private fun stringApproximation(expression: KtStringTemplateExpression): String =
    expression.entries.joinToString("") {
        when (it) {
            is KtLiteralStringTemplateEntry -> it.text
            is KtEscapeStringTemplateEntry -> it.unescapedValue
            else -> "0"
        }
    }

private fun concatenation(expression: KtExpression?): String =
    when (expression) {
        is KtStringTemplateExpression -> stringApproximation(expression)
        is KtBinaryExpression ->
            if (expression.operationToken == KtTokens.PLUS)
                concatenation(expression.left) + concatenation(expression.right)
            else "0"
        else -> "0"
    }

private fun isJson(text: String): Boolean {
    val trimmed = text.trim()
    if (!(trimmed.startsWith('{') && trimmed.endsWith('}')) && !(trimmed.startsWith('[') && trimmed.endsWith(']')))
        return false
    return try {
        when (Json.parseToJsonElement(trimmed)) {
            is JsonObject,
            is JsonArray -> true
            else -> false
        }
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

private const val JSON_PACKAGE = "kotlinx.serialization.json"
private val KNOWN_CALLS =
    mapOf(
        "buildJsonObject" to JsonContractExpressionKind.JSON_BUILDER,
        "buildJsonArray" to JsonContractExpressionKind.JSON_BUILDER,
        "JsonObject" to JsonContractExpressionKind.JSON_OBJECT,
        "JsonArray" to JsonContractExpressionKind.JSON_ARRAY,
    )
