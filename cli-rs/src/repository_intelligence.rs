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
use rusqlite::{Connection, OpenFlags, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RepositoryQueryParams {
    question: String,
    intent: String,
    #[serde(default)]
    scope: RepositoryScope,
    limits: RepositoryLimits,
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
    if params.intent != "resolve" {
        return Err(CliError::new(
            "REPOSITORY_INTENT_UNAVAILABLE",
            format!(
                "repository intent `{}` is not implemented yet",
                params.intent
            ),
        ));
    }
    let snapshot = read_coverage(workspace_root, params.scope.clone())?;
    let candidates = resolve_named_candidates(workspace_root, &params.question)?;
    let status = if candidates.is_empty() {
        if snapshot.coverage.complete {
            "EMPTY"
        } else {
            "QUALIFIED_EMPTY"
        }
    } else if candidates.len() == 1 {
        "ANSWERED"
    } else {
        "AMBIGUOUS"
    };
    let qualification = (!snapshot.coverage.complete).then_some(
        "No matching declaration was found in the completely accounted indexed portion of this scope.",
    );
    Ok(json!({
        "type": "KAST_REPOSITORY_QUERY_RESULT",
        "status": status,
        "question": params.question,
        "intent": params.intent,
        "queryPlan": {
            "intent": "RESOLVE",
            "candidateLookup": "deterministic declaration-name match",
            "execution": "generation-pinned source-index"
        },
        "generation": snapshot.generation,
        "inventoryGeneration": snapshot.generation,
        "graphGeneration": snapshot.generation,
        "scope": snapshot.scope,
        "coverage": snapshot.coverage,
        "appliedFilters": params.scope,
        "bounds": params.limits,
        "ordering": "canonicalKey ascending",
        "truncated": false,
        "continuation": null,
        "nodes": candidates,
        "qualification": qualification,
        "schemaVersion": SCHEMA_VERSION
    }))
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
    if limits.depth > 6 || !(1..=500).contains(&limits.results) || limits.evidence > 50 {
        return Err(CliError::new(
            "INVALID_REPOSITORY_LIMITS",
            "depth must be at most 6, results from 1 through 500, and evidence at most 50",
        ));
    }
    Ok(())
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

fn resolve_named_candidates(workspace_root: &Path, question: &str) -> Result<Vec<Value>> {
    let Some(term) = likely_declaration_term(question) else {
        return Ok(Vec::new());
    };
    let database = config::workspace_database_path(workspace_root)?;
    let connection = Connection::open_with_flags(
        database,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
    )
    .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    source_index_db::configure_read_connection(&connection)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let semantic_table = connection
        .query_row(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'semantic_symbols'",
            [],
            |_| Ok(()),
        )
        .optional()
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    if semantic_table.is_none() {
        return Ok(Vec::new());
    }
    let mut statement = connection
        .prepare(
            "SELECT stable_key, kind, name, fq_name, signature
             FROM semantic_symbols
             WHERE name = ?1 OR fq_name = ?1 OR fq_name LIKE '%.' || ?1
             ORDER BY stable_key
             LIMIT 50",
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([term], |row| {
            Ok(json!({
                "canonicalKey": row.get::<_, String>(0)?,
                "kind": row.get::<_, String>(1)?,
                "name": row.get::<_, String>(2)?,
                "fqName": row.get::<_, Option<String>>(3)?,
                "signature": row.get::<_, Option<String>>(4)?,
                "evidenceClass": "compiler"
            }))
        })
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))
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
