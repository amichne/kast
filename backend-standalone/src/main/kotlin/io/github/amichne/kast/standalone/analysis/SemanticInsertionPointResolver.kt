package io.github.amichne.kast.standalone.analysis

import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.SemanticInsertionQuery
import io.github.amichne.kast.api.SemanticInsertionResult
import io.github.amichne.kast.api.SemanticInsertionTarget
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

internal object SemanticInsertionPointResolver {
    fun resolve(
        file: KtFile,
        query: SemanticInsertionQuery,
    ): SemanticInsertionResult {
        val insertionOffset = when (query.target) {
            SemanticInsertionTarget.CLASS_BODY_START -> classBody(file, query).lBrace?.textRange?.endOffset
            SemanticInsertionTarget.CLASS_BODY_END -> classBody(file, query).rBrace?.textRange?.startOffset
            SemanticInsertionTarget.FILE_TOP -> 0
            SemanticInsertionTarget.FILE_BOTTOM -> file.textLength
            SemanticInsertionTarget.AFTER_IMPORTS -> afterImportsOffset(file)
        } ?: throw NotFoundException(
            message = "The requested semantic insertion point could not be resolved",
            details = mapOf(
                "filePath" to query.position.filePath,
                "target" to query.target.name,
            ),
        )

        return SemanticInsertionResult(
            insertionOffset = insertionOffset,
            filePath = file.resolvedFilePath().value,
        )
    }

    private fun classBody(
        file: KtFile,
        query: SemanticInsertionQuery,
    ): KtClassBody {
        val target = resolveTarget(file, query.position.offset)
        val declaration = target.typeHierarchyDeclaration() as? KtClassOrObject
            ?: throw NotFoundException(
                message = "The requested semantic insertion target requires a class or object declaration",
                details = mapOf(
                    "filePath" to query.position.filePath,
                    "target" to query.target.name,
                ),
            )
        return declaration.body
            ?: throw NotFoundException(
                message = "The requested class or object does not have a body",
                details = mapOf(
                    "filePath" to query.position.filePath,
                    "target" to query.target.name,
                ),
            )
    }

    private fun afterImportsOffset(file: KtFile): Int {
        val content = file.text
        file.importList?.imports?.lastOrNull()?.let { directive ->
            return offsetAfterLineBreak(content, directive.textRange.endOffset)
        }
        file.packageDirective?.let { packageDirective ->
            return offsetAfterLineBreak(content, packageDirective.textRange.endOffset)
        }
        return 0
    }

    private fun offsetAfterLineBreak(
        content: String,
        offset: Int,
    ): Int {
        var cursor = offset
        if (content.getOrNull(cursor) == '\r') {
            cursor += 1
        }
        if (content.getOrNull(cursor) == '\n') {
            cursor += 1
        }
        return cursor
    }
}
