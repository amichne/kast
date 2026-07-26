use crate::SCHEMA_VERSION;
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
}

impl RepositoryIntent {
    fn canonical(self) -> &'static str {
        match self {
            Self::Resolve => "RESOLVE",
            Self::Path => "PATH",
            Self::IncomingImpact => "INCOMING_IMPACT",
            Self::OutgoingImpact => "OUTGOING_IMPACT",
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryQueryParams {
    question: String,
    intent: RepositoryIntent,
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
            &connection,
            &params.question,
            &params.scope,
            params.limits.results,
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
            "candidateLookup": "deterministic compiler-symbol ranking",
            "execution": "generation-pinned source-index"
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
        "ordering": "canonicalKey ascending",
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
    connection: &Connection,
    question: &str,
    scope: &RepositoryScope,
    limit: usize,
) -> Result<Value> {
    let mentions = mentioned_callable_names(connection, question)?;
    let forced_name = dotted_member_name(question)
        .or_else(|| {
            likely_declaration_term(question)
                .filter(|term| term.contains("Missing"))
                .map(str::to_string)
        })
        .or_else(|| mentions.first().map(|(_, name)| name.clone()));
    let candidates = best_question_nodes(connection, question, scope, forced_name.as_deref())?;
    let ambiguous = candidates.len() > 1;
    let truncated = candidates.len() > limit;
    Ok(json!({
        "answered": !candidates.is_empty(),
        "ambiguous": ambiguous,
        "nodes": candidates.into_iter().take(limit).collect::<Vec<_>>(),
        "identityCollisions": 0,
        "truncated": truncated
    }))
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
        RepositoryIntent::Resolve => unreachable!("resolve is handled separately"),
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
