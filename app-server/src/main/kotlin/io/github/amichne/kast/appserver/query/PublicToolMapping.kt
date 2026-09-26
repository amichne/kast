package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*

/** Pure normalization/lowering; nullable controls exist only in the generated ingress documents. */
internal fun PublicToolDocument.lower(): Refinement<PublicToolCanonical, PublicToolInputFailure> =
    when (this) {
        is PublicToolCheckDiagnostics ->
            when (WorkspaceRelativePath.parse(relative_path.value)) {
                is Refinement.Rejected ->
                    rejected(PublicToolParameter.DIAGNOSTIC_PATH, PublicToolRule.WORKSPACE_RELATIVE_PATH)
                is Refinement.Refined ->
                    Refinement.Refined(
                        PublicToolCanonical.Diagnostics(
                            DiagnosticCheckRequest(
                                relative_path,
                                proven(ProtocolCount.parse(max_diagnostics ?: PublicToolDefaults.maxDiagnostics)),
                                continuation = continuation,
                                executionBudget = executionBudget,
                            )
                        )
                    )
            }
        is PublicToolQuerySymbols -> request.lower()
    }

private fun PublicToolAction.lower(): Refinement<PublicToolCanonical, PublicToolInputFailure> =
    when (this) {
        is PublicToolRunAction ->
            when (val from = source.lower()) {
                is Refinement.Rejected -> from
                is Refinement.Refined ->
                    when (val loweredSteps = (steps ?: PublicToolDefaults.steps).values.lower()) {
                        is Refinement.Rejected -> loweredSteps
                        is Refinement.Refined ->
                            Refinement.Refined(
                                PublicToolCanonical.Query(
                                    QueryRunRequest.Run(
                                        from = from.value,
                                        steps = bounded(loweredSteps.value),
                                        output = output(return_fields),
                                        execution =
                                            QueryExecutionDocument(
                                                QueryExecutionKindDocument.EXHAUSTIVE,
                                                QueryExecutionBudgetDocument.INTERACTIVE,
                                            ),
                                        retention =
                                            when (retention) {
                                                null,
                                                PublicToolRetention.DISCARD -> QueryRetentionModeDocument.DISCARD
                                                PublicToolRetention.RETAIN -> QueryRetentionModeDocument.RETAIN
                                            },
                                        executionBudget = executionBudget,
                                    )
                                )
                            )
                    }
            }
        is PublicToolResumeAction ->
            Refinement.Refined(PublicToolCanonical.Query(QueryRunRequest.Resume(continuation, executionBudget)))
        is PublicToolReadResultAction ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.ReadResult(
                        result,
                        cursor ?: QueryResultCursor.Start,
                        output(return_fields),
                        executionBudget,
                    )
                )
            )
    }

private fun output(fields: BoundedProtocolList<PublicToolReturnFields>?): QueryOutputDocument.Symbols =
    QueryOutputDocument.Symbols(bounded((fields ?: PublicToolDefaults.returnFields).values.map { it.lower() }))

private fun PublicToolSource.lower(): Refinement<QueryFromDocument, PublicToolInputFailure> =
    when (this) {
        is PublicToolSearchSource ->
            searchSource(
                declaration_name,
                name_match ?: PublicToolDefaults.nameMatch,
                scope ?: PublicToolDefaults.scope,
                (declaration_kinds ?: PublicToolDefaults.declarationKinds).values,
                PublicToolParameter.SOURCE_DECLARATION_NAME,
            )
        is PublicToolAllSource ->
            when (val scope = (scope ?: PublicToolDefaults.scope).lower()) {
                is Refinement.Rejected -> scope
                is Refinement.Refined ->
                    Refinement.Refined(
                        QueryFromDocument.Symbols(
                            QueryMatchDocument.All,
                            scope.value,
                            bounded(
                                (declaration_kinds ?: PublicToolDefaults.declarationKinds).values.map { it.lower() }
                            ),
                        )
                    )
            }
        is PublicToolReferenceSource ->
            Refinement.Refined(
                QueryFromDocument.References(
                    bounded(
                        symbol_refs.values.map {
                            // The exact-reference owner admits authenticity, generation and workspace at execution.
                            QueryReferenceDocument.ExactSymbol(it)
                        }
                    )
                )
            )
        is PublicToolResultSource -> Refinement.Refined(QueryFromDocument.Result(result))
    }

/** The spelling remains unchanged; no qualification stripping, wildcard expansion or fuzzy retry. */
@JvmInline
private value class DeclarationName private constructor(val text: ProtocolText) {
    companion object {
        fun admit(
            text: ProtocolText,
            parameter: PublicToolParameter,
        ): Refinement<DeclarationName, PublicToolInputFailure> =
            if (text.value.length <= 256 && Regex("[\\p{L}_][\\p{L}\\p{N}_]*").matches(text.value)) {
                Refinement.Refined(DeclarationName(text))
            } else rejected(parameter, PublicToolRule.SIMPLE_NAME)
    }
}

private fun searchSource(
    name: ProtocolText,
    match: PublicToolNameMatch,
    scope: PublicToolScope,
    kinds: List<PublicToolDeclarationKinds>,
    parameter: PublicToolParameter,
): Refinement<QueryFromDocument, PublicToolInputFailure> {
    val admittedName =
        when (val result = DeclarationName.admit(name, parameter)) {
            is Refinement.Rejected -> return result
            is Refinement.Refined -> result.value
        }
    val admittedScope =
        when (val result = scope.lower()) {
            is Refinement.Rejected -> return result
            is Refinement.Refined -> result.value
        }
    return Refinement.Refined(
        QueryFromDocument.Symbols(
            QueryMatchDocument.Name(
                admittedName.text,
                when (match) {
                    PublicToolNameMatch.EXACT -> SymbolDiscoveryMatchDocument.EXACT_NAME
                    PublicToolNameMatch.FUZZY -> SymbolDiscoveryMatchDocument.FUZZY
                },
            ),
            admittedScope,
            bounded(kinds.map { it.lower() }),
        )
    )
}

private fun PublicToolScope.lower(): Refinement<QueryScopeDocument, PublicToolInputFailure> =
    when (this) {
        is PublicToolDirectoryScope ->
            when (val path = WorkspaceRelativePath.parse(relative_directory_path.value)) {
                is Refinement.Rejected ->
                    rejected(PublicToolParameter.DIRECTORY, PublicToolRule.WORKSPACE_RELATIVE_PATH)
                is Refinement.Refined ->
                    Refinement.Refined(
                        QueryScopeDocument(
                            source_set_names ?: PublicToolDefaults.sourceSets,
                            QueryDirectoryScopeDocument(
                                proven(ProtocolText.parse(path.value.value)),
                                containment(include_subdirectories),
                            ),
                            null,
                        )
                    )
            }
        is PublicToolPackageScope ->
            when (val name = PublicQueryPackageName.parse(package_name.value)) {
                is Refinement.Rejected -> rejected(PublicToolParameter.PACKAGE, PublicToolRule.PACKAGE_NAME)
                is Refinement.Refined ->
                    Refinement.Refined(
                        QueryScopeDocument(
                            source_set_names ?: PublicToolDefaults.sourceSets,
                            null,
                            QueryPackageScopeDocument(
                                proven(ProtocolText.parse(name.value.value)),
                                containment(include_subpackages),
                            ),
                        )
                    )
            }
    }

private fun containment(recursive: Boolean): QueryContainmentDocument =
    if (recursive) QueryContainmentDocument.DESCENDANTS else QueryContainmentDocument.DIRECT

private fun List<PublicToolStep>.lower(): Refinement<List<QueryStepDocument>, PublicToolInputFailure> {
    val result = mutableListOf<QueryStepDocument>()
    for (step in this) {
        when (val lowered = step.lower()) {
            is Refinement.Refined -> result += lowered.value
            is Refinement.Rejected -> return lowered
        }
    }
    return Refinement.Refined(result)
}

private fun PublicToolStep.lower(): Refinement<QueryStepDocument, PublicToolInputFailure> =
    when (this) {
        is PublicToolFilterVisibility ->
            Refinement.Refined(
                QueryStepDocument.Where(
                    QueryPredicateDocument.Visibility(bounded(visibilities.values.map { it.lower() }))
                )
            )
        is PublicToolExpandRelation -> Refinement.Refined(QueryStepDocument.Related(relation.lower()))
        PublicToolDistinctSymbols -> Refinement.Refined(QueryStepDocument.Distinct)
        is PublicToolAppendSymbolRefs -> Refinement.Refined(QueryStepDocument.AppendReferences(symbol_refs))
        is PublicToolFilterJq ->
            when (val predicate = PrimitiveJqFilter.admit(expression)) {
                is Refinement.Refined -> Refinement.Refined(QueryStepDocument.Where(predicate.value))
                is Refinement.Rejected -> predicate
            }
    }

private fun PublicToolDeclarationKinds.lower(): QueryDeclarationKindDocument =
    when (this) {
        PublicToolDeclarationKinds.CLASS -> QueryDeclarationKindDocument.CLASS
        PublicToolDeclarationKinds.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
        PublicToolDeclarationKinds.PROPERTY -> QueryDeclarationKindDocument.PROPERTY
        PublicToolDeclarationKinds.TYPE_ALIAS -> QueryDeclarationKindDocument.TYPE_ALIAS
    }

private fun PublicToolVisibilities.lower(): QueryVisibilityDocument =
    when (this) {
        PublicToolVisibilities.PUBLIC -> QueryVisibilityDocument.PUBLIC
        PublicToolVisibilities.PROTECTED -> QueryVisibilityDocument.PROTECTED
        PublicToolVisibilities.INTERNAL -> QueryVisibilityDocument.INTERNAL
        PublicToolVisibilities.PRIVATE -> QueryVisibilityDocument.PRIVATE
        PublicToolVisibilities.LOCAL -> QueryVisibilityDocument.LOCAL
    }

private fun PublicToolRelation.lower(): RelationKindDocument =
    when (this) {
        PublicToolRelation.REFERENCES -> RelationKindDocument.REFERENCES
        PublicToolRelation.CALLERS -> RelationKindDocument.CALLERS
        PublicToolRelation.CALLEES -> RelationKindDocument.CALLEES
        PublicToolRelation.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
        PublicToolRelation.INHERITORS -> RelationKindDocument.INHERITORS
        PublicToolRelation.OVERRIDES -> RelationKindDocument.OVERRIDES
        PublicToolRelation.TYPE_USES -> RelationKindDocument.TYPE_USES
    }

private fun PublicToolReturnFields.lower(): QuerySymbolFieldDocument =
    when (this) {
        PublicToolReturnFields.NAME -> QuerySymbolFieldDocument.NAME
        PublicToolReturnFields.LOCATION -> QuerySymbolFieldDocument.LOCATION
        PublicToolReturnFields.SIGNATURE -> QuerySymbolFieldDocument.SIGNATURE
        PublicToolReturnFields.SOURCE -> QuerySymbolFieldDocument.SOURCE
    }

private fun rejected(parameter: PublicToolParameter, rule: PublicToolRule) =
    Refinement.Rejected(PublicToolInputFailure.Parameter(parameter, rule))

private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = proven(BoundedProtocolList.create(values))

/** Only schema-bounded cardinalities and unchanged refined text reach this extraction. */
private fun <T> proven(value: Refinement<T, *>): T =
    when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("A schema-admitted tool value violated its canonical bound")
    }
