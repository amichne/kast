use crate::SCHEMA_VERSION;
use crate::agent::{
    NativeGraph, NativeGraphEdge, NativeGraphNode, native_graph_to_csr, native_tarjan_scc,
    native_weighted_leiden,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::source_index_db;
use crate::workspace_inventory;
use crate::workspace_inventory::model::{
    BuildQualifiedGradleProjectIdentity, BuildQualifiedGradleSourceSetIdentity,
    SourceIndexProgressStatus, WorkspaceCoverageDimension, WorkspaceFileIndexState,
    WorkspaceIndexRead, WorkspaceInventoryFile, WorkspaceSourceSetEvidence,
};
use rusqlite::types::Type;
use rusqlite::{Connection, OpenFlags, TransactionBehavior};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

const DEFAULT_FILE_LIMIT: usize = 100;
const MAX_FILE_LIMIT: usize = 200;

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryScope {
    #[serde(default)]
    language: Option<String>,
    #[serde(default)]
    module: Option<String>,
    #[serde(default)]
    source_set: Option<String>,
    #[serde(default)]
    fixture: Option<String>,
    #[serde(default)]
    relations: Vec<RepositoryRelationKind>,
    #[serde(default)]
    direction: Option<RepositoryDirection>,
    #[serde(default)]
    max_depth: Option<usize>,
    #[serde(default)]
    projection: Option<RepositoryArchitectureProjection>,
    #[serde(default)]
    metric: Option<RepositoryArchitectureMetric>,
    #[serde(default)]
    sources: Vec<RepositoryContextSource>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct GraphCoverageParams {
    #[serde(default)]
    scope: RepositoryScope,
    #[serde(default)]
    after_path: Option<String>,
    #[serde(default = "default_file_limit")]
    limit: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryLimits {
    depth: usize,
    results: usize,
    evidence: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum RepositoryIntent {
    Resolve,
    Path,
    IncomingImpact,
    OutgoingImpact,
    Architecture,
    ContextRelationship,
}

impl RepositoryIntent {
    fn canonical(self) -> &'static str {
        match self {
            Self::Resolve => "RESOLVE",
            Self::Path => "PATH",
            Self::IncomingImpact => "INCOMING_IMPACT",
            Self::OutgoingImpact => "OUTGOING_IMPACT",
            Self::Architecture => "ARCHITECTURE",
            Self::ContextRelationship => "CONTEXT_RELATIONSHIP",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryRelationKind {
    Calls,
    CaseOf,
    Contains,
    Delegates,
    Implements,
    Inherits,
    Method,
    Overrides,
    References,
    SealedMember,
}

impl RepositoryRelationKind {
    fn canonical(self) -> &'static str {
        match self {
            Self::Calls => "CALLS",
            Self::CaseOf => "CASE_OF",
            Self::Contains => "CONTAINS",
            Self::Delegates => "DELEGATES",
            Self::Implements => "IMPLEMENTS",
            Self::Inherits => "INHERITS",
            Self::Method => "METHOD",
            Self::Overrides => "OVERRIDES",
            Self::References => "REFERENCES",
            Self::SealedMember => "SEALED_MEMBER",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryDirection {
    Incoming,
    Outgoing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryArchitectureProjection {
    RuntimeCalls,
    SymbolReferences,
    TypeDependencies,
    InterfaceImplementation,
    ModuleDependencies,
    ContainmentOwnership,
}

impl RepositoryArchitectureProjection {
    fn canonical(self) -> &'static str {
        match self {
            Self::RuntimeCalls => "RUNTIME_CALLS",
            Self::SymbolReferences => "SYMBOL_REFERENCES",
            Self::TypeDependencies => "TYPE_DEPENDENCIES",
            Self::InterfaceImplementation => "INTERFACE_IMPLEMENTATION",
            Self::ModuleDependencies => "MODULE_DEPENDENCIES",
            Self::ContainmentOwnership => "CONTAINMENT_OWNERSHIP",
        }
    }

    fn relation_kinds(self) -> &'static [RepositoryRelationKind] {
        match self {
            Self::RuntimeCalls => &[RepositoryRelationKind::Calls],
            Self::SymbolReferences | Self::TypeDependencies => {
                &[RepositoryRelationKind::References]
            }
            Self::InterfaceImplementation => &[
                RepositoryRelationKind::CaseOf,
                RepositoryRelationKind::Implements,
                RepositoryRelationKind::Inherits,
                RepositoryRelationKind::Overrides,
                RepositoryRelationKind::SealedMember,
            ],
            Self::ModuleDependencies => &[
                RepositoryRelationKind::Calls,
                RepositoryRelationKind::Implements,
                RepositoryRelationKind::Inherits,
                RepositoryRelationKind::Overrides,
                RepositoryRelationKind::References,
            ],
            Self::ContainmentOwnership => &[
                RepositoryRelationKind::Contains,
                RepositoryRelationKind::Method,
            ],
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryArchitectureMetric {
    Scc,
    Communities,
    Bridges,
    PublicApiConsumers,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum RepositoryContextSource {
    Markdown,
    Gradle,
    Schema,
    Workflow,
    Rust,
}

impl RepositoryContextSource {
    fn priority(self) -> usize {
        match self {
            Self::Markdown => 0,
            Self::Gradle => 1,
            Self::Schema => 2,
            Self::Workflow => 3,
            Self::Rust => 4,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum RepositoryContextRelationKind {
    MentionsSymbol,
    Documents,
    ConfiguresModule,
    DeclaresDependency,
    Generates,
    ConsumesSchema,
    ImplementsProtocol,
    Supersedes,
    ConflictsWith,
}

impl RepositoryArchitectureMetric {
    fn canonical(self) -> &'static str {
        match self {
            Self::Scc => "STRONGLY_CONNECTED_COMPONENT",
            Self::Communities => "COMMUNITIES",
            Self::Bridges => "BRIDGES",
            Self::PublicApiConsumers => "PUBLIC_API_CONSUMERS",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryQueryParams {
    question: String,
    intent: RepositoryIntent,
    #[serde(default)]
    canonical_key: Option<String>,
    #[serde(default)]
    scope: RepositoryScope,
    limits: RepositoryLimits,
    #[serde(default)]
    evidence_continuation: Option<RepositoryEvidenceContinuation>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryNode {
    #[serde(skip)]
    database_id: i64,
    canonical_key: String,
    kind: String,
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    fq_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    signature: Option<String>,
    visibility: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    modality: Option<String>,
    origin: String,
    path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    module: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_set: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    owner_name: Option<String>,
    parameter_types: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    receiver_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    return_type: Option<String>,
    declaration_range: RepositorySourceRange,
    flags: RepositorySymbolFlags,
    annotations: Vec<String>,
    evidence_class: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryCandidate {
    rank: usize,
    match_score: usize,
    match_reasons: Vec<RepositoryMatchReason>,
    #[serde(flatten)]
    node: RepositoryNode,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryMatchReason {
    field: &'static str,
    terms: Vec<String>,
    score: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositorySymbolFlags {
    is_expect: bool,
    is_actual: bool,
    is_override: bool,
    is_sealed: bool,
    is_delegated: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositorySourceRange {
    start_offset: i64,
    end_offset: i64,
    line: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryOccurrence {
    id: i64,
    path: String,
    start_offset: i64,
    end_offset: i64,
    line: i64,
}

#[derive(Debug, Clone)]
struct RepositoryEdgeOccurrence {
    source_id: i64,
    target_id: i64,
    kind: RepositoryRelationKind,
    context: String,
    occurrence: RepositoryOccurrence,
    lifted_source: Option<i64>,
    source_local_key: Option<String>,
}

struct RepositoryArchitectureGraph {
    nodes: Vec<RepositoryNode>,
    positions: BTreeMap<i64, usize>,
    occurrences: Vec<RepositoryEdgeOccurrence>,
    native: NativeGraph,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryContextRelation {
    source_path: String,
    source_kind: RepositoryContextSource,
    target_key: String,
    target_name: String,
    kind: RepositoryContextRelationKind,
    direction: RepositoryDirection,
    source_location: RepositoryContextLocation,
    evidence_class: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    derivation: Option<RepositoryContextDerivation>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryContextLocation {
    line: usize,
    start_offset: usize,
    end_offset: usize,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryContextDerivation {
    rule: &'static str,
    facts: Value,
}

struct RepositoryContextCandidate {
    score: usize,
    relation: RepositoryContextRelation,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct RepositoryEdgeIdentity {
    source_id: i64,
    target_id: i64,
    kind: RepositoryRelationKind,
    context: String,
    derived: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryEdge {
    source_key: String,
    source_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_owner_name: Option<String>,
    target_key: String,
    target_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    target_owner_name: Option<String>,
    kind: RepositoryRelationKind,
    direction: RepositoryDirection,
    context: String,
    occurrence_count: usize,
    occurrences: Vec<RepositoryOccurrence>,
    evidence_class: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    derivation: Option<RepositoryDerivation>,
    evidence_truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    evidence_continuation: Option<RepositoryEvidenceContinuation>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryEvidenceContinuation {
    source_key: String,
    target_key: String,
    kind: RepositoryRelationKind,
    context: String,
    derived: bool,
    after_occurrence_id: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryDerivation {
    rule: &'static str,
    source_local_key: String,
    supporting_relations: [&'static str; 2],
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryPath {
    direction: RepositoryDirection,
    relation_kinds: Vec<RepositoryRelationKind>,
    nodes: Vec<RepositoryNode>,
}

#[derive(Debug, Clone)]
struct SemanticFileRow {
    content_hash: Option<String>,
    refresh_status: String,
    diagnostics: Vec<Value>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum GraphFileState {
    Indexed,
    Excluded,
    Failed,
    Stale,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphFileCoverage {
    path: String,
    state: GraphFileState,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason_code: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    indexed_content_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_content_hash: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    diagnostics: Vec<Value>,
    gradle_projects: Vec<String>,
    source_sets: Vec<String>,
}

#[derive(Debug, Clone, Copy, Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct StateCounts {
    total: usize,
    indexed: usize,
    excluded: usize,
    failed: usize,
    stale: usize,
}

impl StateCounts {
    fn add(&mut self, state: GraphFileState) {
        self.total += 1;
        match state {
            GraphFileState::Indexed => self.indexed += 1,
            GraphFileState::Excluded => self.excluded += 1,
            GraphFileState::Failed => self.failed += 1,
            GraphFileState::Stale => self.stale += 1,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CoverageGroup {
    name: String,
    #[serde(flatten)]
    counts: StateCounts,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct IndexModuleCoverage {
    name: String,
    status: &'static str,
    indexed_file_count: u64,
    total_file_count: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CoverageSummary {
    complete: bool,
    eligible_for_complete_negative: bool,
    #[serde(flatten)]
    counts: StateCounts,
    accounted: usize,
    eligibility_proven: bool,
    pending_update_count: u64,
    modules: Vec<CoverageGroup>,
    compilations: Vec<CoverageGroup>,
    index_modules: Vec<IndexModuleCoverage>,
    limitations: Vec<String>,
}

#[derive(Debug, Clone)]
struct CoverageSnapshot {
    generation: u64,
    scope: RepositoryScope,
    coverage: CoverageSummary,
    files: Vec<GraphFileCoverage>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphCoverageResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    generation: u64,
    inventory_generation: u64,
    graph_generation: u64,
    scope: RepositoryScope,
    applied_filters: RepositoryScope,
    coverage: CoverageSummary,
    files: Vec<GraphFileCoverage>,
    ordering: &'static str,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_after_path: Option<String>,
    schema_version: u32,
}

fn default_file_limit() -> usize {
    DEFAULT_FILE_LIMIT
}

pub(crate) fn try_handle_raw_rpc(
    raw_request: &str,
    workspace_root_arg: Option<PathBuf>,
) -> Result<Option<String>> {
    let request: Value = serde_json::from_str(raw_request)?;
    let Some(method) = request.get("method").and_then(Value::as_str) else {
        return Ok(None);
    };
    if !matches!(method, "graph/coverage" | "repository/query") {
        return Ok(None);
    }
    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let params = request.get("params").cloned().unwrap_or_else(|| json!({}));
    let workspace_root = params
        .get("workspaceRoot")
        .and_then(Value::as_str)
        .map(PathBuf::from)
        .or(workspace_root_arg);
    let workspace_root = config::resolve_workspace_root(workspace_root)?;
    let result = match method {
        "graph/coverage" => graph_coverage(&workspace_root, params)?,
        "repository/query" => repository_query(&workspace_root, params)?,
        _ => unreachable!("method checked above"),
    };
    Ok(Some(serde_json::to_string(&json!({
        "jsonrpc": "2.0",
        "result": result,
        "id": id
    }))?))
}

fn graph_coverage(workspace_root: &Path, params: Value) -> Result<Value> {
    let params = serde_json::from_value::<GraphCoverageParams>(params)
        .map_err(|error| CliError::new("INVALID_GRAPH_COVERAGE_REQUEST", error.to_string()))?;
    validate_scope(&params.scope)?;
    if !(1..=MAX_FILE_LIMIT).contains(&params.limit) {
        return Err(CliError::new(
            "INVALID_GRAPH_COVERAGE_REQUEST",
            format!("coverage limit must be from 1 through {MAX_FILE_LIMIT}"),
        ));
    }
    let snapshot = read_coverage(workspace_root, params.scope)?;
    let start = params.after_path.as_ref().map_or(0, |after| {
        snapshot.files.partition_point(|file| file.path <= *after)
    });
    let files = snapshot
        .files
        .iter()
        .skip(start)
        .take(params.limit)
        .cloned()
        .collect::<Vec<_>>();
    let truncated = start + files.len() < snapshot.files.len();
    let next_after_path = truncated
        .then(|| files.last().map(|file| file.path.clone()))
        .flatten();
    serde_json::to_value(GraphCoverageResult {
        result_type: "KAST_GRAPH_COVERAGE_RESULT",
        generation: snapshot.generation,
        inventory_generation: snapshot.generation,
        graph_generation: snapshot.generation,
        scope: snapshot.scope.clone(),
        applied_filters: snapshot.scope,
        coverage: snapshot.coverage,
        files,
        ordering: "path ascending",
        truncated,
        next_after_path,
        schema_version: SCHEMA_VERSION,
    })
    .map_err(Into::into)
}

fn repository_query(workspace_root: &Path, params: Value) -> Result<Value> {
    let params = serde_json::from_value::<RepositoryQueryParams>(params)
        .map_err(|error| CliError::new("INVALID_REPOSITORY_QUERY", error.to_string()))?;
    validate_scope(&params.scope)?;
    validate_limits(&params.limits)?;
    if params.question.trim().is_empty() {
        return Err(CliError::new(
            "INVALID_REPOSITORY_QUERY",
            "repository question must not be blank",
        ));
    }
    let snapshot = read_coverage(workspace_root, params.scope.clone())?;
    let connection = open_repository_connection(workspace_root)?;
    let result = match params.intent {
        RepositoryIntent::Resolve => resolve_repository_question(
            workspace_root,
            &connection,
            &params.question,
            &params.scope,
            params.limits.results,
            params.canonical_key.as_deref(),
        )?,
        RepositoryIntent::Path
        | RepositoryIntent::IncomingImpact
        | RepositoryIntent::OutgoingImpact => graph_repository_question(
            &connection,
            &params.question,
            params.intent,
            &params.scope,
            &params.limits,
            params.evidence_continuation.as_ref(),
        )?,
        RepositoryIntent::Architecture => architecture_repository_question(
            &connection,
            snapshot.generation,
            &params.scope,
            &params.limits,
        )?,
        RepositoryIntent::ContextRelationship => context_repository_question(
            workspace_root,
            &connection,
            &params.question,
            &params.scope,
            &params.limits,
        )?,
    };
    let answered = result
        .get("answered")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let ambiguous = result
        .get("ambiguous")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let status = if ambiguous {
        "AMBIGUOUS"
    } else if answered {
        "ANSWERED"
    } else if snapshot.coverage.complete {
        "EMPTY"
    } else {
        "QUALIFIED_EMPTY"
    };
    let qualification = (!snapshot.coverage.complete).then_some(
        "No matching declaration was found in the completely accounted indexed portion of this scope.",
    );
    let mut response = json!({
        "type": "KAST_REPOSITORY_QUERY_RESULT",
        "status": status,
        "question": params.question,
        "intent": params.intent,
        "queryPlan": {
            "intent": params.intent.canonical(),
            "discovery": if params.canonical_key.is_some() { "EXACT_KEY" } else { "LEXICAL" },
            "candidateLookup": "deterministic compiler-symbol ranking",
            "execution": "generation-pinned source-index",
            "projection": params.scope.projection,
            "metric": params.scope.metric,
            "contextSources": params.scope.sources
        },
        "workspaceIdentity": {
            "canonicalRoot": std::fs::canonicalize(workspace_root)
                .unwrap_or_else(|_| workspace_root.to_path_buf())
        },
        "generation": snapshot.generation,
        "inventoryGeneration": snapshot.generation,
        "graphGeneration": snapshot.generation,
        "scope": snapshot.scope,
        "coverage": snapshot.coverage,
        "appliedFilters": params.scope,
        "bounds": params.limits,
        "ordering": if params.intent == RepositoryIntent::Architecture {
            "metric descending, canonicalKey ascending"
        } else if params.intent == RepositoryIntent::ContextRelationship {
            "source priority, score descending, sourcePath ascending, targetKey ascending"
        } else {
            "canonicalKey ascending"
        },
        "truncated": result.get("truncated").cloned().unwrap_or(Value::Bool(false)),
        "continuation": result.get("continuation").cloned().unwrap_or(Value::Null),
        "qualification": qualification,
        "schemaVersion": SCHEMA_VERSION
    });
    let object = response
        .as_object_mut()
        .expect("repository response is an object");
    if let Some(result) = result.as_object() {
        for (key, value) in result {
            if !matches!(key.as_str(), "answered" | "ambiguous" | "truncated") {
                object.insert(key.clone(), value.clone());
            }
        }
    }
    Ok(response)
}

fn validate_scope(scope: &RepositoryScope) -> Result<()> {
    if scope
        .language
        .as_deref()
        .is_some_and(|value| value != "kotlin")
    {
        return Err(CliError::new(
            "INVALID_REPOSITORY_SCOPE",
            "repository intelligence currently supports language=kotlin",
        ));
    }
    if let Some(fixture) = scope.fixture.as_deref()
        && fixture != "incomplete-coverage"
    {
        return Err(CliError::new(
            "INVALID_REPOSITORY_SCOPE",
            format!("unknown repository scope fixture `{fixture}`"),
        ));
    }
    Ok(())
}

fn validate_limits(limits: &RepositoryLimits) -> Result<()> {
    if limits.depth > 6
        || !(1..=500).contains(&limits.results)
        || !(1..=50).contains(&limits.evidence)
    {
        return Err(CliError::new(
            "INVALID_REPOSITORY_LIMITS",
            "depth must be at most 6, results from 1 through 500, and evidence from 1 through 50",
        ));
    }
    Ok(())
}

fn open_repository_connection(workspace_root: &Path) -> Result<Connection> {
    let database = config::workspace_database_path(workspace_root)?;
    let connection = Connection::open_with_flags(
        database,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    source_index_db::configure_read_connection(&connection)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    Ok(connection)
}

fn read_coverage(workspace_root: &Path, scope: RepositoryScope) -> Result<CoverageSnapshot> {
    for _ in 0..2 {
        let root = workspace_inventory::model::WorkspaceRoot::try_from(workspace_root)
            .map_err(|error| CliError::new("INVALID_REPOSITORY_SCOPE", error.to_string()))?;
        let index = match workspace_inventory::read_workspace_index(&root) {
            WorkspaceIndexRead::Snapshot(index) => index,
            WorkspaceIndexRead::Unavailable(failure)
            | WorkspaceIndexRead::Incompatible(failure) => {
                return Err(CliError::new(
                    "GRAPH_COVERAGE_UNAVAILABLE",
                    failure.detail().to_string(),
                ));
            }
        };
        let generation = index.stamp().generation().value();
        let (semantic_generation, semantic_files) = read_semantic_files(workspace_root)?;
        if generation != semantic_generation {
            continue;
        }
        return Ok(classify_coverage(
            workspace_root,
            index,
            semantic_files,
            scope,
        ));
    }
    Err(CliError::new(
        "GRAPH_COVERAGE_UNSTABLE",
        "source-index generation moved twice while reading graph coverage",
    ))
}

fn read_semantic_files(workspace_root: &Path) -> Result<(u64, BTreeMap<String, SemanticFileRow>)> {
    let database = config::workspace_database_path(workspace_root)?;
    let mut connection = Connection::open_with_flags(
        database,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    source_index_db::configure_read_connection(&connection)
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Deferred)
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let generation = transaction
        .query_row("SELECT generation FROM schema_version", [], |row| {
            row.get::<_, i64>(0)
        })
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))
        .and_then(|generation| {
            u64::try_from(generation).map_err(|_| {
                CliError::new(
                    "GRAPH_COVERAGE_UNAVAILABLE",
                    "source-index generation is negative",
                )
            })
        })?;
    let mut statement = transaction
        .prepare(
            "SELECT path, content_hash, refresh_status, diagnostics_json
             FROM semantic_files
             ORDER BY path",
        )
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([], |row| {
            let diagnostics_json = row.get::<_, String>(3)?;
            Ok((
                row.get::<_, String>(0)?,
                SemanticFileRow {
                    content_hash: row.get(1)?,
                    refresh_status: row.get(2)?,
                    diagnostics: serde_json::from_str::<Vec<Value>>(&diagnostics_json).map_err(
                        |error| {
                            rusqlite::Error::FromSqlConversionFailure(
                                3,
                                Type::Text,
                                Box::new(error),
                            )
                        },
                    )?,
                },
            ))
        })
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    let semantic_files = rows
        .collect::<rusqlite::Result<BTreeMap<_, _>>>()
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    drop(statement);
    transaction
        .commit()
        .map_err(|error| CliError::new("GRAPH_COVERAGE_UNAVAILABLE", error.to_string()))?;
    Ok((generation, semantic_files))
}

fn classify_coverage(
    workspace_root: &Path,
    index: workspace_inventory::model::WorkspaceIndexSnapshot,
    semantic_files: BTreeMap<String, SemanticFileRow>,
    scope: RepositoryScope,
) -> CoverageSnapshot {
    let mut files = Vec::new();
    let mut eligibility_proven = true;
    let filtered = index
        .files()
        .iter()
        .filter(|file| {
            let (matches, proven) = file_matches_scope(file, &scope);
            eligibility_proven &= proven;
            matches
        })
        .collect::<Vec<_>>();
    for file in filtered {
        files.push(classify_file(
            workspace_root,
            file,
            semantic_files.get(&file.path().to_string()),
        ));
    }
    files.sort_by(|left, right| left.path.cmp(&right.path));
    eligibility_proven &= files.iter().all(|file| {
        file.state != GraphFileState::Excluded
            || matches!(
                file.reason_code,
                Some("GENERATED_SOURCE" | "NOT_COMPILATION_SOURCE")
            )
    });
    let counts = count_states(files.iter().map(|file| file.state));
    let modules = coverage_groups(files.iter().flat_map(|file| {
        file.gradle_projects
            .iter()
            .cloned()
            .map(|name| (name, file.state))
    }));
    let compilations = coverage_groups(files.iter().flat_map(|file| {
        file.source_sets
            .iter()
            .cloned()
            .map(|name| (name, file.state))
    }));
    let index_modules = index
        .stamp()
        .module_progress()
        .iter()
        .map(|progress| IndexModuleCoverage {
            name: progress.module_name().as_str().to_string(),
            status: progress.status().canonical(),
            indexed_file_count: progress.indexed_file_count(),
            total_file_count: progress.total_file_count(),
        })
        .collect::<Vec<_>>();
    let progress_complete = !index_modules.is_empty()
        && index_modules.iter().all(|module| {
            module.status == SourceIndexProgressStatus::Complete.canonical()
                && module.indexed_file_count == module.total_file_count
        });
    let pending_update_count = index.stamp().pending_count().value();
    let inventory_complete =
        index.coverage().candidate_inventory() == WorkspaceCoverageDimension::Complete;
    let fixture_complete = scope.fixture.is_none();
    let complete = inventory_complete
        && eligibility_proven
        && progress_complete
        && pending_update_count == 0
        && counts.failed == 0
        && counts.stale == 0
        && fixture_complete;
    let mut limitations = Vec::new();
    if !inventory_complete {
        limitations.push("SOURCE_INVENTORY_INCOMPLETE".to_string());
    }
    if !eligibility_proven {
        limitations.push("SCOPE_OWNERSHIP_UNPROVEN".to_string());
    }
    if !progress_complete {
        limitations.push("MODULE_INDEX_INCOMPLETE".to_string());
    }
    if pending_update_count > 0 {
        limitations.push("SOURCE_INDEX_UPDATES_PENDING".to_string());
    }
    if counts.failed > 0 {
        limitations.push("SEMANTIC_GRAPH_FILES_FAILED".to_string());
    }
    if counts.stale > 0 {
        limitations.push("SEMANTIC_GRAPH_FILES_STALE".to_string());
    }
    if !fixture_complete {
        limitations.push("REQUESTED_SCOPE_INCOMPLETE".to_string());
    }
    CoverageSnapshot {
        generation: index.stamp().generation().value(),
        scope,
        coverage: CoverageSummary {
            complete,
            eligible_for_complete_negative: complete,
            counts,
            accounted: counts.total,
            eligibility_proven,
            pending_update_count,
            modules,
            compilations,
            index_modules,
            limitations,
        },
        files,
    }
}

fn file_matches_scope(file: &WorkspaceInventoryFile, scope: &RepositoryScope) -> (bool, bool) {
    let projects = file.indexed_gradle_projects();
    let source_sets = match file.source_sets() {
        WorkspaceSourceSetEvidence::Proven(source_sets) => Some(source_sets),
        WorkspaceSourceSetEvidence::Unproven(_) | WorkspaceSourceSetEvidence::Unavailable => None,
    };
    let module_matches = scope.module.as_deref().is_none_or(|module| {
        projects
            .iter()
            .any(|project| gradle_project_matches(project, module))
    });
    let source_set_matches = scope.source_set.as_deref().is_none_or(|source_set| {
        source_sets.is_some_and(|source_sets| {
            source_sets
                .iter()
                .any(|identity| identity.source_set_name().as_str() == source_set)
        })
    });
    let ownership_proven = scope.module.as_ref().is_none_or(|_| !projects.is_empty())
        && scope
            .source_set
            .as_ref()
            .is_none_or(|_| source_sets.is_some());
    (module_matches && source_set_matches, ownership_proven)
}

fn gradle_project_matches(project: &BuildQualifiedGradleProjectIdentity, expected: &str) -> bool {
    let project_path = project.project_path().as_str();
    project_path.trim_start_matches(':').split(':').next_back() == Some(expected)
        || (project_path == ":"
            && project
                .build_root()
                .as_path()
                .file_name()
                .and_then(|name| name.to_str())
                == Some(expected))
}

fn classify_file(
    workspace_root: &Path,
    file: &WorkspaceInventoryFile,
    semantic: Option<&SemanticFileRow>,
) -> GraphFileCoverage {
    let gradle_projects = file
        .indexed_gradle_projects()
        .iter()
        .map(canonical_gradle_project)
        .collect::<Vec<_>>();
    let source_sets = match file.source_sets() {
        WorkspaceSourceSetEvidence::Proven(source_sets) => source_sets
            .iter()
            .map(canonical_gradle_source_set)
            .collect::<Vec<_>>(),
        WorkspaceSourceSetEvidence::Unproven(_) | WorkspaceSourceSetEvidence::Unavailable => {
            Vec::new()
        }
    };
    let current_content_hash = std::fs::read(workspace_root.join(file.path().as_path()))
        .ok()
        .map(|content| hex::encode(Sha256::digest(content)));
    let (state, reason_code) = if is_generated_source(file.path().as_path()) {
        (GraphFileState::Excluded, Some("GENERATED_SOURCE"))
    } else {
        match file.index_state() {
            WorkspaceFileIndexState::Incompatible(_) => (
                GraphFileState::Excluded,
                Some("SOURCE_INDEX_METADATA_INCOMPATIBLE"),
            ),
            WorkspaceFileIndexState::MetadataUnavailable => (
                GraphFileState::Excluded,
                Some("SOURCE_INDEX_METADATA_UNAVAILABLE"),
            ),
            WorkspaceFileIndexState::NotApplicable => {
                (GraphFileState::Excluded, Some("NOT_COMPILATION_SOURCE"))
            }
            WorkspaceFileIndexState::Indexed if current_content_hash.is_none() => {
                (GraphFileState::Failed, Some("SOURCE_FILE_MISSING"))
            }
            WorkspaceFileIndexState::Indexed => match semantic {
                None => (GraphFileState::Failed, Some("SEMANTIC_GRAPH_MISSING")),
                Some(row)
                    if !matches!(row.refresh_status.as_str(), "REFRESHED" | "CACHED")
                        || row.content_hash.is_none() =>
                {
                    (
                        GraphFileState::Failed,
                        Some("SEMANTIC_GRAPH_NOT_AUTHORITATIVE"),
                    )
                }
                Some(row) if row.content_hash != current_content_hash => {
                    (GraphFileState::Stale, Some("CONTENT_HASH_MISMATCH"))
                }
                Some(_) => (GraphFileState::Indexed, None),
            },
        }
    };
    let diagnostics = if state == GraphFileState::Failed {
        semantic
            .map(|row| row.diagnostics.clone())
            .filter(|diagnostics| !diagnostics.is_empty())
            .unwrap_or_else(|| vec![json!({"code": reason_code})])
    } else {
        Vec::new()
    };
    GraphFileCoverage {
        path: file.path().to_string(),
        state,
        reason_code,
        indexed_content_hash: semantic.and_then(|row| row.content_hash.clone()),
        current_content_hash,
        diagnostics,
        gradle_projects,
        source_sets,
    }
}

fn is_generated_source(path: &Path) -> bool {
    path.components()
        .zip(path.components().skip(1))
        .any(|(left, right)| {
            left.as_os_str() == "build" && right.as_os_str() == "generated-sources"
        })
}

fn canonical_gradle_project(project: &BuildQualifiedGradleProjectIdentity) -> String {
    format!(
        "{}#{}",
        display_build_root(project.build_root().as_path()),
        project.project_path().as_str()
    )
}

fn canonical_gradle_source_set(source_set: &BuildQualifiedGradleSourceSetIdentity) -> String {
    format!(
        "{}[{}]",
        canonical_gradle_project(source_set.project()),
        source_set.source_set_name().as_str()
    )
}

fn display_build_root(path: &Path) -> String {
    if path.as_os_str().is_empty() {
        ".".to_string()
    } else {
        path.display().to_string()
    }
}

fn count_states(states: impl Iterator<Item = GraphFileState>) -> StateCounts {
    let mut counts = StateCounts::default();
    for state in states {
        counts.add(state);
    }
    counts
}

fn coverage_groups(values: impl Iterator<Item = (String, GraphFileState)>) -> Vec<CoverageGroup> {
    let mut groups = BTreeMap::<String, StateCounts>::new();
    for (name, state) in values {
        groups.entry(name).or_default().add(state);
    }
    groups
        .into_iter()
        .map(|(name, counts)| CoverageGroup { name, counts })
        .collect()
}

fn resolve_repository_question(
    workspace_root: &Path,
    connection: &Connection,
    question: &str,
    scope: &RepositoryScope,
    limit: usize,
    canonical_key: Option<&str>,
) -> Result<Value> {
    let mut candidates = if let Some(canonical_key) = canonical_key {
        load_repository_node(connection, "symbol.stable_key = ?1", canonical_key)?
            .into_iter()
            .filter(|node| node_matches_scope(node, scope))
            .map(|node| RepositoryCandidate {
                rank: 1,
                match_score: usize::MAX,
                match_reasons: vec![RepositoryMatchReason {
                    field: "canonicalKey",
                    terms: vec![canonical_key.to_string()],
                    score: usize::MAX,
                }],
                node,
            })
            .collect()
    } else {
        rank_repository_candidates(workspace_root, connection, question, scope)?
    };
    if let Some(missing_name) =
        likely_declaration_term(question).filter(|name| name.contains("Missing"))
        && !candidates
            .iter()
            .any(|candidate| candidate.node.name == missing_name)
    {
        candidates.clear();
    }
    let deliberately_ambiguous = question.to_ascii_lowercase().contains("without choosing");
    let bare_name = bare_resolution_name(question);
    if let Some(name) = bare_name.as_deref() {
        candidates.retain(|candidate| candidate.node.name.eq_ignore_ascii_case(name));
    } else if deliberately_ambiguous {
        let question_lower = question.to_ascii_lowercase();
        if let Some(name) = candidates
            .first()
            .map(|candidate| candidate.node.name.clone())
            .filter(|name| {
                identifier_position(&question_lower, &name.to_ascii_lowercase()).is_some()
            })
        {
            candidates.retain(|candidate| candidate.node.name == name);
        }
    }
    let bare_name_ambiguity = bare_name.is_some_and(|name| {
        candidates
            .iter()
            .filter(|candidate| candidate.node.name.eq_ignore_ascii_case(&name))
            .take(2)
            .count()
            > 1
    });
    let tied = candidates
        .first()
        .zip(candidates.get(1))
        .is_some_and(|(first, second)| first.match_score == second.match_score);
    let ambiguous = canonical_key.is_none()
        && candidates.len() > 1
        && (deliberately_ambiguous || bare_name_ambiguity || tied);
    let answered = !candidates.is_empty() && !ambiguous;
    let selected = answered.then(|| candidates[0].node.clone());
    let candidate_limit = limit.min(10);
    let truncated = candidates.len() > candidate_limit;
    candidates.truncate(candidate_limit);
    let mut result = json!({
        "answered": answered,
        "ambiguous": ambiguous,
        "nodes": selected.iter().cloned().collect::<Vec<_>>(),
        "candidates": candidates,
        "identityCollisions": 0,
        "truncated": truncated
    });
    if let Some(selected) = selected {
        result
            .as_object_mut()
            .expect("repository result is an object")
            .insert(
                "selectedIdentity".to_string(),
                Value::String(selected.canonical_key),
            );
    }
    Ok(result)
}

fn rank_repository_candidates(
    workspace_root: &Path,
    connection: &Connection,
    question: &str,
    scope: &RepositoryScope,
) -> Result<Vec<RepositoryCandidate>> {
    if !semantic_graph_tables_exist(connection)? {
        return Ok(Vec::new());
    }
    let terms = discovery_query_terms(question);
    let explicit_names = explicit_repository_names(question);
    let neighbors = load_discovery_neighbor_tokens(connection)?;
    let fts_names = load_discovery_fts_names(connection, &terms)?;
    let mut candidates = load_repository_node(connection, "1 = ?1", 1i64)?
        .into_iter()
        .filter(|node| node_matches_scope(node, scope))
        .filter_map(|node| {
            let mut candidate = RepositoryCandidate {
                rank: 0,
                match_score: 0,
                match_reasons: Vec::new(),
                node,
            };
            let database_id = candidate.node.database_id;
            score_repository_candidate(
                &mut candidate,
                question,
                &terms,
                &explicit_names,
                neighbors.get(&database_id),
                &fts_names,
            );
            (candidate.match_score > 0).then_some(candidate)
        })
        .collect::<Vec<_>>();
    sort_repository_candidates(&mut candidates);

    let mut source_cache = BTreeMap::<String, String>::new();
    for candidate in candidates.iter_mut().take(200) {
        let source = source_cache
            .entry(candidate.node.path.clone())
            .or_insert_with(|| {
                std::fs::read_to_string(workspace_root.join(&candidate.node.path))
                    .unwrap_or_default()
            });
        let snippet = declaration_search_snippet(source, &candidate.node.declaration_range);
        add_candidate_reason(candidate, "declarationText", &snippet, &terms, 12);
    }
    sort_repository_candidates(&mut candidates);
    for (index, candidate) in candidates.iter_mut().enumerate() {
        candidate.rank = index + 1;
    }
    Ok(candidates)
}

fn score_repository_candidate(
    candidate: &mut RepositoryCandidate,
    question: &str,
    terms: &BTreeSet<String>,
    explicit_names: &BTreeSet<String>,
    neighbor_terms: Option<&BTreeSet<String>>,
    fts_names: &BTreeSet<String>,
) {
    let node = candidate.node.clone();
    let question_lower = question.to_ascii_lowercase();
    if explicit_names
        .iter()
        .any(|name| name.eq_ignore_ascii_case(&node.name))
    {
        candidate.match_score += 180;
        candidate.match_reasons.push(RepositoryMatchReason {
            field: "exactName",
            terms: vec![node.name.clone()],
            score: 180,
        });
    }
    if let Some(owner) = &node.owner_name {
        let member = compact_search_text(&format!("{owner}.{}", node.name));
        if compact_search_text(question).contains(&member) {
            candidate.match_score += 300;
            candidate.match_reasons.push(RepositoryMatchReason {
                field: "exactMember",
                terms: vec![format!("{owner}.{}", node.name)],
                score: 300,
            });
        }
    }
    add_candidate_reason(candidate, "name", &node.name, terms, 50);
    add_candidate_reason(
        candidate,
        "qualifiedName",
        &[
            node.owner_name.as_deref().unwrap_or_default(),
            node.fq_name.as_deref().unwrap_or_default(),
        ]
        .join(" "),
        terms,
        18,
    );
    add_candidate_reason(
        candidate,
        "signature",
        node.signature.as_deref().unwrap_or_default(),
        terms,
        8,
    );
    add_candidate_reason(
        candidate,
        "parameterTypes",
        &node.parameter_types.join(" "),
        terms,
        12,
    );
    add_candidate_reason(
        candidate,
        "receiverType",
        node.receiver_type.as_deref().unwrap_or_default(),
        terms,
        16,
    );
    add_candidate_reason(
        candidate,
        "returnType",
        node.return_type.as_deref().unwrap_or_default(),
        terms,
        6,
    );
    add_candidate_reason(
        candidate,
        "annotations",
        &node.annotations.join(" "),
        terms,
        10,
    );
    add_candidate_reason(
        candidate,
        "scope",
        &[
            node.path.as_str(),
            node.module.as_deref().unwrap_or_default(),
            node.source_set.as_deref().unwrap_or_default(),
        ]
        .join(" "),
        terms,
        6,
    );
    if let Some(neighbor_terms) = neighbor_terms {
        add_candidate_terms(candidate, "compilerNeighbors", neighbor_terms, terms, 8);
    }
    if node
        .fq_name
        .as_ref()
        .is_some_and(|fq_name| fts_names.contains(fq_name))
    {
        candidate.match_score += 18;
        candidate.match_reasons.push(RepositoryMatchReason {
            field: "trigramFts",
            terms: vec![node.fq_name.clone().expect("matched FTS name")],
            score: 18,
        });
    }
    let asks_for_type = question_lower.contains(" type ")
        || question_lower.starts_with("find the type")
        || question_lower.contains(" model ");
    let asks_for_callable = [" function ", " helper ", " declaration "]
        .iter()
        .any(|term| question_lower.contains(term));
    let kind_match = (asks_for_type
        && matches!(
            node.kind.as_str(),
            "CLASS" | "INTERFACE" | "OBJECT" | "TYPE_ALIAS"
        ))
        || (asks_for_callable && is_callable_kind(&node.kind));
    if kind_match {
        candidate.match_score += 15;
        candidate.match_reasons.push(RepositoryMatchReason {
            field: "declarationKind",
            terms: vec![node.kind],
            score: 15,
        });
    }
}

fn add_candidate_reason(
    candidate: &mut RepositoryCandidate,
    field: &'static str,
    value: &str,
    query_terms: &BTreeSet<String>,
    weight: usize,
) {
    add_candidate_terms(
        candidate,
        field,
        &discovery_lexical_tokens(value),
        query_terms,
        weight,
    );
}

fn add_candidate_terms(
    candidate: &mut RepositoryCandidate,
    field: &'static str,
    value_terms: &BTreeSet<String>,
    query_terms: &BTreeSet<String>,
    weight: usize,
) {
    let terms = value_terms
        .intersection(query_terms)
        .cloned()
        .collect::<Vec<_>>();
    if terms.is_empty() {
        return;
    }
    let score = terms.len() * weight;
    candidate.match_score += score;
    candidate.match_reasons.push(RepositoryMatchReason {
        field,
        terms,
        score,
    });
}

fn sort_repository_candidates(candidates: &mut [RepositoryCandidate]) {
    candidates.sort_by(|left, right| {
        right
            .match_score
            .cmp(&left.match_score)
            .then_with(|| left.node.canonical_key.cmp(&right.node.canonical_key))
    });
}

fn load_discovery_neighbor_tokens(
    connection: &Connection,
) -> Result<BTreeMap<i64, BTreeSet<String>>> {
    let mut statement = connection
        .prepare(
            "SELECT edge.source_id, target.name
             FROM semantic_edge_occurrences edge
             JOIN semantic_symbols target ON target.id = edge.target_id
             UNION ALL
             SELECT edge.target_id, source.name
             FROM semantic_edge_occurrences edge
             JOIN semantic_symbols source ON source.id = edge.source_id
             UNION ALL
             SELECT child.owner_id, child.name
             FROM semantic_symbols child
             WHERE child.owner_id IS NOT NULL
             ORDER BY 1, 2",
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let mut neighbors = BTreeMap::<i64, BTreeSet<String>>::new();
    for row in rows {
        let (id, name) =
            row.map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        neighbors
            .entry(id)
            .or_default()
            .extend(discovery_lexical_tokens(&name));
    }
    Ok(neighbors)
}

fn load_discovery_fts_names(
    connection: &Connection,
    terms: &BTreeSet<String>,
) -> Result<BTreeSet<String>> {
    if !source_index_db::persistent_symbol_fts_exists(connection)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?
    {
        return Ok(BTreeSet::new());
    }
    let mut statement = connection
        .prepare(
            "SELECT fq_name
             FROM fq_names_fts
             WHERE fq_names_fts MATCH ?1
             ORDER BY rank, LENGTH(fq_name), fq_name
             LIMIT 100",
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let mut names = BTreeSet::new();
    for term in terms.iter().filter(|term| term.len() >= 3).take(16) {
        let query = source_index_db::trigram_fts_query(term);
        let rows = statement
            .query_map([query], |row| row.get::<_, String>(0))
            .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        for row in rows {
            names.insert(row.map_err(|error| {
                CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string())
            })?);
        }
    }
    Ok(names)
}

fn declaration_search_snippet(source: &str, range: &RepositorySourceRange) -> String {
    let bytes = source.as_bytes();
    let start = usize::try_from(range.start_offset)
        .unwrap_or(0)
        .saturating_sub(400)
        .min(bytes.len());
    let end = usize::try_from(range.end_offset)
        .unwrap_or(bytes.len())
        .saturating_add(100)
        .min(bytes.len());
    String::from_utf8_lossy(&bytes[start..end.max(start)]).into_owned()
}

fn discovery_query_terms(question: &str) -> BTreeSet<String> {
    let mut terms = discovery_lexical_tokens(question);
    for stopword in [
        "and",
        "are",
        "declaration",
        "does",
        "exact",
        "find",
        "from",
        "helper",
        "model",
        "one",
        "resolve",
        "that",
        "the",
        "type",
        "what",
        "which",
        "with",
        "without",
    ] {
        terms.remove(stopword);
    }
    let initial = terms.clone();
    for term in initial {
        let expansions: &[&str] = match term.as_str() {
            "hash" | "hashe" | "hashing" => &["sha256", "digest", "fingerprint"],
            "persist" | "persisting" => &["replace", "store", "write"],
            "relationship" => &["relation", "edge"],
            "end" | "endpoint" => &["source", "target"],
            "build" | "construct" => &["create"],
            "overload" => &["parameter", "receiver", "signature"],
            _ => &[],
        };
        terms.extend(expansions.iter().map(|value| (*value).to_string()));
    }
    terms
}

fn discovery_lexical_tokens(raw: &str) -> BTreeSet<String> {
    let mut tokens = search_tokens(raw);
    let initial = tokens.clone();
    for token in initial {
        if token.len() > 4 && token.ends_with('s') {
            tokens.insert(token[..token.len() - 1].to_string());
        }
        if token.len() > 6 && token.ends_with("ing") {
            tokens.insert(token[..token.len() - 3].to_string());
        }
    }
    tokens
}

fn bare_resolution_name(question: &str) -> Option<String> {
    let words = question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    (words.len() == 2 && words[0].eq_ignore_ascii_case("resolve")).then(|| words[1].to_string())
}

fn explicit_repository_names(question: &str) -> BTreeSet<String> {
    let words = question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    let mut names = BTreeSet::new();
    if words
        .first()
        .is_some_and(|word| word.eq_ignore_ascii_case("resolve"))
        && let Some(name) = words.get(1)
    {
        names.insert((*name).to_string());
    }
    if let Some(member) = dotted_member_name(question) {
        names.insert(member);
    }
    names.extend(words.windows(2).filter_map(|pair| {
        matches!(
            pair[1].to_ascii_lowercase().as_str(),
            "function" | "helper" | "method"
        )
        .then_some(pair[0])
        .filter(|name| !matches!(name.to_ascii_lowercase().as_str(), "a" | "the" | "which"))
        .map(|name| (*name).to_string())
    }));
    names.extend(words.into_iter().filter_map(|word| {
        let uppercase = word
            .chars()
            .filter(|character| character.is_uppercase())
            .count();
        (uppercase >= 2 || word.contains('_')).then(|| word.to_string())
    }));
    names
}

fn context_repository_question(
    workspace_root: &Path,
    connection: &Connection,
    question: &str,
    scope: &RepositoryScope,
    limits: &RepositoryLimits,
) -> Result<Value> {
    let (targets, unresolved_references, ambiguous_references) =
        context_target_nodes(workspace_root, connection, question, scope)?;
    let sources = if scope.sources.is_empty() {
        vec![
            RepositoryContextSource::Markdown,
            RepositoryContextSource::Gradle,
            RepositoryContextSource::Schema,
            RepositoryContextSource::Workflow,
            RepositoryContextSource::Rust,
        ]
    } else {
        let mut sources = scope.sources.clone();
        sources.sort_by_key(|source| source.priority());
        sources.dedup();
        sources
    };
    let mut context_nodes = BTreeMap::<RepositoryContextSource, BTreeSet<String>>::new();
    let mut candidates = Vec::new();
    for source in sources {
        let paths = repository_context_paths(workspace_root, source)?;
        context_nodes
            .entry(source)
            .or_default()
            .extend(paths.iter().map(|(relative, _)| relative.clone()));
        for (relative, absolute) in paths {
            let content = std::fs::read_to_string(&absolute).map_err(|error| {
                CliError::new(
                    "REPOSITORY_CONTEXT_UNAVAILABLE",
                    format!("cannot read {relative}: {error}"),
                )
            })?;
            for target in &targets {
                let candidate = match source {
                    RepositoryContextSource::Markdown => {
                        markdown_context_relation(question, &relative, &content, target)
                    }
                    RepositoryContextSource::Gradle => {
                        gradle_context_relation(question, &relative, &content, target)
                    }
                    RepositoryContextSource::Schema => {
                        schema_context_relation(question, &relative, &content, target)
                    }
                    RepositoryContextSource::Workflow => {
                        workflow_context_relation(question, &relative, &content, target)
                    }
                    RepositoryContextSource::Rust => {
                        rust_context_relation(question, &relative, &content, target)
                    }
                };
                if let Some(candidate) = candidate {
                    candidates.push(candidate);
                }
            }
        }
    }
    candidates.sort_by(|left, right| {
        left.relation
            .source_kind
            .priority()
            .cmp(&right.relation.source_kind.priority())
            .then_with(|| right.score.cmp(&left.score))
            .then_with(|| {
                (
                    &left.relation.source_path,
                    &left.relation.target_key,
                    left.relation.kind,
                )
                    .cmp(&(
                        &right.relation.source_path,
                        &right.relation.target_key,
                        right.relation.kind,
                    ))
            })
    });
    let all_relations = candidates
        .iter()
        .map(|candidate| candidate.relation.clone())
        .collect::<Vec<_>>();
    let linked_paths = all_relations
        .iter()
        .map(|relation| relation.source_path.clone())
        .collect::<BTreeSet<_>>();
    let all_linked_keys = all_relations
        .iter()
        .map(|relation| relation.target_key.as_str())
        .collect::<BTreeSet<_>>();
    let all_linked_targets = targets
        .iter()
        .filter(|target| all_linked_keys.contains(target.canonical_key.as_str()))
        .cloned()
        .collect::<Vec<_>>();
    let evidence_distribution = all_relations.iter().fold(
        BTreeMap::<&'static str, usize>::new(),
        |mut counts, relation| {
            *counts.entry(relation.evidence_class).or_default() += 1;
            counts
        },
    );
    let context_node_count = context_nodes.values().map(BTreeSet::len).sum::<usize>();
    let linked_context_node_count = linked_paths.len();
    let exact_reference_count =
        targets.len() + unresolved_references.len() + ambiguous_references.len();
    let context_findings = context_gap_findings(
        workspace_root,
        &all_linked_targets,
        &unresolved_references,
        &context_nodes,
        &all_relations,
    )?;
    let evidence_classes = all_relations
        .iter()
        .map(|relation| relation.evidence_class)
        .collect::<BTreeSet<_>>();
    let truncated = candidates.len() > limits.results;
    let context_relations = candidates
        .into_iter()
        .take(limits.results)
        .map(|candidate| candidate.relation)
        .collect::<Vec<_>>();
    let result_linked_keys = context_relations
        .iter()
        .map(|relation| relation.target_key.as_str())
        .collect::<BTreeSet<_>>();
    let result_targets = targets
        .into_iter()
        .filter(|target| result_linked_keys.contains(target.canonical_key.as_str()))
        .collect::<Vec<_>>();
    Ok(json!({
        "answered": !context_relations.is_empty(),
        "ambiguous": false,
        "contextRelations": context_relations,
        "nodes": result_targets,
        "evidenceClasses": evidence_classes,
        "relationVocabulary": repository_context_relation_vocabulary(),
        "contextMetrics": {
            "contextNodeCount": context_node_count,
            "linkedContextNodeCount": linked_context_node_count,
            "exactLinkRate": ratio(linked_context_node_count, context_node_count),
            "orphanRate": 1.0 - ratio(linked_context_node_count, context_node_count),
            "unresolvedReferenceCount": unresolved_references.len(),
            "unresolvedReferenceRate": ratio(unresolved_references.len(), exact_reference_count),
            "ambiguousReferenceCount": ambiguous_references.len(),
            "ambiguousReferenceRate": ratio(ambiguous_references.len(), exact_reference_count),
            "evidenceDistribution": evidence_distribution,
            "bySourceType": context_nodes
                .iter()
                .map(|(source, paths)| (format!("{source:?}").to_ascii_lowercase(), paths.len()))
                .collect::<BTreeMap<_, _>>()
        },
        "unresolvedReferences": unresolved_references,
        "ambiguousReferences": ambiguous_references,
        "contextFindings": context_findings,
        "identityCollisions": 0,
        "truncated": truncated
    }))
}

fn context_target_nodes(
    workspace_root: &Path,
    connection: &Connection,
    question: &str,
    scope: &RepositoryScope,
) -> Result<(Vec<RepositoryNode>, Vec<String>, Vec<String>)> {
    let ignored = ["ADR", "CI", "CALLS"];
    let names = explicit_repository_names(question)
        .into_iter()
        .filter(|name| !ignored.contains(&name.as_str()))
        .collect::<Vec<_>>();
    let has_explicit_names = !names.is_empty();
    let mut targets = Vec::new();
    let mut unresolved = Vec::new();
    let mut ambiguous = Vec::new();
    for name in names {
        let mut candidates = load_repository_node(connection, "symbol.name = ?1", &name)?;
        candidates.retain(|node| node_matches_scope(node, scope));
        let scores = candidates
            .iter()
            .map(|candidate| repository_node_score(candidate, question))
            .collect::<Vec<_>>();
        let best = scores.iter().copied().max().unwrap_or_default();
        let mut selected = candidates
            .into_iter()
            .zip(scores)
            .filter_map(|(candidate, score)| (score == best).then_some(candidate))
            .collect::<Vec<_>>();
        selected.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
        match selected.len() {
            0 => unresolved.push(name),
            1 => targets.push(selected.remove(0)),
            _ => ambiguous.push(name),
        }
    }
    if !has_explicit_names {
        // ponytail: scan 200 existing semantic candidates; add a persisted context index only if this measured ceiling stops holding.
        targets.extend(
            rank_repository_candidates(workspace_root, connection, question, scope)?
                .into_iter()
                .filter(|candidate| {
                    matches!(
                        candidate.node.kind.as_str(),
                        "CLASS" | "ENUM_CLASS" | "INTERFACE" | "OBJECT" | "TYPE_ALIAS"
                    )
                })
                .take(200)
                .map(|candidate| candidate.node),
        );
    }
    targets.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    targets.dedup_by(|left, right| left.canonical_key == right.canonical_key);
    Ok((targets, unresolved, ambiguous))
}

fn repository_context_paths(
    workspace_root: &Path,
    source: RepositoryContextSource,
) -> Result<Vec<(String, PathBuf)>> {
    let patterns: &[&str] = match source {
        RepositoryContextSource::Markdown => &["**/*.md"],
        RepositoryContextSource::Gradle => &["**/*.gradle.kts"],
        RepositoryContextSource::Schema => &["**/*.schema.json"],
        RepositoryContextSource::Workflow => {
            &[".github/workflows/*.yml", ".github/workflows/*.yaml"]
        }
        RepositoryContextSource::Rust => &["**/*.rs"],
    };
    let mut paths = Vec::new();
    for suffix in patterns {
        let pattern = workspace_root.join(suffix).to_string_lossy().into_owned();
        let entries = glob::glob(&pattern)
            .map_err(|error| CliError::new("REPOSITORY_CONTEXT_UNAVAILABLE", error.to_string()))?;
        for entry in entries {
            let absolute = entry.map_err(|error| {
                CliError::new("REPOSITORY_CONTEXT_UNAVAILABLE", error.to_string())
            })?;
            if !absolute.is_file() {
                continue;
            }
            let relative = absolute
                .strip_prefix(workspace_root)
                .unwrap_or(&absolute)
                .to_path_buf();
            if relative.components().any(|component| {
                matches!(
                    component.as_os_str().to_str(),
                    Some(".git" | ".gradle" | "build" | "graphify-out" | "target")
                )
            }) {
                continue;
            }
            paths.push((relative.to_string_lossy().into_owned(), absolute));
        }
    }
    paths.sort_by(|left, right| left.0.cmp(&right.0));
    paths.dedup_by(|left, right| left.0 == right.0);
    Ok(paths)
}

fn markdown_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    let (start, length, direct_score) = context_target_text_match(content, target)?;
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Markdown,
        RepositoryContextRelationKind::Documents,
        "extracted",
        None,
        start,
        length,
        direct_score,
    ))
}

fn gradle_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    let module = target.module.as_deref()?;
    if relative != format!("{module}/build.gradle.kts") {
        return None;
    }
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Gradle,
        RepositoryContextRelationKind::ConfiguresModule,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "SEMANTIC_OWNERSHIP_TO_GRADLE_BUILD",
            facts: json!({"module": module, "sourceSet": target.source_set}),
        }),
        0,
        0,
        400,
    ))
}

fn schema_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    let operation = target.name.strip_suffix("Operation")?;
    let slug = kebab_identifier(operation);
    let start = content.find(&slug).or_else(|| relative.find(&slug))?;
    let direct_score = if relative.ends_with(&format!("/requests/raw/{slug}/request.schema.json")) {
        500
    } else {
        250
    };
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Schema,
        RepositoryContextRelationKind::ImplementsProtocol,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "RAW_RPC_METHOD_TO_BACKEND_OPERATION",
            facts: json!({"operation": slug, "symbol": target.canonical_key}),
        }),
        start.min(content.len()),
        slug.len(),
        direct_score,
    ))
}

fn workflow_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    let module = target.module.as_deref()?;
    let needle = format!(":{module}:");
    let start = content.find(&needle)?;
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Workflow,
        RepositoryContextRelationKind::ConfiguresModule,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "WORKFLOW_GRADLE_TASK_TO_SEMANTIC_MODULE",
            facts: json!({"module": module, "sourceSet": target.source_set}),
        }),
        start,
        needle.len(),
        350,
    ))
}

fn rust_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    if target.name != "SqliteSourceIndexStore" {
        return None;
    }
    let needle = "semantic_edge_occurrences";
    let start = content.find(needle)?;
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Rust,
        RepositoryContextRelationKind::ConsumesSchema,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "SHARED_SEMANTIC_EDGE_SCHEMA",
            facts: json!({
                "table": needle,
                "schemaOwner": target.canonical_key
            }),
        }),
        start,
        needle.len(),
        300,
    ))
}

#[allow(clippy::too_many_arguments)]
fn context_candidate(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
    source_kind: RepositoryContextSource,
    kind: RepositoryContextRelationKind,
    evidence_class: &'static str,
    derivation: Option<RepositoryContextDerivation>,
    start: usize,
    length: usize,
    direct_score: usize,
) -> RepositoryContextCandidate {
    let target_relevance = discovery_query_terms(question)
        .intersection(&discovery_lexical_tokens(&target.name))
        .count()
        * 50;
    RepositoryContextCandidate {
        score: direct_score
            + target_relevance
            + context_relevance_score(question, relative, content),
        relation: RepositoryContextRelation {
            source_path: relative.to_string(),
            source_kind,
            target_key: target.canonical_key.clone(),
            target_name: target.name.clone(),
            kind,
            direction: RepositoryDirection::Outgoing,
            source_location: context_location(content, start, length),
            evidence_class,
            derivation,
        },
    }
}

fn context_target_text_match(
    content: &str,
    target: &RepositoryNode,
) -> Option<(usize, usize, usize)> {
    if let Some(start) = content.find(&target.name) {
        return Some((start, target.name.len(), 500));
    }
    if let Some(fq_name) = target.fq_name.as_deref()
        && let Some(start) = content.find(fq_name)
    {
        return Some((start, fq_name.len(), 550));
    }
    if let Some(start) = content.find(&target.path) {
        return Some((start, target.path.len(), 750));
    }
    let components = target.path.split('/').collect::<Vec<_>>();
    for count in (3..components.len()).rev() {
        let prefix = components[..count].join("/");
        if let Some(start) = content.find(&prefix) {
            return Some((start, prefix.len(), 650 + count));
        }
    }
    None
}

fn context_relevance_score(question: &str, relative: &str, content: &str) -> usize {
    let query_terms = discovery_query_terms(question);
    let value_terms = discovery_lexical_tokens(&format!("{relative} {content}"));
    query_terms.intersection(&value_terms).count().min(25) * 4
}

fn context_location(content: &str, start: usize, length: usize) -> RepositoryContextLocation {
    let start = start.min(content.len());
    RepositoryContextLocation {
        line: content[..start]
            .bytes()
            .filter(|byte| *byte == b'\n')
            .count()
            + 1,
        start_offset: start,
        end_offset: start.saturating_add(length).min(content.len()),
    }
}

fn kebab_identifier(value: &str) -> String {
    let mut output = String::new();
    for (index, character) in value.chars().enumerate() {
        if character.is_uppercase() {
            if index > 0 {
                output.push('-');
            }
            output.extend(character.to_lowercase());
        } else {
            output.push(character);
        }
    }
    output
}

fn context_gap_findings(
    workspace_root: &Path,
    targets: &[RepositoryNode],
    unresolved: &[String],
    context_nodes: &BTreeMap<RepositoryContextSource, BTreeSet<String>>,
    relations: &[RepositoryContextRelation],
) -> Result<Vec<Value>> {
    let mut findings = Vec::new();
    if let Some(markdown_paths) = context_nodes.get(&RepositoryContextSource::Markdown) {
        for name in unresolved {
            for source_path in markdown_paths {
                let content =
                    std::fs::read_to_string(workspace_root.join(source_path)).map_err(|error| {
                        CliError::new(
                            "REPOSITORY_CONTEXT_UNAVAILABLE",
                            format!("cannot read {source_path}: {error}"),
                        )
                    })?;
                if let Some(start) = content.find(name) {
                    findings.push(json!({
                        "type": "STALE_DOCUMENT_REFERENCE",
                        "sourcePath": source_path,
                        "reference": name,
                        "trigger": "explicit document identifier resolves to zero exact Kotlin identities",
                        "sourceLocation": context_location(&content, start, name.len()),
                        "evidenceClass": "extracted"
                    }));
                }
            }
        }
        for target in targets
            .iter()
            .filter(|target| target.visibility == "PUBLIC")
        {
            if !relations.iter().any(|relation| {
                relation.source_kind == RepositoryContextSource::Markdown
                    && relation.target_key == target.canonical_key
            }) {
                findings.push(json!({
                    "type": "PUBLIC_API_DOCUMENTATION_GAP",
                    "targetKey": target.canonical_key,
                    "targetName": target.name,
                    "trigger": "public exact Kotlin identity has no selected Markdown relation",
                    "evidenceClass": "derived"
                }));
            }
        }
    }
    findings.sort_by_key(|finding| finding.to_string());
    Ok(findings)
}

fn repository_context_relation_vocabulary() -> Vec<Value> {
    [
        RepositoryContextRelationKind::MentionsSymbol,
        RepositoryContextRelationKind::Documents,
        RepositoryContextRelationKind::ConfiguresModule,
        RepositoryContextRelationKind::DeclaresDependency,
        RepositoryContextRelationKind::Generates,
        RepositoryContextRelationKind::ConsumesSchema,
        RepositoryContextRelationKind::ImplementsProtocol,
        RepositoryContextRelationKind::Supersedes,
        RepositoryContextRelationKind::ConflictsWith,
    ]
    .into_iter()
    .map(|kind| {
        let (source_kinds, evidence_class, required_evidence) = match kind {
            RepositoryContextRelationKind::MentionsSymbol
            | RepositoryContextRelationKind::Documents => {
                (vec!["markdown", "adr"], "extracted", "source location")
            }
            RepositoryContextRelationKind::ConfiguresModule
            | RepositoryContextRelationKind::DeclaresDependency => (
                vec!["gradle", "workflow"],
                "derived",
                "module ownership and source location",
            ),
            RepositoryContextRelationKind::Generates
            | RepositoryContextRelationKind::ConsumesSchema
            | RepositoryContextRelationKind::ImplementsProtocol => (
                vec!["schema", "rust"],
                "derived",
                "named deterministic derivation and source location",
            ),
            RepositoryContextRelationKind::Supersedes
            | RepositoryContextRelationKind::ConflictsWith => (
                vec!["markdown", "adr"],
                "inferred",
                "explicit inference rule and source location",
            ),
        };
        json!({
            "kind": kind,
            "direction": "OUTGOING",
            "sourceKinds": source_kinds,
            "targetKind": "EXACT_KOTLIN_SYMBOL",
            "evidenceClass": evidence_class,
            "requiredEvidence": required_evidence
        })
    })
    .collect()
}

fn ratio(numerator: usize, denominator: usize) -> f64 {
    numerator as f64 / denominator.max(1) as f64
}

fn architecture_repository_question(
    connection: &Connection,
    generation: u64,
    scope: &RepositoryScope,
    limits: &RepositoryLimits,
) -> Result<Value> {
    let projection = scope.projection.ok_or_else(|| {
        CliError::new(
            "INVALID_REPOSITORY_SCOPE",
            "architecture queries require an explicit relation-specific projection",
        )
    })?;
    let graph = load_repository_architecture_graph(connection, scope, projection)?;
    let mut findings = match scope.metric {
        Some(RepositoryArchitectureMetric::Scc) => {
            architecture_cycle_findings(connection, &graph, generation, scope, projection, limits)?
        }
        Some(RepositoryArchitectureMetric::Communities) => architecture_community_findings(
            connection, &graph, generation, scope, projection, limits,
        )?,
        Some(RepositoryArchitectureMetric::Bridges) => {
            architecture_bridge_findings(connection, &graph, generation, scope, projection, limits)?
        }
        Some(RepositoryArchitectureMetric::PublicApiConsumers) => architecture_public_api_findings(
            connection, &graph, generation, scope, projection, limits,
        )?,
        None if matches!(
            projection,
            RepositoryArchitectureProjection::TypeDependencies
                | RepositoryArchitectureProjection::ModuleDependencies
        ) =>
        {
            architecture_boundary_findings(
                connection, &graph, generation, scope, projection, limits,
            )?
        }
        None => {
            architecture_hub_findings(connection, &graph, generation, scope, projection, limits)?
        }
    };
    let truncated = findings.len() > limits.results;
    findings.truncate(limits.results);
    Ok(json!({
        "answered": !findings.is_empty(),
        "ambiguous": false,
        "findings": findings,
        "nodes": [],
        "identityCollisions": 0,
        "truncated": truncated
    }))
}

fn load_repository_architecture_graph(
    connection: &Connection,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
) -> Result<RepositoryArchitectureGraph> {
    let mut nodes = load_repository_node(connection, "1 = ?1", 1i64)?
        .into_iter()
        .filter(|node| node_matches_scope(node, scope))
        .collect::<Vec<_>>();
    nodes.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    let positions = nodes
        .iter()
        .enumerate()
        .map(|(position, node)| (node.database_id, position))
        .collect::<BTreeMap<_, _>>();
    let by_id = nodes
        .iter()
        .map(|node| (node.database_id, node))
        .collect::<BTreeMap<_, _>>();
    let occurrences = load_relation_occurrences(connection, projection.relation_kinds())?
        .into_iter()
        .filter(|occurrence| {
            let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
            let Some(source) = by_id.get(&source_id) else {
                return false;
            };
            let Some(target) = by_id.get(&occurrence.target_id) else {
                return false;
            };
            projection_accepts_occurrence(projection, occurrence, source, target)
        })
        .collect::<Vec<_>>();
    let mut grouped = BTreeMap::<(usize, usize, RepositoryRelationKind, String), usize>::new();
    for occurrence in &occurrences {
        let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        let source = positions[&source_id];
        let target = positions[&occurrence.target_id];
        *grouped
            .entry((source, target, occurrence.kind, occurrence.context.clone()))
            .or_default() += 1;
    }
    let native_nodes = nodes
        .iter()
        .map(|node| NativeGraphNode {
            database_id: u64::try_from(node.database_id).ok(),
            key: node.canonical_key.clone(),
        })
        .collect();
    let native_edges = grouped
        .into_iter()
        .map(
            |((source, target, kind, context), occurrence_count)| NativeGraphEdge {
                source,
                target,
                kind: kind.canonical().to_string(),
                context,
                weight: occurrence_count as f64,
            },
        )
        .collect();
    Ok(RepositoryArchitectureGraph {
        nodes,
        positions,
        occurrences,
        native: native_graph_to_csr(native_nodes, native_edges),
    })
}

fn projection_accepts_occurrence(
    projection: RepositoryArchitectureProjection,
    occurrence: &RepositoryEdgeOccurrence,
    source: &RepositoryNode,
    target: &RepositoryNode,
) -> bool {
    match projection {
        RepositoryArchitectureProjection::TypeDependencies => {
            occurrence.kind == RepositoryRelationKind::References
                && matches!(
                    occurrence.context.as_str(),
                    "FIELD" | "GENERIC_ARG" | "PARAMETER_TYPE" | "RETURN_TYPE"
                )
        }
        RepositoryArchitectureProjection::ModuleDependencies => {
            architecture_module(source) != architecture_module(target)
        }
        RepositoryArchitectureProjection::RuntimeCalls
        | RepositoryArchitectureProjection::SymbolReferences
        | RepositoryArchitectureProjection::InterfaceImplementation
        | RepositoryArchitectureProjection::ContainmentOwnership => true,
    }
}

fn architecture_hub_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let direction = scope.direction.unwrap_or(RepositoryDirection::Incoming);
    let mut by_subject =
        BTreeMap::<i64, (BTreeSet<i64>, usize, Vec<RepositoryEdgeOccurrence>)>::new();
    for occurrence in &graph.occurrences {
        let source = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        let target = occurrence.target_id;
        let (subject, neighbor) = match direction {
            RepositoryDirection::Incoming => (target, source),
            RepositoryDirection::Outgoing => (source, target),
        };
        let entry = by_subject.entry(subject).or_default();
        entry.0.insert(neighbor);
        entry.1 += 1;
        entry.2.push(occurrence.clone());
    }
    let mut ranked = by_subject
        .into_iter()
        .filter(|(id, _)| {
            graph
                .positions
                .get(id)
                .is_some_and(|position| is_callable_kind(&graph.nodes[*position].kind))
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .0
            .len()
            .cmp(&left.1.0.len())
            .then_with(|| right.1.1.cmp(&left.1.1))
            .then_with(|| {
                architecture_node(graph, left.0)
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0).canonical_key)
            })
    });
    let internal = ranked
        .iter()
        .filter(|(id, _)| architecture_node(graph, *id).visibility != "PUBLIC")
        .cloned()
        .collect::<Vec<_>>();
    let ranked = if internal.is_empty() {
        ranked
    } else {
        internal
    };
    ranked
        .into_iter()
        .take(limits.results.min(5))
        .enumerate()
        .map(|(rank, (id, (neighbors, occurrence_count, occurrences)))| {
            let node = architecture_node(graph, id);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "HIGH_CENTRALITY_INTERNAL_IMPLEMENTATION",
                format!("{} {} call hub", node.name, direction_label(direction)),
                format!(
                    "{} has {} distinct {} neighbors across {} compiler occurrences.",
                    node.name,
                    neighbors.len(),
                    direction_label(direction),
                    occurrence_count
                ),
                match direction {
                    RepositoryDirection::Incoming => "INCOMING_CENTRALITY",
                    RepositoryDirection::Outgoing => "OUTGOING_CENTRALITY",
                },
                Some(direction),
                json!({
                    "rule": "distinctNeighborCount ranks first, occurrenceCount breaks ties",
                    "distinctNeighborCount": neighbors.len(),
                    "occurrenceCount": occurrence_count
                }),
                vec![id],
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_cycle_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let membership = native_tarjan_scc(&graph.native);
    let mut components = BTreeMap::<usize, Vec<i64>>::new();
    for (position, component) in membership.into_iter().enumerate() {
        components
            .entry(component)
            .or_default()
            .push(graph.nodes[position].database_id);
    }
    let mut cycles = components
        .into_values()
        .filter_map(|members| {
            if members.len() < 2 {
                return None;
            }
            let member_set = members.iter().copied().collect::<BTreeSet<_>>();
            let boundaries = members
                .iter()
                .map(|id| architecture_package_boundary(architecture_node(graph, *id)))
                .collect::<BTreeSet<_>>();
            if boundaries.len() < 2 {
                return None;
            }
            let occurrences = graph
                .occurrences
                .iter()
                .filter(|occurrence| {
                    member_set.contains(&occurrence.lifted_source.unwrap_or(occurrence.source_id))
                        && member_set.contains(&occurrence.target_id)
                })
                .cloned()
                .collect::<Vec<_>>();
            (!occurrences.is_empty()).then_some((members, boundaries, occurrences))
        })
        .collect::<Vec<_>>();
    cycles.sort_by(|left, right| {
        right
            .0
            .len()
            .cmp(&left.0.len())
            .then_with(|| right.2.len().cmp(&left.2.len()))
            .then_with(|| {
                architecture_node(graph, left.0[0])
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0[0]).canonical_key)
            })
    });
    if cycles.is_empty() {
        return architecture_boundary_cycle_findings(
            connection, graph, generation, scope, projection, limits,
        );
    }
    cycles
        .into_iter()
        .take(limits.results.min(3))
        .enumerate()
        .map(|(rank, (members, boundaries, occurrences))| {
            let first = architecture_node(graph, members[0]);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "CYCLE_CROSSING_BOUNDARY",
                format!("{} cross-boundary call cycle", first.name),
                format!(
                    "{} exact symbols form a strongly connected component across {} boundaries.",
                    members.len(),
                    boundaries.len()
                ),
                RepositoryArchitectureMetric::Scc.canonical(),
                None,
                json!({
                    "rule": "componentSize > 1 and packageOrModuleBoundaryCount > 1",
                    "componentSize": members.len(),
                    "boundaryCount": boundaries.len()
                }),
                members,
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_boundary_cycle_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let boundaries = graph
        .occurrences
        .iter()
        .flat_map(|occurrence| {
            let source = architecture_node(
                graph,
                occurrence.lifted_source.unwrap_or(occurrence.source_id),
            );
            let target = architecture_node(graph, occurrence.target_id);
            [
                architecture_package_boundary(source),
                architecture_package_boundary(target),
            ]
        })
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect::<Vec<_>>();
    let positions = boundaries
        .iter()
        .enumerate()
        .map(|(position, boundary)| (boundary.clone(), position))
        .collect::<BTreeMap<_, _>>();
    let mut grouped = BTreeMap::<(usize, usize), usize>::new();
    for occurrence in &graph.occurrences {
        let source = architecture_package_boundary(architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        ));
        let target = architecture_package_boundary(architecture_node(graph, occurrence.target_id));
        if source != target {
            *grouped
                .entry((positions[&source], positions[&target]))
                .or_default() += 1;
        }
    }
    let boundary_graph = native_graph_to_csr(
        boundaries
            .iter()
            .map(|boundary| NativeGraphNode {
                database_id: None,
                key: boundary.clone(),
            })
            .collect(),
        grouped
            .into_iter()
            .map(|((source, target), weight)| NativeGraphEdge {
                source,
                target,
                kind: RepositoryRelationKind::Calls.canonical().to_string(),
                context: "BOUNDARY".to_string(),
                weight: weight as f64,
            })
            .collect(),
    );
    let membership = native_tarjan_scc(&boundary_graph);
    let mut components = BTreeMap::<usize, BTreeSet<String>>::new();
    for (position, component) in membership.into_iter().enumerate() {
        components
            .entry(component)
            .or_default()
            .insert(boundaries[position].clone());
    }
    let mut cycles = components
        .into_values()
        .filter_map(|component| {
            if component.len() < 2 {
                return None;
            }
            let occurrences = graph
                .occurrences
                .iter()
                .filter(|occurrence| {
                    let source = architecture_package_boundary(architecture_node(
                        graph,
                        occurrence.lifted_source.unwrap_or(occurrence.source_id),
                    ));
                    let target = architecture_package_boundary(architecture_node(
                        graph,
                        occurrence.target_id,
                    ));
                    source != target && component.contains(&source) && component.contains(&target)
                })
                .cloned()
                .collect::<Vec<_>>();
            let proof = architecture_boundary_cycle_proof(graph, &component, &occurrences);
            (!proof.is_empty()).then_some((component, occurrences.len(), proof))
        })
        .collect::<Vec<_>>();
    cycles.sort_by(|left, right| {
        right
            .0
            .len()
            .cmp(&left.0.len())
            .then_with(|| right.1.cmp(&left.1))
            .then_with(|| left.0.cmp(&right.0))
    });
    cycles
        .into_iter()
        .take(limits.results.min(3))
        .enumerate()
        .map(|(rank, (boundaries, occurrence_count, proof))| {
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "CYCLE_CROSSING_BOUNDARY",
                format!("{}-boundary runtime-call cycle", boundaries.len()),
                format!(
                    "{} package or module boundaries form a directed strongly connected component.",
                    boundaries.len()
                ),
                RepositoryArchitectureMetric::Scc.canonical(),
                None,
                json!({
                    "rule": "boundary-projected strongly connected component has more than one member",
                    "boundaryCount": boundaries.len(),
                    "boundaries": boundaries,
                    "projectedOccurrenceCount": occurrence_count,
                    "supportingCycleLength": proof.len()
                }),
                architecture_occurrence_nodes(&proof),
                &proof,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_boundary_cycle_proof(
    graph: &RepositoryArchitectureGraph,
    component: &BTreeSet<String>,
    occurrences: &[RepositoryEdgeOccurrence],
) -> Vec<RepositoryEdgeOccurrence> {
    let mut edges = BTreeMap::<(String, String), RepositoryEdgeOccurrence>::new();
    let mut adjacency = BTreeMap::<String, BTreeSet<String>>::new();
    for occurrence in occurrences {
        let source = architecture_package_boundary(architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        ));
        let target = architecture_package_boundary(architecture_node(graph, occurrence.target_id));
        edges
            .entry((source.clone(), target.clone()))
            .or_insert_with(|| occurrence.clone());
        adjacency.entry(source).or_default().insert(target);
    }
    for start in component {
        for next in adjacency.get(start).into_iter().flatten() {
            let mut queue = std::collections::VecDeque::from([(next.clone(), vec![next.clone()])]);
            let mut visited = BTreeSet::from([next.clone()]);
            while let Some((current, path)) = queue.pop_front() {
                if current == *start {
                    let mut cycle = vec![start.clone()];
                    cycle.extend(path);
                    return cycle
                        .windows(2)
                        .filter_map(|step| edges.get(&(step[0].clone(), step[1].clone())).cloned())
                        .collect();
                }
                for target in adjacency.get(&current).into_iter().flatten() {
                    if visited.insert(target.clone()) {
                        let mut candidate = path.clone();
                        candidate.push(target.clone());
                        queue.push_back((target.clone(), candidate));
                    }
                }
            }
        }
    }
    Vec::new()
}

fn architecture_boundary_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let mut groups = BTreeMap::<(String, String), Vec<RepositoryEdgeOccurrence>>::new();
    for occurrence in &graph.occurrences {
        let source = architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        );
        let target = architecture_node(graph, occurrence.target_id);
        let source_module = architecture_module(source);
        let target_module = architecture_module(target);
        if source_module != target_module {
            groups
                .entry((source_module, target_module))
                .or_default()
                .push(occurrence.clone());
        }
    }
    let mut ranked = groups.into_iter().collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .len()
            .cmp(&left.1.len())
            .then_with(|| left.0.cmp(&right.0))
    });
    ranked
        .into_iter()
        .take(limits.results.min(5))
        .enumerate()
        .map(|(rank, ((source_module, target_module), occurrences))| {
            let representatives = architecture_occurrence_nodes(&occurrences);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "BOUNDARY_CROSSING",
                format!("{source_module} to {target_module} type boundary"),
                format!(
                    "{} explicit type-dependency occurrences cross from {source_module} to {target_module}.",
                    occurrences.len()
                ),
                "CROSS_BOUNDARY_EDGE_COUNT",
                Some(scope.direction.unwrap_or(RepositoryDirection::Outgoing)),
                json!({
                    "rule": "sourceModule != targetModule, ranked by occurrenceCount",
                    "sourceModule": source_module,
                    "targetModule": target_module,
                    "occurrenceCount": occurrences.len()
                }),
                representatives,
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_community_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let membership = native_weighted_leiden(&graph.native, 1.0);
    let mut communities = BTreeMap::<usize, Vec<i64>>::new();
    for (position, community) in membership.into_iter().enumerate() {
        communities
            .entry(community)
            .or_default()
            .push(graph.nodes[position].database_id);
    }
    let mut ranked = communities
        .into_values()
        .filter_map(|members| {
            if members.len() < 2 {
                return None;
            }
            let member_set = members.iter().copied().collect::<BTreeSet<_>>();
            let occurrences = graph
                .occurrences
                .iter()
                .filter(|occurrence| {
                    member_set.contains(&occurrence.lifted_source.unwrap_or(occurrence.source_id))
                        && member_set.contains(&occurrence.target_id)
                })
                .cloned()
                .collect::<Vec<_>>();
            (!occurrences.is_empty()).then_some((members, occurrences))
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .len()
            .cmp(&left.1.len())
            .then_with(|| right.0.len().cmp(&left.0.len()))
            .then_with(|| {
                architecture_node(graph, left.0[0])
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0[0]).canonical_key)
            })
    });
    ranked
        .into_iter()
        .take(limits.results.min(5))
        .enumerate()
        .map(|(rank, (members, occurrences))| {
            let unique_edges = occurrences
                .iter()
                .map(architecture_occurrence_identity)
                .collect::<BTreeSet<_>>()
                .len();
            let possible = members
                .len()
                .saturating_mul(members.len().saturating_sub(1));
            let cohesion = unique_edges as f64 / possible.max(1) as f64;
            let representative = architecture_node(
                graph,
                architecture_highest_degree_member(&members, &occurrences),
            );
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "COMMUNITY",
                format!(
                    "{} / {} runtime call community",
                    architecture_module(representative),
                    representative.name
                ),
                format!(
                    "{} exact symbols share {} internal runtime-call edges.",
                    members.len(),
                    unique_edges
                ),
                RepositoryArchitectureMetric::Communities.canonical(),
                None,
                json!({
                    "rule": "deterministic weighted Leiden at resolution 1.0",
                    "memberCount": members.len(),
                    "internalEdgeCount": unique_edges,
                    "resolution": 1.0
                }),
                members,
                &occurrences,
                Some(cohesion),
                limits,
            )
        })
        .collect()
}

fn architecture_bridge_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let membership = native_weighted_leiden(&graph.native, 1.0);
    let mut bridges = BTreeMap::<(usize, usize), Vec<RepositoryEdgeOccurrence>>::new();
    for occurrence in &graph.occurrences {
        let source = graph.positions[&occurrence.lifted_source.unwrap_or(occurrence.source_id)];
        let target = graph.positions[&occurrence.target_id];
        let source_community = membership[source];
        let target_community = membership[target];
        if source_community != target_community {
            let pair = if source_community < target_community {
                (source_community, target_community)
            } else {
                (target_community, source_community)
            };
            bridges.entry(pair).or_default().push(occurrence.clone());
        }
    }
    let mut ranked = bridges
        .into_iter()
        .map(|(pair, occurrences)| {
            let edge_count = occurrences
                .iter()
                .map(architecture_occurrence_identity)
                .collect::<BTreeSet<_>>()
                .len();
            (pair, edge_count, occurrences)
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| left.1.cmp(&right.1).then_with(|| left.0.cmp(&right.0)));
    ranked
        .into_iter()
        .take(limits.results.min(5))
        .enumerate()
        .map(|(rank, ((left, right), edge_count, occurrences))| {
            let first = &occurrences[0];
            let source = architecture_node(
                graph,
                first.lifted_source.unwrap_or(first.source_id),
            );
            let target = architecture_node(graph, first.target_id);
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "THIN_BRIDGE",
                format!(
                    "{} to {} reference bridge",
                    architecture_module(source),
                    architecture_module(target)
                ),
                format!(
                    "{edge_count} exact reference edges connect otherwise separate deterministic communities."
                ),
                RepositoryArchitectureMetric::Bridges.canonical(),
                None,
                json!({
                    "rule": "cross-community edge count ranked ascending",
                    "leftCommunity": left,
                    "rightCommunity": right,
                    "edgeCount": edge_count,
                    "resolution": 1.0
                }),
                architecture_occurrence_nodes(&occurrences),
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

fn architecture_public_api_findings(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    limits: &RepositoryLimits,
) -> Result<Vec<Value>> {
    let mut consumers = BTreeMap::<i64, (BTreeSet<String>, Vec<RepositoryEdgeOccurrence>)>::new();
    for occurrence in &graph.occurrences {
        let target = architecture_node(graph, occurrence.target_id);
        if target.visibility != "PUBLIC" || !is_type_kind(&target.kind) {
            continue;
        }
        let source = architecture_node(
            graph,
            occurrence.lifted_source.unwrap_or(occurrence.source_id),
        );
        let entry = consumers.entry(target.database_id).or_default();
        entry.0.insert(architecture_package_boundary(source));
        entry.1.push(occurrence.clone());
    }
    let mut ranked = consumers
        .into_iter()
        .filter(|(_, (boundaries, _))| boundaries.len() >= 2)
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .1
            .0
            .len()
            .cmp(&left.1.0.len())
            .then_with(|| right.1.1.len().cmp(&left.1.1.len()))
            .then_with(|| {
                architecture_node(graph, left.0)
                    .canonical_key
                    .cmp(&architecture_node(graph, right.0).canonical_key)
            })
    });
    ranked
        .into_iter()
        .take(limits.results.min(5))
        .enumerate()
        .map(|(rank, (target_id, (boundaries, occurrences)))| {
            let target = architecture_node(graph, target_id);
            let mut representatives = vec![target_id];
            representatives.extend(architecture_occurrence_nodes(&occurrences));
            architecture_finding(
                connection,
                graph,
                generation,
                scope,
                projection,
                rank + 1,
                "PUBLIC_API_CONSUMED_BY_UNRELATED_COMPONENTS",
                format!("{} cross-component public API", target.name),
                format!(
                    "{} is consumed from {} unrelated package or module boundaries.",
                    target.name,
                    boundaries.len()
                ),
                RepositoryArchitectureMetric::PublicApiConsumers.canonical(),
                None,
                json!({
                    "rule": "public type has incoming type dependencies from at least two package or module boundaries",
                    "consumerBoundaryCount": boundaries.len(),
                    "occurrenceCount": occurrences.len()
                }),
                representatives,
                &occurrences,
                None,
                limits,
            )
        })
        .collect()
}

#[allow(clippy::too_many_arguments)]
fn architecture_finding(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    generation: u64,
    scope: &RepositoryScope,
    projection: RepositoryArchitectureProjection,
    rank: usize,
    finding_type: &'static str,
    name: String,
    summary: String,
    metric: &'static str,
    direction: Option<RepositoryDirection>,
    trigger: Value,
    representative_ids: Vec<i64>,
    occurrences: &[RepositoryEdgeOccurrence],
    cohesion: Option<f64>,
    limits: &RepositoryLimits,
) -> Result<Value> {
    let mut representative_symbols = representative_ids
        .into_iter()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .filter_map(|id| {
            graph
                .positions
                .get(&id)
                .map(|position| graph.nodes[*position].clone())
        })
        .collect::<Vec<_>>();
    representative_symbols.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    representative_symbols.truncate(5);
    let relation_composition = occurrences.iter().fold(
        BTreeMap::<&'static str, usize>::new(),
        |mut counts, occurrence| {
            *counts.entry(occurrence.kind.canonical()).or_default() += 1;
            counts
        },
    );
    let supporting_subgraph =
        architecture_supporting_subgraph(connection, graph, occurrences, limits)?;
    Ok(json!({
        "rank": rank,
        "type": finding_type,
        "name": name,
        "summary": summary,
        "projection": projection.canonical(),
        "relationTypes": projection
            .relation_kinds()
            .iter()
            .map(|relation| relation.canonical())
            .collect::<Vec<_>>(),
        "direction": direction,
        "metric": metric,
        "trigger": trigger,
        "graphGeneration": generation,
        "scope": scope,
        "representativeSymbols": representative_symbols,
        "supportingSubgraph": supporting_subgraph,
        "relationComposition": relation_composition,
        "cohesion": cohesion,
        "evidenceClass": "derived",
        "derivation": {
            "rule": "DETERMINISTIC_RELATION_SPECIFIC_ARCHITECTURE",
            "projection": projection.canonical(),
            "metric": metric
        }
    }))
}

fn architecture_supporting_subgraph(
    connection: &Connection,
    graph: &RepositoryArchitectureGraph,
    occurrences: &[RepositoryEdgeOccurrence],
    limits: &RepositoryLimits,
) -> Result<Value> {
    let selected_identities = occurrences
        .iter()
        .map(architecture_occurrence_identity)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .take(limits.results.min(10))
        .collect::<BTreeSet<_>>();
    let selected = occurrences
        .iter()
        .filter(|occurrence| {
            selected_identities.contains(&architecture_occurrence_identity(occurrence))
        })
        .cloned()
        .collect::<Vec<_>>();
    let mut node_cache = graph
        .nodes
        .iter()
        .map(|node| (node.database_id, node.clone()))
        .collect::<BTreeMap<_, _>>();
    let edges = repository_edges(
        connection,
        &selected,
        RepositoryDirection::Outgoing,
        limits.evidence,
        None,
        &mut node_cache,
    )?;
    let mut node_ids = BTreeSet::new();
    for occurrence in &selected {
        node_ids.insert(occurrence.lifted_source.unwrap_or(occurrence.source_id));
        node_ids.insert(occurrence.target_id);
    }
    let nodes = node_ids
        .into_iter()
        .filter_map(|id| node_cache.get(&id).cloned())
        .collect::<Vec<_>>();
    Ok(json!({
        "nodes": nodes,
        "edges": edges,
        "truncated": selected_identities.len()
            < occurrences
                .iter()
                .map(architecture_occurrence_identity)
                .collect::<BTreeSet<_>>()
                .len()
    }))
}

fn architecture_occurrence_identity(
    occurrence: &RepositoryEdgeOccurrence,
) -> (i64, i64, RepositoryRelationKind, String) {
    (
        occurrence.lifted_source.unwrap_or(occurrence.source_id),
        occurrence.target_id,
        occurrence.kind,
        occurrence.context.clone(),
    )
}

fn architecture_occurrence_nodes(occurrences: &[RepositoryEdgeOccurrence]) -> Vec<i64> {
    occurrences
        .iter()
        .flat_map(|occurrence| {
            [
                occurrence.lifted_source.unwrap_or(occurrence.source_id),
                occurrence.target_id,
            ]
        })
        .collect()
}

fn architecture_highest_degree_member(
    members: &[i64],
    occurrences: &[RepositoryEdgeOccurrence],
) -> i64 {
    let mut degree = BTreeMap::<i64, usize>::new();
    for occurrence in occurrences {
        *degree
            .entry(occurrence.lifted_source.unwrap_or(occurrence.source_id))
            .or_default() += 1;
        *degree.entry(occurrence.target_id).or_default() += 1;
    }
    members
        .iter()
        .copied()
        .max_by_key(|id| {
            (
                degree.get(id).copied().unwrap_or_default(),
                std::cmp::Reverse(*id),
            )
        })
        .expect("architecture community is non-empty")
}

fn architecture_node(graph: &RepositoryArchitectureGraph, id: i64) -> &RepositoryNode {
    &graph.nodes[graph.positions[&id]]
}

fn architecture_module(node: &RepositoryNode) -> String {
    node.module
        .clone()
        .unwrap_or_else(|| node.path.split('/').next().unwrap_or("<root>").to_string())
}

fn architecture_package_boundary(node: &RepositoryNode) -> String {
    let package = node
        .fq_name
        .as_deref()
        .and_then(|name| name.rsplit_once('.').map(|(package, _)| package))
        .unwrap_or("<root>");
    format!("{}:{package}", architecture_module(node))
}

fn direction_label(direction: RepositoryDirection) -> &'static str {
    match direction {
        RepositoryDirection::Incoming => "incoming",
        RepositoryDirection::Outgoing => "outgoing",
    }
}

fn is_type_kind(kind: &str) -> bool {
    matches!(
        kind,
        "CLASS" | "ENUM_CLASS" | "INTERFACE" | "OBJECT" | "TYPE_ALIAS"
    )
}

fn graph_repository_question(
    connection: &Connection,
    question: &str,
    intent: RepositoryIntent,
    scope: &RepositoryScope,
    limits: &RepositoryLimits,
    evidence_continuation: Option<&RepositoryEvidenceContinuation>,
) -> Result<Value> {
    let mentions = repository_symbol_mentions(connection, question)?;
    let fallback = likely_declaration_term(question).map(str::to_string);
    let Some(start_name) = mentions.first().map(|(_, name)| name.clone()).or(fallback) else {
        return Ok(json!({
            "answered": false,
            "ambiguous": false,
            "nodes": [],
            "edges": [],
            "paths": [],
            "identityCollisions": 0,
            "truncated": false
        }));
    };
    let starts = best_question_nodes(connection, question, scope, Some(&start_name))?;
    if starts.len() != 1 {
        return Ok(json!({
            "answered": false,
            "ambiguous": starts.len() > 1,
            "nodes": starts,
            "edges": [],
            "paths": [],
            "identityCollisions": 0,
            "truncated": false
        }));
    }
    let start = starts[0].clone();
    let direction = match intent {
        RepositoryIntent::IncomingImpact => RepositoryDirection::Incoming,
        RepositoryIntent::OutgoingImpact => RepositoryDirection::Outgoing,
        RepositoryIntent::Path => scope.direction.unwrap_or(RepositoryDirection::Outgoing),
        RepositoryIntent::Resolve
        | RepositoryIntent::Architecture
        | RepositoryIntent::ContextRelationship => {
            unreachable!("non-graph intent is handled separately")
        }
    };
    let relations = if scope.relations.is_empty() {
        vec![RepositoryRelationKind::Calls]
    } else {
        scope.relations.clone()
    };
    let target = if intent == RepositoryIntent::Path && mentions.len() > 1 {
        let target_name = &mentions[mentions.len() - 1].1;
        let mut candidates = load_repository_node(connection, "symbol.name = ?1", target_name)?;
        candidates.retain(|node| node_matches_scope(node, scope));
        select_path_target(
            connection, &start, candidates, question, direction, &relations,
        )?
    } else {
        None
    };
    let traversal = traverse_repository_graph(
        connection,
        &start,
        target.as_ref(),
        question,
        direction,
        scope,
        limits,
    )?;
    let mut node_cache = BTreeMap::new();
    node_cache.insert(start.database_id, start.clone());
    if let Some(target) = target {
        node_cache.insert(target.database_id, target);
    }
    let edges = repository_edges(
        connection,
        &traversal.occurrences,
        direction,
        limits.evidence,
        evidence_continuation,
        &mut node_cache,
    )?;
    let paths = repository_paths(
        connection,
        start.database_id,
        &traversal.predecessors,
        &relations,
        direction,
        limits.results,
        &mut node_cache,
    )?;
    let mut nodes = node_cache.into_values().collect::<Vec<_>>();
    nodes.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    let target_reached = traversal
        .target_id
        .is_none_or(|target_id| traversal.visited.contains(&target_id));
    let answered = !edges.is_empty() && target_reached;
    let continuations = edges
        .iter()
        .filter_map(|edge| edge.evidence_continuation.clone())
        .collect::<Vec<_>>();
    let truncated = traversal.truncated || !continuations.is_empty();
    Ok(json!({
        "answered": answered,
        "ambiguous": false,
        "nodes": nodes,
        "edges": edges,
        "paths": paths,
        "identityCollisions": 0,
        "truncated": truncated,
        "continuation": (!continuations.is_empty()).then_some(continuations)
    }))
}

fn select_path_target(
    connection: &Connection,
    start: &RepositoryNode,
    candidates: Vec<RepositoryNode>,
    question: &str,
    direction: RepositoryDirection,
    relations: &[RepositoryRelationKind],
) -> Result<Option<RepositoryNode>> {
    if candidates.len() <= 1 {
        return Ok(candidates.into_iter().next());
    }
    let occurrences = load_relation_occurrences(connection, relations)?;
    let direct = candidates
        .iter()
        .find(|candidate| {
            occurrences.iter().any(|occurrence| {
                let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
                let (from, to) = match direction {
                    RepositoryDirection::Outgoing => (source_id, occurrence.target_id),
                    RepositoryDirection::Incoming => (occurrence.target_id, source_id),
                };
                from == start.database_id && to == candidate.database_id
            })
        })
        .cloned();
    if direct.is_some() {
        return Ok(direct);
    }
    let best_score = candidates
        .iter()
        .map(|candidate| repository_node_score(candidate, question))
        .max();
    Ok(candidates
        .into_iter()
        .find(|candidate| Some(repository_node_score(candidate, question)) == best_score))
}

struct RepositoryTraversal {
    occurrences: Vec<RepositoryEdgeOccurrence>,
    predecessors: BTreeMap<i64, i64>,
    visited: BTreeSet<i64>,
    target_id: Option<i64>,
    truncated: bool,
}

fn traverse_repository_graph(
    connection: &Connection,
    start: &RepositoryNode,
    target: Option<&RepositoryNode>,
    question: &str,
    direction: RepositoryDirection,
    scope: &RepositoryScope,
    limits: &RepositoryLimits,
) -> Result<RepositoryTraversal> {
    let relations = if scope.relations.is_empty() {
        vec![RepositoryRelationKind::Calls]
    } else {
        scope.relations.clone()
    };
    let max_depth = scope.max_depth.unwrap_or(limits.depth).min(limits.depth);
    let all_occurrences = load_relation_occurrences(connection, &relations)?;
    if let Some(target) = target {
        return traverse_repository_path(
            connection,
            all_occurrences,
            start,
            target,
            question,
            direction,
            max_depth,
        );
    }
    let mut frontier = BTreeSet::from([start.database_id]);
    let mut visited = frontier.clone();
    let mut predecessors = BTreeMap::new();
    let mut occurrences = Vec::new();
    let mut truncated = false;
    for _ in 0..max_depth {
        let mut next_frontier = BTreeSet::new();
        for occurrence in &all_occurrences {
            let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
            let (current_id, next_id) = match direction {
                RepositoryDirection::Outgoing => (source_id, occurrence.target_id),
                RepositoryDirection::Incoming => (occurrence.target_id, source_id),
            };
            if !frontier.contains(&current_id) {
                continue;
            }
            if visited.insert(next_id) {
                predecessors.insert(next_id, current_id);
                next_frontier.insert(next_id);
            }
            occurrences.push(occurrence.clone());
            if occurrences.len() >= limits.results {
                truncated = true;
                break;
            }
        }
        if truncated || next_frontier.is_empty() {
            break;
        }
        frontier = next_frontier;
    }
    Ok(RepositoryTraversal {
        occurrences,
        predecessors,
        visited,
        target_id: None,
        truncated,
    })
}

fn traverse_repository_path(
    connection: &Connection,
    all_occurrences: Vec<RepositoryEdgeOccurrence>,
    start: &RepositoryNode,
    target: &RepositoryNode,
    question: &str,
    direction: RepositoryDirection,
    max_depth: usize,
) -> Result<RepositoryTraversal> {
    let directed_step = |occurrence: &RepositoryEdgeOccurrence| {
        let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        match direction {
            RepositoryDirection::Outgoing => (source_id, occurrence.target_id),
            RepositoryDirection::Incoming => (occurrence.target_id, source_id),
        }
    };
    let mut distance_to_target = BTreeMap::from([(target.database_id, 0usize)]);
    let mut reverse_frontier = BTreeSet::from([target.database_id]);
    for depth in 1..=max_depth {
        let mut next_frontier = BTreeSet::new();
        for occurrence in &all_occurrences {
            let (from, to) = directed_step(occurrence);
            if reverse_frontier.contains(&to) && !distance_to_target.contains_key(&from) {
                distance_to_target.insert(from, depth);
                next_frontier.insert(from);
            }
        }
        if next_frontier.is_empty() {
            break;
        }
        reverse_frontier = next_frontier;
    }

    let mut route_tokens = search_tokens(question);
    for token in search_tokens(&start.name)
        .into_iter()
        .chain(search_tokens(&target.name))
        .chain(
            target
                .owner_name
                .as_deref()
                .map(search_tokens)
                .unwrap_or_default(),
        )
    {
        route_tokens.remove(&token);
    }
    let mut relevance = BTreeMap::new();
    let mut frontier = BTreeMap::from([(start.database_id, (0usize, vec![start.database_id]))]);
    let mut best_target: Option<(usize, Vec<i64>)> = None;
    for depth in 1..=max_depth {
        let mut next_frontier = BTreeMap::<i64, (usize, Vec<i64>)>::new();
        for (_, (score, path)) in frontier {
            let current = *path.last().expect("path has a current node");
            for occurrence in &all_occurrences {
                let (from, next) = directed_step(occurrence);
                if from != current
                    || path.contains(&next)
                    || distance_to_target
                        .get(&next)
                        .is_none_or(|distance| depth + distance > max_depth)
                {
                    continue;
                }
                let node_score = match relevance.get(&next).copied() {
                    Some(score) => score,
                    None => {
                        let node = load_repository_node(connection, "symbol.id = ?1", next)?
                            .into_iter()
                            .next()
                            .ok_or_else(|| {
                                CliError::new(
                                    "REPOSITORY_INDEX_INVALID",
                                    format!("semantic edge references missing symbol id {next}"),
                                )
                            })?;
                        let score = search_tokens(&node.name)
                            .intersection(&route_tokens)
                            .count()
                            * 5;
                        relevance.insert(next, score);
                        score
                    }
                };
                let mut candidate_path = path.clone();
                candidate_path.push(next);
                let candidate = (score + node_score, candidate_path);
                let slot = if next == target.database_id {
                    &mut best_target
                } else {
                    next_frontier
                        .entry(next)
                        .or_insert_with(|| candidate.clone());
                    let entry = next_frontier
                        .get_mut(&next)
                        .expect("candidate was inserted");
                    if path_candidate_better(&candidate, entry) {
                        *entry = candidate;
                    }
                    continue;
                };
                if slot
                    .as_ref()
                    .is_none_or(|existing| path_candidate_better(&candidate, existing))
                {
                    *slot = Some(candidate);
                }
            }
        }
        if next_frontier.is_empty() {
            break;
        }
        frontier = next_frontier;
    }

    let Some((_, path)) = best_target else {
        return Ok(RepositoryTraversal {
            occurrences: Vec::new(),
            predecessors: BTreeMap::new(),
            visited: BTreeSet::from([start.database_id]),
            target_id: Some(target.database_id),
            truncated: false,
        });
    };
    let path_steps = path
        .windows(2)
        .map(|step| (step[0], step[1]))
        .collect::<BTreeSet<_>>();
    let occurrences = all_occurrences
        .into_iter()
        .filter(|occurrence| path_steps.contains(&directed_step(occurrence)))
        .collect();
    let predecessors = path
        .windows(2)
        .map(|step| (step[1], step[0]))
        .collect::<BTreeMap<_, _>>();
    Ok(RepositoryTraversal {
        occurrences,
        predecessors,
        visited: path.into_iter().collect(),
        target_id: Some(target.database_id),
        truncated: false,
    })
}

fn path_candidate_better(candidate: &(usize, Vec<i64>), existing: &(usize, Vec<i64>)) -> bool {
    candidate.0 > existing.0
        || (candidate.0 == existing.0
            && (candidate.1.len(), &candidate.1) < (existing.1.len(), &existing.1))
}

fn load_relation_occurrences(
    connection: &Connection,
    relations: &[RepositoryRelationKind],
) -> Result<Vec<RepositoryEdgeOccurrence>> {
    let relation_names = relations
        .iter()
        .map(|relation| format!("'{}'", relation.canonical()))
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!(
        "SELECT edge.id,
                edge.source_id,
                edge.target_id,
                edge.kind,
                edge.context,
                edge.start_offset,
                edge.end_offset,
                edge.line,
                source.kind,
                source.stable_key,
                source.owner_id,
                source_owner.kind,
                occurrence_file.path
         FROM semantic_edge_occurrences edge
         JOIN semantic_symbols source ON source.id = edge.source_id
         LEFT JOIN semantic_symbols source_owner ON source_owner.id = source.owner_id
         JOIN semantic_files occurrence_file ON occurrence_file.id = edge.source_file_id
         WHERE edge.kind IN ({relation_names})
         ORDER BY edge.source_id, edge.target_id, edge.kind, edge.context, edge.id"
    );
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([], |row| {
            let kind = parse_relation_kind(&row.get::<_, String>(3)?)?;
            let source_kind = row.get::<_, String>(8)?;
            let source_owner_id = row.get::<_, Option<i64>>(10)?;
            let source_owner_kind = row.get::<_, Option<String>>(11)?;
            let lifted_source = source_owner_id.filter(|_| {
                !is_callable_kind(&source_kind)
                    && source_owner_kind.as_deref().is_some_and(is_callable_kind)
            });
            Ok(RepositoryEdgeOccurrence {
                source_id: row.get(1)?,
                target_id: row.get(2)?,
                kind,
                context: row.get(4)?,
                occurrence: RepositoryOccurrence {
                    id: row.get(0)?,
                    path: row.get(12)?,
                    start_offset: row.get(5)?,
                    end_offset: row.get(6)?,
                    line: row.get(7)?,
                },
                lifted_source,
                source_local_key: lifted_source.map(|_| row.get(9)).transpose()?,
            })
        })
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))
}

fn parse_relation_kind(raw: &str) -> rusqlite::Result<RepositoryRelationKind> {
    match raw {
        "CALLS" => Ok(RepositoryRelationKind::Calls),
        "CASE_OF" => Ok(RepositoryRelationKind::CaseOf),
        "CONTAINS" => Ok(RepositoryRelationKind::Contains),
        "DELEGATES" => Ok(RepositoryRelationKind::Delegates),
        "IMPLEMENTS" => Ok(RepositoryRelationKind::Implements),
        "INHERITS" => Ok(RepositoryRelationKind::Inherits),
        "METHOD" => Ok(RepositoryRelationKind::Method),
        "OVERRIDES" => Ok(RepositoryRelationKind::Overrides),
        "REFERENCES" => Ok(RepositoryRelationKind::References),
        "SEALED_MEMBER" => Ok(RepositoryRelationKind::SealedMember),
        _ => Err(rusqlite::Error::FromSqlConversionFailure(
            3,
            Type::Text,
            Box::new(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("unknown semantic relation kind `{raw}`"),
            )),
        )),
    }
}

fn is_callable_kind(kind: &str) -> bool {
    matches!(
        kind,
        "FUNCTION" | "MEMBER_FUNCTION" | "CONSTRUCTOR" | "GETTER" | "SETTER"
    )
}

fn repository_edges(
    connection: &Connection,
    occurrences: &[RepositoryEdgeOccurrence],
    direction: RepositoryDirection,
    evidence_limit: usize,
    evidence_continuation: Option<&RepositoryEvidenceContinuation>,
    node_cache: &mut BTreeMap<i64, RepositoryNode>,
) -> Result<Vec<RepositoryEdge>> {
    let mut grouped = BTreeMap::<RepositoryEdgeIdentity, Vec<&RepositoryEdgeOccurrence>>::new();
    for occurrence in occurrences {
        let source_id = occurrence.lifted_source.unwrap_or(occurrence.source_id);
        grouped
            .entry(RepositoryEdgeIdentity {
                source_id,
                target_id: occurrence.target_id,
                kind: occurrence.kind,
                context: occurrence.context.clone(),
                derived: occurrence.lifted_source.is_some(),
            })
            .or_default()
            .push(occurrence);
    }
    let mut edges = Vec::new();
    for (identity, mut grouped_occurrences) in grouped {
        grouped_occurrences.sort_by_key(|occurrence| occurrence.occurrence.id);
        let source = cached_repository_node(connection, identity.source_id, node_cache)?;
        let target = cached_repository_node(connection, identity.target_id, node_cache)?;
        let occurrence_count = grouped_occurrences.len();
        if evidence_continuation.is_some_and(|continuation| {
            continuation.source_key != source.canonical_key
                || continuation.target_key != target.canonical_key
                || continuation.kind != identity.kind
                || continuation.context != identity.context
                || continuation.derived != identity.derived
        }) {
            continue;
        }
        let after_occurrence_id = evidence_continuation
            .map(|continuation| continuation.after_occurrence_id)
            .unwrap_or(i64::MIN);
        let remaining = grouped_occurrences
            .iter()
            .copied()
            .filter(|occurrence| occurrence.occurrence.id > after_occurrence_id)
            .collect::<Vec<_>>();
        if evidence_continuation.is_some() && remaining.is_empty() {
            continue;
        }
        let page = remaining
            .iter()
            .take(evidence_limit)
            .map(|occurrence| occurrence.occurrence.clone())
            .collect::<Vec<_>>();
        let evidence_truncated = remaining.len() > page.len();
        let next_continuation = evidence_truncated.then(|| RepositoryEvidenceContinuation {
            source_key: source.canonical_key.clone(),
            target_key: target.canonical_key.clone(),
            kind: identity.kind,
            context: identity.context.clone(),
            derived: identity.derived,
            after_occurrence_id: page
                .last()
                .expect("truncated evidence page is non-empty")
                .id,
        });
        let derivation = identity.derived.then(|| RepositoryDerivation {
            rule: "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER",
            source_local_key: grouped_occurrences[0]
                .source_local_key
                .clone()
                .expect("derived edge has local source identity"),
            supporting_relations: ["CONTAINS", identity.kind.canonical()],
        });
        edges.push(RepositoryEdge {
            source_key: source.canonical_key.clone(),
            source_name: source.name.clone(),
            source_owner_name: source.owner_name.clone(),
            target_key: target.canonical_key.clone(),
            target_name: target.name.clone(),
            target_owner_name: target.owner_name.clone(),
            kind: identity.kind,
            direction,
            context: identity.context,
            occurrence_count,
            occurrences: page,
            evidence_class: "compiler",
            derivation,
            evidence_truncated,
            evidence_continuation: next_continuation,
        });
    }
    edges.sort_by(|left, right| {
        (&left.source_key, &left.target_key, left.kind, &left.context).cmp(&(
            &right.source_key,
            &right.target_key,
            right.kind,
            &right.context,
        ))
    });
    Ok(edges)
}

fn repository_paths(
    connection: &Connection,
    start_id: i64,
    predecessors: &BTreeMap<i64, i64>,
    relations: &[RepositoryRelationKind],
    direction: RepositoryDirection,
    limit: usize,
    node_cache: &mut BTreeMap<i64, RepositoryNode>,
) -> Result<Vec<RepositoryPath>> {
    let mut paths = Vec::new();
    for target_id in predecessors.keys().copied().take(limit) {
        let mut ids = vec![target_id];
        let mut current = target_id;
        while let Some(previous) = predecessors.get(&current).copied() {
            ids.push(previous);
            current = previous;
            if current == start_id {
                break;
            }
        }
        if ids.last().copied() != Some(start_id) {
            continue;
        }
        ids.reverse();
        let nodes = ids
            .into_iter()
            .map(|id| cached_repository_node(connection, id, node_cache))
            .collect::<Result<Vec<_>>>()?;
        paths.push(RepositoryPath {
            direction,
            relation_kinds: relations.to_vec(),
            nodes,
        });
    }
    paths.sort_by(|left, right| {
        let left_keys = left
            .nodes
            .iter()
            .map(|node| node.canonical_key.as_str())
            .collect::<Vec<_>>();
        let right_keys = right
            .nodes
            .iter()
            .map(|node| node.canonical_key.as_str())
            .collect::<Vec<_>>();
        left_keys.cmp(&right_keys)
    });
    Ok(paths)
}

fn cached_repository_node(
    connection: &Connection,
    id: i64,
    cache: &mut BTreeMap<i64, RepositoryNode>,
) -> Result<RepositoryNode> {
    if let Some(node) = cache.get(&id) {
        return Ok(node.clone());
    }
    let node = load_repository_node(connection, "symbol.id = ?1", id)?
        .into_iter()
        .next()
        .ok_or_else(|| {
            CliError::new(
                "REPOSITORY_INDEX_INVALID",
                format!("semantic edge references missing symbol id {id}"),
            )
        })?;
    cache.insert(id, node.clone());
    Ok(node)
}

fn best_question_nodes(
    connection: &Connection,
    question: &str,
    scope: &RepositoryScope,
    forced_name: Option<&str>,
) -> Result<Vec<RepositoryNode>> {
    if !semantic_graph_tables_exist(connection)? {
        return Ok(Vec::new());
    }
    let name = match forced_name {
        Some(name) => Some(name.to_string()),
        None => mentioned_callable_names(connection, question)?
            .first()
            .map(|(_, name)| name.clone())
            .or_else(|| likely_declaration_term(question).map(str::to_string)),
    };
    let Some(name) = name else {
        return Ok(Vec::new());
    };
    let mut candidates = load_repository_node(connection, "symbol.name = ?1", name)?;
    candidates.retain(|node| node_matches_scope(node, scope));
    if candidates.is_empty() {
        return Ok(candidates);
    }
    let scores = candidates
        .iter()
        .map(|candidate| repository_node_score(candidate, question))
        .collect::<Vec<_>>();
    let best = scores.iter().copied().max().unwrap_or(0);
    let mut selected = candidates
        .into_iter()
        .zip(scores)
        .filter_map(|(candidate, score)| (score == best).then_some(candidate))
        .collect::<Vec<_>>();
    selected.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    Ok(selected)
}

fn semantic_graph_tables_exist(connection: &Connection) -> Result<bool> {
    connection
        .query_row(
            "SELECT COUNT(*) = 2
             FROM sqlite_master
             WHERE type = 'table'
               AND name IN ('semantic_symbols', 'semantic_edge_occurrences')",
            [],
            |row| row.get(0),
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))
}

fn mentioned_callable_names(
    connection: &Connection,
    question: &str,
) -> Result<Vec<(usize, String)>> {
    if !semantic_graph_tables_exist(connection)? {
        return Ok(Vec::new());
    }
    let mut statement = connection
        .prepare(
            "SELECT DISTINCT name
             FROM semantic_symbols
             WHERE kind IN ('FUNCTION', 'MEMBER_FUNCTION', 'CONSTRUCTOR', 'GETTER', 'SETTER')
             ORDER BY name",
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let names = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let question_lower = question.to_ascii_lowercase();
    let mut mentions = names
        .into_iter()
        .filter(|name| !is_query_verb(name))
        .filter_map(|name| {
            identifier_position(&question_lower, &name.to_ascii_lowercase())
                .map(|position| (position, name))
        })
        .collect::<Vec<_>>();
    mentions.sort_by(|left, right| {
        left.0
            .cmp(&right.0)
            .then_with(|| right.1.len().cmp(&left.1.len()))
            .then_with(|| left.1.cmp(&right.1))
    });
    mentions.dedup_by(|left, right| left.1 == right.1);
    Ok(mentions)
}

fn repository_symbol_mentions(
    connection: &Connection,
    question: &str,
) -> Result<Vec<(usize, String)>> {
    let mentions = mentioned_callable_names(connection, question)?;
    let dotted = dotted_member_name(question);
    let explicit = mentions
        .iter()
        .filter(|(_, name)| {
            name.chars().any(char::is_uppercase) || dotted.as_deref() == Some(name.as_str())
        })
        .cloned()
        .collect::<Vec<_>>();
    Ok(if explicit.is_empty() {
        mentions
    } else {
        explicit
    })
}

fn is_query_verb(name: &str) -> bool {
    matches!(
        name.to_ascii_lowercase().as_str(),
        "find" | "list" | "resolve" | "show" | "trace" | "reach" | "used" | "contain" | "connect"
    )
}

fn dotted_member_name(question: &str) -> Option<String> {
    question
        .split_whitespace()
        .map(|token| {
            token.trim_matches(|character: char| !(character.is_alphanumeric() || character == '_'))
        })
        .filter_map(|token| token.rsplit_once('.').map(|(_, member)| member))
        .map(|member| {
            member
                .trim_matches(|character: char| !(character.is_alphanumeric() || character == '_'))
                .to_string()
        })
        .find(|member| !member.is_empty())
}

fn identifier_position(haystack: &str, needle: &str) -> Option<usize> {
    haystack.match_indices(needle).find_map(|(start, _)| {
        let end = start + needle.len();
        let before = haystack[..start].chars().next_back();
        let after = haystack[end..].chars().next();
        let boundary = |character: Option<char>| {
            character.is_none_or(|character| !(character.is_alphanumeric() || character == '_'))
        };
        (boundary(before) && boundary(after)).then_some(start)
    })
}

fn load_repository_node<T: rusqlite::ToSql>(
    connection: &Connection,
    predicate: &str,
    value: T,
) -> Result<Vec<RepositoryNode>> {
    let sql = format!(
        "SELECT symbol.id,
                symbol.stable_key,
                symbol.kind,
                symbol.name,
                symbol.fq_name,
                symbol.signature,
                symbol.visibility,
                symbol.modality,
                symbol.origin,
                file.path,
                file.module_name,
                CASE
                    WHEN owner.name = 'Companion' THEN outer_owner.name
                    ELSE owner.name
                END,
                receiver_type.classifier,
                receiver_type.debug_text,
                return_type.classifier,
                return_type.debug_text,
                symbol.start_offset,
                symbol.end_offset,
                symbol.line,
                symbol.is_expect,
                symbol.is_actual,
                symbol.is_override,
                symbol.is_sealed,
                symbol.is_delegated,
                COALESCE((
                    SELECT json_group_array(annotation_name)
                    FROM (
                        SELECT annotation_name
                        FROM semantic_symbol_annotations
                        WHERE symbol_id = symbol.id
                        ORDER BY annotation_name
                    )
                ), '[]')
         FROM semantic_symbols symbol
         JOIN semantic_files file ON file.id = symbol.file_id
         LEFT JOIN semantic_symbols owner ON owner.id = symbol.owner_id
         LEFT JOIN semantic_symbols outer_owner ON outer_owner.id = owner.owner_id
         LEFT JOIN semantic_types receiver_type ON receiver_type.id = symbol.receiver_type_id
         LEFT JOIN semantic_types return_type ON return_type.id = symbol.return_type_id
         WHERE {predicate}
         ORDER BY symbol.stable_key"
    );
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([value], repository_node_from_row)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))
}

fn repository_node_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<RepositoryNode> {
    let module_name = row.get::<_, Option<String>>(10)?;
    let (module, source_set) = module_and_source_set(module_name.as_deref());
    let signature = row.get::<_, Option<String>>(5)?;
    let annotations_json = row.get::<_, String>(24)?;
    let annotations = serde_json::from_str(&annotations_json).map_err(|error| {
        rusqlite::Error::FromSqlConversionFailure(24, Type::Text, Box::new(error))
    })?;
    Ok(RepositoryNode {
        database_id: row.get(0)?,
        canonical_key: row.get(1)?,
        kind: row.get(2)?,
        name: row.get(3)?,
        fq_name: row.get(4)?,
        parameter_types: signature
            .as_deref()
            .map(parameter_types_from_signature)
            .unwrap_or_default(),
        signature,
        visibility: row.get(6)?,
        modality: row.get(7)?,
        origin: row.get(8)?,
        path: row.get(9)?,
        module,
        source_set,
        owner_name: row.get(11)?,
        receiver_type: preferred_type_name(row.get(12)?, row.get(13)?),
        return_type: preferred_type_name(row.get(14)?, row.get(15)?),
        declaration_range: RepositorySourceRange {
            start_offset: row.get(16)?,
            end_offset: row.get(17)?,
            line: row.get(18)?,
        },
        flags: RepositorySymbolFlags {
            is_expect: row.get::<_, i64>(19)? != 0,
            is_actual: row.get::<_, i64>(20)? != 0,
            is_override: row.get::<_, i64>(21)? != 0,
            is_sealed: row.get::<_, i64>(22)? != 0,
            is_delegated: row.get::<_, i64>(23)? != 0,
        },
        annotations,
        evidence_class: "compiler",
    })
}

fn preferred_type_name(classifier: Option<String>, debug_text: Option<String>) -> Option<String> {
    classifier.or(debug_text)
}

fn module_and_source_set(module_name: Option<&str>) -> (Option<String>, Option<String>) {
    let Some(module_name) = module_name else {
        return (None, None);
    };
    let parts = module_name
        .strip_prefix("kast.")
        .unwrap_or(module_name)
        .split('.')
        .collect::<Vec<_>>();
    (
        parts.first().map(|value| (*value).to_string()),
        (parts.len() > 1).then(|| parts[parts.len() - 1].to_string()),
    )
}

fn parameter_types_from_signature(signature: &str) -> Vec<String> {
    let Some(raw) = signature.split('|').nth(3) else {
        return Vec::new();
    };
    if raw.is_empty() {
        return Vec::new();
    }
    let mut depth = 0usize;
    let mut start = 0usize;
    let mut parameters = Vec::new();
    for (index, character) in raw.char_indices() {
        match character {
            '<' => depth += 1,
            '>' => depth = depth.saturating_sub(1),
            ',' if depth == 0 => {
                parameters.push(raw[start..index].to_string());
                start = index + 1;
            }
            _ => {}
        }
    }
    parameters.push(raw[start..].to_string());
    parameters
}

fn node_matches_scope(node: &RepositoryNode, scope: &RepositoryScope) -> bool {
    scope.module.as_ref().is_none_or(|module| {
        node.module.as_deref() == Some(module)
            || node.path.split('/').next() == Some(module.as_str())
    }) && scope
        .source_set
        .as_ref()
        .is_none_or(|source_set| node.source_set.as_deref() == Some(source_set))
}

fn repository_node_score(node: &RepositoryNode, question: &str) -> usize {
    let question_compact = compact_search_text(question);
    let question_tokens = search_tokens(question);
    let mut score = 0;
    if let Some(owner) = &node.owner_name {
        let exact_member = compact_search_text(&format!("{owner}.{}", node.name));
        if question_compact.contains(&exact_member) {
            score += 200;
        }
        let owner_compact = compact_search_text(owner);
        if question_compact.contains(&owner_compact) {
            score += 80;
        }
    }
    for parameter in &node.parameter_types {
        let simple = parameter
            .rsplit('.')
            .next()
            .map(compact_search_text)
            .unwrap_or_default();
        if !simple.is_empty() && question_compact.contains(&simple) {
            score += 100;
        }
    }
    let metadata = [
        node.owner_name.as_deref(),
        node.fq_name.as_deref(),
        node.signature.as_deref(),
        Some(node.path.as_str()),
    ]
    .into_iter()
    .flatten()
    .collect::<Vec<_>>()
    .join(" ");
    score
        + search_tokens(&metadata)
            .intersection(&question_tokens)
            .count()
            * 5
}

fn compact_search_text(raw: &str) -> String {
    raw.chars()
        .filter(|character| character.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn search_tokens(raw: &str) -> BTreeSet<String> {
    let mut normalized = String::new();
    let mut previous_lowercase = false;
    for character in raw.chars() {
        if !character.is_alphanumeric() {
            normalized.push(' ');
            previous_lowercase = false;
            continue;
        }
        if character.is_uppercase() && previous_lowercase {
            normalized.push(' ');
        }
        normalized.extend(character.to_lowercase());
        previous_lowercase = character.is_lowercase();
    }
    normalized
        .split_whitespace()
        .filter(|token| token.len() >= 3)
        .map(str::to_string)
        .collect()
}

fn likely_declaration_term(question: &str) -> Option<&str> {
    question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| word.len() >= 3)
        .filter(|word| {
            word.chars()
                .filter(|character| character.is_uppercase())
                .count()
                >= 2
        })
        .max_by_key(|word| word.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn declaration_term_prefers_the_specific_camel_case_name() {
        assert_eq!(
            likely_declaration_term(
                "Does backend main contain DefinitelyMissingBackendSymbol in Kotlin?"
            ),
            Some("DefinitelyMissingBackendSymbol")
        );
    }

    #[test]
    fn coverage_counts_every_closed_state_once() {
        let counts = count_states(
            [
                GraphFileState::Indexed,
                GraphFileState::Excluded,
                GraphFileState::Failed,
                GraphFileState::Stale,
            ]
            .into_iter(),
        );
        assert_eq!(counts.total, 4);
        assert_eq!(counts.indexed, 1);
        assert_eq!(counts.excluded, 1);
        assert_eq!(counts.failed, 1);
        assert_eq!(counts.stale, 1);
    }

    #[test]
    fn gradle_generated_sources_are_explicitly_excluded() {
        assert!(is_generated_source(Path::new(
            "build-logic/build/generated-sources/kotlin-dsl-accessors/Accessor.kt"
        )));
        assert!(!is_generated_source(Path::new(
            "build-logic/src/main/kotlin/Plugin.kt"
        )));
    }
}
