@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private const val MAX_QUERY_PRIMITIVE_VALUE_LENGTH = 512
private const val MAX_QUERY_NAME_LENGTH = 256

internal object QueryRunActionSerializer : KSerializer<QueryRunRequest.Run> {
    private val delegate = QueryRunRequest.Run.generatedSerializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryRunRequest.Run) {
        delegate.serialize(encoder, value.requireCanonicalSyntax())
    }

    override fun deserialize(decoder: Decoder): QueryRunRequest.Run =
        delegate.deserialize(decoder).requireCanonicalSyntax()
}

private fun QueryRunRequest.Run.requireCanonicalSyntax(): QueryRunRequest.Run =
    if (hasCanonicalRequestSyntax()) this
    else throw SerializationException("QueryRunRequest rejected non-canonical query syntax")

private fun QueryRunRequest.Run.hasCanonicalRequestSyntax(): Boolean {
    val sourceIsCanonical =
        when (val source = from) {
            is QueryFromDocument.Impact -> true
            is QueryFromDocument.Location -> source.file.value.isCanonicalQueryFile()
            is QueryFromDocument.Symbols ->
                source.match !is QueryMatchDocument.TextWord && source.discovery.isCanonical()
            is QueryFromDocument.TextWord ->
                source.word.isIndexedQueryWord() &&
                    QueryDiscoveryDocument(
                            QueryMatchDocument.TextWord(source.word),
                            source.scope,
                            source.declarationKinds,
                        )
                        .isCanonical()
            is QueryFromDocument.References -> source.values.values.isNotEmpty()
            is QueryFromDocument.Result -> source.rowIds?.values?.isUnique() ?: true
        }
    if (!sourceIsCanonical) return false
    if (!steps.values.all(QueryStepDocument::hasCanonicalSyntax)) return false
    return when (val projection = output) {
        is QueryOutputDocument.Symbols -> projection.fields.values.isUnique()
        QueryOutputDocument.Occurrences -> true
        QueryOutputDocument.TraversalRecords -> true
        QueryOutputDocument.BindingRows -> true
        QueryOutputDocument.ValuePaths -> true
        is QueryOutputDocument.ImpactWitness -> from is QueryFromDocument.Impact && steps.values.isEmpty()
    }
}

internal fun String.isCanonicalQueryFile(): Boolean =
    isNotBlank() &&
        !startsWith('/') &&
        !contains('\\') &&
        !Regex("^[A-Za-z]:").containsMatchIn(this) &&
        none(Char::isISOControl) &&
        split('/').none { it.isBlank() || it == "." || it == ".." }

private fun QueryDiscoveryDocument.isCanonical(): Boolean {
    if (!scope.sourceSets.values.isUniqueNonEmpty() || !declarationKinds.values.isUniqueNonEmpty()) return false
    if (QueryDeclarationKindDocument.CONSTRUCTOR in declarationKinds.values) return false
    if (!match.hasCanonicalMatch()) return false
    val packageName = scope.packageName?.name?.value
    if (packageName != null && !QUERY_PACKAGE_NAME.matches(packageName)) return false
    val directory = scope.directory?.path?.value
    if (directory != null && !directory.isCanonicalQueryDirectory()) return false
    return true
}

private fun String.isCanonicalQueryDirectory(): Boolean {
    if (this == ".") return true
    if (startsWith('/') || any(Char::isISOControl)) return false
    return split('/').none { it.isBlank() || it == "." || it == ".." }
}

private fun QueryMatchDocument.hasCanonicalMatch(): Boolean =
    when (this) {
        QueryMatchDocument.All -> true
        is QueryMatchDocument.Name -> text.value.length <= MAX_QUERY_NAME_LENGTH
        is QueryMatchDocument.TextWord -> word.isIndexedQueryWord()
    }

private fun QueryStepDocument.hasCanonicalSyntax(): Boolean =
    when (this) {
        is QueryStepDocument.Trace,
        is QueryStepDocument.Related,
        is QueryStepDocument.Walk,
        is QueryStepDocument.ProjectBinding,
        QueryStepDocument.Distinct -> true
        is QueryStepDocument.Join ->
            (right.rowIds?.values?.isUnique() ?: true) &&
                ((mode as? QueryJoinModeDocument.Inner)?.let { it.leftName != it.rightName } ?: true)
        is QueryStepDocument.Concat ->
            when (val source = input) {
                is QueryFromDocument.References -> source.values.values.isNotEmpty()
                is QueryFromDocument.Result -> source.rowIds?.values?.isUnique() ?: true
            }
        is QueryStepDocument.Intersect -> right.rowIds?.values?.isUnique() ?: true
        is QueryStepDocument.Union -> right.rowIds?.values?.isUnique() ?: true
        is QueryStepDocument.Difference -> right.rowIds?.values?.isUnique() ?: true
        is QueryStepDocument.Where ->
            when (val value = predicate) {
                is QueryPredicateDocument.Visibility -> value.values.values.isUniqueNonEmpty()
                is QueryPredicateDocument.Primitive -> value.value.value.length <= MAX_QUERY_PRIMITIVE_VALUE_LENGTH
            }
    }

private fun <Value> List<Value>.isUniqueNonEmpty(): Boolean = isNotEmpty() && isUnique()

private fun <Value> List<Value>.isUnique(): Boolean = size == distinct().size

private val QUERY_PACKAGE_NAME = Regex("(?!.*\\.(?:[0-9.]|$))[A-Za-z_][A-Za-z0-9_.]*")
