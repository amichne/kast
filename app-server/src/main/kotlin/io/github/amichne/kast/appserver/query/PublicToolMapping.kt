package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.*

/** Pure normalization/lowering; nullable controls exist only in the generated ingress documents. */
internal fun PublicToolDocument.lower(): Refinement<PublicToolCanonical, PublicToolInputFailure> =
    when (this) {
        is PublicToolCheckDiagnostics ->
            when (WorkspaceRelativePath.parse(relativePath.value)) {
                is Refinement.Rejected ->
                    rejected(PublicToolParameter.DIAGNOSTIC_PATH, PublicToolRule.WORKSPACE_RELATIVE_PATH)
                is Refinement.Refined ->
                    Refinement.Refined(
                        PublicToolCanonical.Diagnostics(
                            DiagnosticCheckRequest(
                                relativePath,
                                proven(ProtocolCount.parse(maxDiagnostics ?: PublicToolDefaults.maxDiagnostics)),
                                continuation = continuation,
                                executionBudget = (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
                            )
                        )
                    )
            }
        is PublicToolQuerySymbols -> request.lower()
        is PublicToolAddDeclaration -> Refinement.Refined(lowerChange())
        is PublicToolReplaceBody -> Refinement.Refined(lowerChange())
    }

private fun PublicToolAction.lower(): Refinement<PublicToolCanonical, PublicToolInputFailure> =
    when (this) {
        is PublicToolRunAction -> lowerRun()
        is PublicToolResumeAction ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.Resume(
                        continuation,
                        (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
                    )
                )
            )
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
                                output = output?.lower() ?: PublicToolDefaults.output,
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
                                executionBudget = (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
                            )
                        )
                    )
            }
    }

private fun PublicToolReadResultAction.lowerReadResult(): Refinement<PublicToolCanonical, PublicToolInputFailure> =
    when (val selected = output?.lower() ?: PublicToolDefaults.output) {
        is QueryOutputDocument.Symbols ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.ReadResult.symbols(
                        result,
                        cursor ?: QueryResultCursor.Start,
                        selected,
                        (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
                    )
                )
            )
        QueryOutputDocument.Occurrences ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.ReadResult.occurrences(
                        result,
                        cursor ?: QueryResultCursor.Start,
                        (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
                    )
                )
            )
        QueryOutputDocument.TraversalRecords ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.ReadResult.traversalRecords(
                        result,
                        cursor ?: QueryResultCursor.Start,
                        (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
                    )
                )
            )
        QueryOutputDocument.BindingRows ->
            Refinement.Refined(
                PublicToolCanonical.Query(
                    QueryRunRequest.ReadResult.bindingRows(
                        result,
                        cursor ?: QueryResultCursor.Start,
                        (executionBudget ?: PublicToolDefaults.executionBudget).lower(),
                    )
                )
            )
    }

internal fun PublicToolExecutionBudget.lower(): ExecutionBudgetDocument =
    ExecutionBudgetDocument(
        maxElapsedMillis = maxElapsedMs?.let { proven(ElapsedTimeLimitMillis.parse(it)) },
        maxWorkUnits = maxWorkUnits?.let { proven(WorkUnitLimit.parse(it)) },
        maxResults = maxResults?.let { proven(ResultLimit.parse(it)) },
        maxReturnedBytes = maxReturnedBytes?.let { proven(ReturnedByteLimit.parse(it)) },
    )

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
                declarationName,
                nameMatch ?: PublicToolDefaults.nameMatch,
                scope ?: PublicToolDefaults.scope,
                (declarationKinds ?: PublicToolDefaults.declarationKinds).values,
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
                                (declarationKinds ?: PublicToolDefaults.declarationKinds).values.map { it.lower() }
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
            symbolRefs.values.map {
                // The exact-reference owner admits authenticity, generation and workspace at execution.
                QueryReferenceDocument.ExactSymbol(it)
            }
        )
    )

private fun PublicToolResultSource.lowerResult(): QueryFromDocument.Result = QueryFromDocument.Result(result, rowIds)

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
            when (val path = WorkspaceRelativePath.parse(relativeDirectoryPath.value)) {
                is Refinement.Rejected ->
                    rejected(PublicToolParameter.DIRECTORY, PublicToolRule.WORKSPACE_RELATIVE_PATH)
                is Refinement.Refined ->
                    Refinement.Refined(
                        QueryScopeDocument(
                            sourceSetNames ?: PublicToolDefaults.sourceSets,
                            QueryDirectoryScopeDocument(
                                proven(ProtocolText.parse(path.value.value)),
                                containment(includeSubdirectories ?: PublicToolDefaults.includeSubdirectories),
                            ),
                            null,
                        )
                    )
            }
        is PublicToolPackageScope ->
            when (val name = PublicQueryPackageName.parse(packageName.value)) {
                is Refinement.Rejected -> rejected(PublicToolParameter.PACKAGE, PublicToolRule.PACKAGE_NAME)
                is Refinement.Refined ->
                    Refinement.Refined(
                        QueryScopeDocument(
                            sourceSetNames ?: PublicToolDefaults.sourceSets,
                            null,
                            QueryPackageScopeDocument(
                                proven(ProtocolText.parse(name.value.value)),
                                containment(includeSubpackages ?: PublicToolDefaults.includeSubpackages),
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
        is PublicToolWhere -> Refinement.Refined(QueryStepDocument.Where(predicate.lower()))
        is PublicToolExpandRelation -> Refinement.Refined(QueryStepDocument.Related(relation.lower()))
        is PublicToolWalk ->
            Refinement.Refined(
                QueryStepDocument.Walk(
                    relation.lower(),
                    maximumDepth ?: PublicToolDefaults.walkDepth,
                    strategy?.lower() ?: TraversalStrategyDocument.BreadthFirst,
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

private fun PublicToolPredicate.lower(): QueryPredicateDocument =
    when (this) {
        is PublicToolVisibilityPredicate ->
            QueryPredicateDocument.Visibility(
                bounded(
                    values.values.map { value ->
                        when (value) {
                            PublicToolValues.PUBLIC -> QueryVisibilityDocument.PUBLIC
                            PublicToolValues.PROTECTED -> QueryVisibilityDocument.PROTECTED
                            PublicToolValues.INTERNAL -> QueryVisibilityDocument.INTERNAL
                            PublicToolValues.PRIVATE -> QueryVisibilityDocument.PRIVATE
                            PublicToolValues.LOCAL -> QueryVisibilityDocument.LOCAL
                        }
                    }
                )
            )
        is PublicToolPrimitivePredicate ->
            QueryPredicateDocument.Primitive(
                when (field) {
                    PublicToolField.NAME -> QueryPrimitiveFieldDocument.NAME
                    PublicToolField.KIND -> QueryPrimitiveFieldDocument.KIND
                    PublicToolField.FILE -> QueryPrimitiveFieldDocument.FILE
                },
                when (operator) {
                    PublicToolOperator.EQUALS -> QueryPrimitiveOperatorDocument.EQUALS
                    PublicToolOperator.NOT_EQUALS -> QueryPrimitiveOperatorDocument.NOT_EQUALS
                    PublicToolOperator.STARTS_WITH -> QueryPrimitiveOperatorDocument.STARTS_WITH
                    PublicToolOperator.ENDS_WITH -> QueryPrimitiveOperatorDocument.ENDS_WITH
                },
                value,
            )
    }

private fun PublicToolWalkStrategy.lower(): TraversalStrategyDocument =
    when (this) {
        PublicToolBreadthFirstStrategy -> TraversalStrategyDocument.BreadthFirst
        is PublicToolBoundedFanOutStrategy ->
            TraversalStrategyDocument.BoundedFanOut(
                proven(ProtocolCount.parse(maximumEdgesPerNode ?: PublicToolDefaults.maximumEdgesPerNode))
            )
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
