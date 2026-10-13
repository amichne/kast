Baseline: 54fc6390e55f219dc826a9465a01c514e3201d12.
Owned files: authored tools.schema.json, public-query-contract.md, query/AGENTS.md, query-patterns.md, new PublicToolRecipeTest.kt.
Current behavior: admission accepts uppercase variants, optional RUN steps, rowIds, output-only query-output:v1 continuation, four intent tools. Fixed completion policy has no selector.
Decision: reuse admission and existing correct search walkthroughs. Correct conflicting prose and omit two redundant controls.
Argument delta: publicFunctionInventory.source.scope.includeSubdirectories true becomes omitted. boundedCallerWalk.steps[0].strategy BREADTH_FIRST becomes omitted. All other arguments stay identical.
Protected fixtures: 29 valid authored recipes and all invalid examples. Existing explicit/null/empty controls stay unchanged. Impact steps/models/declarations remain explicitly empty. Scope, fields, presentation limits, retention and illustrative handle bytes stay unchanged.
Independent expected inventory: services/orders directory, DESCENDANTS, main source set, FUNCTION kind, ALL declarations, ordered WHERE PUBLIC then WHERE NAME STARTS_WITH create, SYMBOLS NAME LOCATION SIGNATURE, maxResults 50, DISCARD.
Independent expected walk: unchanged exact-ref placeholder, CALLERS, THROUGH_DEPTH 2, BreadthFirst, RetainedSeed, TRAVERSAL_RECORDS, maxResults 50, DISCARD.
Independent selected-reference examples: directCallers CALLERS then DISTINCT_SYMBOLS, implementations IMPLEMENTATIONS then DISTINCT_SYMBOLS, TRACE WORKSPACE with RETAIN and maxElapsedMs 30000. These requests preserve NAME LOCATION SIGNATURE and the same illustrative exact ref.
Effects: read packaged authored schema and use production schema/typed admission. No semantic provider, IDE, subprocess or network effects in assertions. Build provisioning remains outside the behavior budget.
Selectors: baseline :app-server:test --tests '*PublicToolContractTest' --tests '*PublicToolSchemaTest' --tests '*PublicToolContinuationTest' --tests '*PublicToolCompletionPolicyTest'. New characterization :app-server:test --tests '*PublicToolRecipeTest', before and after authored edits. Run each with mise exec -- ./gradlew.
RED: not applicable to prose/default-omission reuse. A compile error or missing dependency cannot prove a product failure.
Stop condition: both revised recipes pass existing production admission and frozen canonical assertions. Existing controls and all other recipe arguments match baseline. Coordinator generates and proves parity after integration.
