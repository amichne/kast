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
        is PublicToolRunAction -> lowerRun()
        is PublicToolResumeAction ->
            Refinement.Refined(PublicToolCanonical.Query(QueryRunRequest.Resume(continuation, executionBudget)))
        is PublicToolReadResultAction -> lowerReadResult()
    }

private fun PublicToolRunAction.lowerRun(): Refinement<PublicToolCanonical, PublicToolInputFailure> =
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
                                output = output ?: PublicToolDefaults.output,
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

private fun PublicToolReadResultAction.lowerReadResult(): Refinement<PublicToolCanonical, PublicToolInputFailure> =
    when (val selected = output ?: PublicToolDefaults.output) {
        is QueryOutputDocument.Symbols ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.ReadResult.symbols(
                        result,
                        cursor ?: QueryResultCursor.Start,
                        selected,
                        executionBudget,
                    )
                )
            )
        QueryOutputDocument.Occurrences,
        QueryOutputDocument.TraversalRecords -> Refinement.Rejected(PublicToolInputFailure.SchemaRejected)
        QueryOutputDocument.BindingRows ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.ReadResult.bindingRows(
                        result,
                        cursor ?: QueryResultCursor.Start,
                        executionBudget,
                    )
                )
            )
    }

private fun PublicToolSource.lower(): Refinement<QueryFromDocument, PublicToolInputFailure> =
    when (this) {
        is PublicToolLocationSource ->
            when (WorkspaceRelativePath.parse(file.value)) {
                is Refinement.Rejected ->
                    rejected(PublicToolParameter.LOCATION_FILE, PublicToolRule.WORKSPACE_RELATIVE_PATH)
                is Refinement.Refined ->
                    if (file.value == "." || offset < 0) {
                        rejected(PublicToolParameter.LOCATION_FILE, PublicToolRule.WORKSPACE_RELATIVE_PATH)
                    } else {
                        Refinement.Refined(QueryFromDocument.Location(file, proven(ProtocolOffset.parse(offset))))
                    }
            }
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
        is PublicToolReferenceSource -> Refinement.Refined(lowerReferences())
        is PublicToolResultSource -> Refinement.Refined(lowerResult())
    }

private fun PublicToolCompositionInput.lowerCompositionInput(): QueryCompositionInputDocument =
    when (this) {
        is PublicToolReferenceSource -> lowerReferences()
        is PublicToolResultSource -> lowerResult()
    }

private fun PublicToolReferenceSource.lowerReferences(): QueryFromDocument.References =
    QueryFromDocument.References(
        bounded(
            symbol_refs.values.map {
                // The exact-reference owner admits authenticity, generation and workspace at execution.
                QueryReferenceDocument.ExactSymbol(it)
            }
        )
    )

private fun PublicToolResultSource.lowerResult(): QueryFromDocument.Result = QueryFromDocument.Result(result, row_ids)

private fun PublicToolRetainedInput.lowerResult(): QueryFromDocument.Result =
    when (this) {
        is PublicToolResultSource -> lowerResult()
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
            is Refinement.Refined -> {
                val next = lowered.value
                result += next
            }
            is Refinement.Rejected -> return lowered
        }
    }
    return Refinement.Refined(result)
}

private fun PublicToolStep.lower(): Refinement<QueryStepDocument, PublicToolInputFailure> =
    when (this) {
        is PublicToolWhere -> Refinement.Refined(QueryStepDocument.Where(predicate))
        is PublicToolExpandRelation -> Refinement.Refined(QueryStepDocument.Related(relation.lower()))
        is PublicToolWalk ->
            Refinement.Refined(
                QueryStepDocument.Walk(
                    relation.lower(),
                    maximumDepth,
                    strategy ?: TraversalStrategyDocument.BreadthFirst,
                )
            )
        PublicToolDistinctSymbols -> Refinement.Refined(QueryStepDocument.Distinct)
        is PublicToolProjectBinding -> Refinement.Refined(QueryStepDocument.ProjectBinding(name))
        is PublicToolJoin -> Refinement.Refined(QueryStepDocument.Join(mode.lower(), right.lowerResult()))
        is PublicToolConcat -> Refinement.Refined(QueryStepDocument.Concat(input.lowerCompositionInput()))
        is PublicToolIntersect -> Refinement.Refined(QueryStepDocument.Intersect(right.lowerResult()))
        is PublicToolUnion -> Refinement.Refined(QueryStepDocument.Union(right.lowerResult()))
        is PublicToolDifference -> Refinement.Refined(QueryStepDocument.Difference(right.lowerResult()))
    }

private fun PublicToolJoinMode.lower(): QueryJoinModeDocument =
    when (this) {
        is PublicToolInnerJoinMode -> QueryJoinModeDocument.Inner(leftName, rightName)
        PublicToolSemiJoinMode -> QueryJoinModeDocument.Semi
        PublicToolAntiJoinMode -> QueryJoinModeDocument.Anti
    }

private fun PublicToolDeclarationKinds.lower(): QueryDeclarationKindDocument =
    when (this) {
        PublicToolDeclarationKinds.CLASS -> QueryDeclarationKindDocument.CLASS
        PublicToolDeclarationKinds.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
        PublicToolDeclarationKinds.PROPERTY -> QueryDeclarationKindDocument.PROPERTY
        PublicToolDeclarationKinds.TYPE_ALIAS -> QueryDeclarationKindDocument.TYPE_ALIAS
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

private fun rejected(parameter: PublicToolParameter, rule: PublicToolRule) =
    Refinement.Rejected(PublicToolInputFailure.Parameter(parameter, rule))

private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = proven(BoundedProtocolList.create(values))

/** Only schema-bounded cardinalities and unchanged refined text reach this extraction. */
private fun <T> proven(value: Refinement<T, *>): T =
    when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("A schema-admitted tool value violated its canonical bound")
    }
