use crate::config;
use crate::error::{CliError, Result};
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use glob::Pattern;
use rusqlite::{Connection, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};
use std::env;
use std::path::{Path, PathBuf};

pub(crate) fn try_handle_raw_rpc(
    raw_request: &str,
    workspace_root_arg: Option<PathBuf>,
) -> Result<Option<String>> {
    let request: Value = serde_json::from_str(raw_request)?;
    if request.get("method").and_then(Value::as_str) != Some("symbol/query") {
        return Ok(None);
    }

    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let params = request.get("params").cloned().unwrap_or_else(|| json!({}));
    let workspace_root = params
        .get("workspaceRoot")
        .and_then(Value::as_str)
        .map(PathBuf::from)
        .or(workspace_root_arg)
        .unwrap_or(env::current_dir()?);
    let workspace_root = config::normalize(workspace_root);
    let response = run_symbol_query(&workspace_root, params, id)?;
    Ok(Some(serde_json::to_string(&response)?))
}

fn run_symbol_query(workspace_root: &Path, params: Value, id: Value) -> Result<Value> {
    let request = match serde_json::from_value::<SymbolQueryRequest>(params) {
        Ok(request) => request,
        Err(error) => {
            return Ok(json_rpc_success(
                id,
                failure_result("", "INVALID_FILTER", error.to_string()),
            ));
        }
    };
    if request.query.trim().is_empty() && request.anchor.is_empty() {
        return Ok(json_rpc_success(
            id,
            failure_result(
                &request.query,
                "QUERY_TOO_BROAD",
                "query may be empty only when an anchor is provided",
            ),
        ));
    }

    let database = match config::workspace_database_path(workspace_root) {
        Ok(path) => path,
        Err(error) => {
            return Ok(json_rpc_success(
                id,
                failure_result(&request.query, "INDEX_UNAVAILABLE", error.message),
            ));
        }
    };
    if !database.is_file() {
        return Ok(json_rpc_success(
            id,
            failure_result(
                &request.query,
                "INDEX_UNAVAILABLE",
                format!("No source-index database exists at {}", database.display()),
            ),
        ));
    }

    let db = match SymbolQueryDatabase::open(workspace_root, &database) {
        Ok(db) => db,
        Err(error) => {
            return Ok(json_rpc_success(
                id,
                failure_result(&request.query, "INDEX_UNAVAILABLE", error.message),
            ));
        }
    };
    match db.query(request) {
        Ok(result) => Ok(json_rpc_success(id, serde_json::to_value(result)?)),
        Err(error) => Ok(json_rpc_success(
            id,
            failure_result("", "INVALID_FILTER", error.message),
        )),
    }
}

fn json_rpc_success(id: Value, result: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "result": result,
        "id": id
    })
}

fn failure_result(query: &str, reason: &str, message: impl Into<String>) -> Value {
    json!({
        "type": "SYMBOL_QUERY_FAILURE",
        "query": query,
        "reason": reason,
        "message": message.into()
    })
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SymbolQueryRequest {
    query: String,
    #[serde(default)]
    modes: Vec<String>,
    #[serde(default)]
    filters: SymbolQueryFilters,
    #[serde(default)]
    anchor: SymbolQueryAnchor,
    #[serde(default)]
    graph: SymbolQueryGraph,
    #[serde(default)]
    semantic: SymbolQuerySemantic,
    #[serde(default = "default_limit")]
    limit: usize,
    #[serde(default)]
    include_next_requests: bool,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SymbolQueryFilters {
    #[serde(default)]
    kinds: Vec<String>,
    #[serde(default)]
    visibility: Vec<String>,
    module_path: Option<String>,
    source_set: Option<String>,
    file_glob: Option<String>,
    package_prefix: Option<String>,
    fq_name_prefix: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SymbolQueryAnchor {
    fq_name: Option<String>,
    symbol: Option<String>,
    file_path: Option<String>,
    offset: Option<i64>,
}

impl SymbolQueryAnchor {
    fn is_empty(&self) -> bool {
        self.fq_name.is_none()
            && self.symbol.is_none()
            && self.file_path.is_none()
            && self.offset.is_none()
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SymbolQueryGraph {
    #[serde(default = "default_graph_direction")]
    direction: String,
    #[serde(default)]
    edge_kinds: Vec<String>,
    #[serde(default = "default_graph_depth")]
    depth: usize,
    #[serde(default = "default_graph_max_edges")]
    max_edges_per_result: usize,
}

impl Default for SymbolQueryGraph {
    fn default() -> Self {
        Self {
            direction: default_graph_direction(),
            edge_kinds: Vec::new(),
            depth: default_graph_depth(),
            max_edges_per_result: default_graph_max_edges(),
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SymbolQuerySemantic {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug)]
struct SymbolQueryDatabase<'a> {
    workspace_root: &'a Path,
    conn: Connection,
    has_supertypes: bool,
}

#[derive(Debug, Clone)]
struct DeclarationRow {
    fq_id: i64,
    fq_name: String,
    simple_name: String,
    kind: String,
    visibility: String,
    prefix_id: i64,
    dir_path: String,
    filename: String,
    relative_path: String,
    path: String,
    declaration_offset: Option<i64>,
    module_path: Option<String>,
    source_set: Option<String>,
    package_fq_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
struct DeclarationKey {
    fq_id: i64,
    prefix_id: i64,
    filename: String,
}

#[derive(Debug, Clone)]
struct Candidate {
    declaration: DeclarationRow,
    exact_matches: Vec<SignalMatch>,
    lexical_matches: Vec<LexicalMatch>,
    structural_constraints: Vec<StructuralConstraint>,
    graph_paths: Vec<GraphPath>,
    discovered_by_graph: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SymbolQueryResponse {
    #[serde(rename = "type")]
    response_type: &'static str,
    query: String,
    available_signals: AvailableSignals,
    hard_filters: Vec<HardFilter>,
    results: Vec<SymbolQueryResult>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AvailableSignals {
    exact: bool,
    lexical: bool,
    structural: bool,
    graph: bool,
    semantic: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct HardFilter {
    field: String,
    value: Value,
    source: &'static str,
    satisfied_symbolically: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SymbolQueryResult {
    declaration: DeclarationResult,
    rank: Rank,
    signals: Signals,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_requests: Option<NextRequests>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeclarationResult {
    fq_id: i64,
    fq_name: String,
    simple_name: String,
    kind: String,
    visibility: String,
    module_path: Option<String>,
    source_set: Option<String>,
    file: DeclarationFile,
    declaration_offset: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeclarationFile {
    prefix_id: i64,
    dir_path: String,
    filename: String,
    path: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Rank {
    position: usize,
    sort_score: f64,
    components: RankComponents,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RankComponents {
    exact: f64,
    lexical: f64,
    structural: f64,
    graph: f64,
    semantic: Option<f64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Signals {
    exact: ExactSignal,
    lexical: LexicalSignal,
    structural: StructuralSignal,
    graph: GraphSignal,
    semantic: SemanticSignal,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ExactSignal {
    matched: bool,
    matches: Vec<SignalMatch>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SignalMatch {
    field: &'static str,
    match_type: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    evidence: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LexicalSignal {
    matched: bool,
    matches: Vec<LexicalMatch>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LexicalMatch {
    field: &'static str,
    term: String,
    match_type: &'static str,
    evidence: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct StructuralSignal {
    matched: bool,
    constraints: Vec<StructuralConstraint>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct StructuralConstraint {
    field: &'static str,
    operator: &'static str,
    value: Value,
    source: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphSignal {
    matched: bool,
    paths: Vec<GraphPath>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphPath {
    from_fq_name: String,
    edge_kind: String,
    to_fq_name: String,
    source_file: Option<String>,
    source_offset: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SemanticSignal {
    available: bool,
    matched: bool,
    discovery_only: bool,
    reason: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NextRequests {
    symbol_resolve: NextRequest,
    symbol_references: NextRequest,
    symbol_callers: NextRequest,
    raw_resolve: NextRequest,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NextRequest {
    method: &'static str,
    request: Value,
}

impl<'a> SymbolQueryDatabase<'a> {
    fn open(workspace_root: &'a Path, database: &Path) -> Result<Self> {
        let conn = Connection::open_with_flags(
            database,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        )
        .map_err(sql_error)?;
        source_index_db::configure_read_connection(&conn).map_err(sql_error)?;
        if !schema_is_current(&conn)? {
            return Err(CliError::new(
                "INDEX_UNAVAILABLE",
                format!(
                    "source-index schema at {} is missing or not version {}",
                    database.display(),
                    SOURCE_INDEX_SCHEMA_VERSION
                ),
            ));
        }
        let has_supertypes = table_exists(&conn, "declaration_supertypes")?;
        Ok(Self {
            workspace_root,
            conn,
            has_supertypes,
        })
    }

    fn query(&self, request: SymbolQueryRequest) -> Result<SymbolQueryResponse> {
        let compiled_file_glob = match request.filters.file_glob.as_deref() {
            None => None,
            Some(pattern) if pattern.starts_with("regex:") => {
                return Err(CliError::new(
                    "INVALID_FILTER",
                    "regex: file filters are not supported by symbol/query",
                ));
            }
            Some(pattern) => {
                let normalized = pattern.strip_prefix("glob:").unwrap_or(pattern);
                Some(
                    Pattern::new(normalized)
                        .map_err(|error| CliError::new("INVALID_FILTER", error.to_string()))?,
                )
            }
        };
        let modes = QueryModes::from_request(&request);
        let declarations = self.declarations()?;
        let by_key: HashMap<_, _> = declarations
            .iter()
            .cloned()
            .map(|declaration| (declaration.key(), declaration))
            .collect();
        let mut candidates = BTreeMap::<DeclarationKey, Candidate>::new();
        let terms = query_terms(&request.query);

        for declaration in declarations {
            if !request
                .filters
                .matches(&declaration, compiled_file_glob.as_ref())
            {
                continue;
            }
            let exact_matches = if modes.exact {
                exact_matches(&request.query, &declaration, &request.anchor)
            } else {
                Vec::new()
            };
            let lexical_matches = if modes.lexical {
                self.lexical_matches(&terms, &declaration)?
            } else {
                Vec::new()
            };
            let anchored = anchor_matches(&request.anchor, &declaration);
            if !anchored && exact_matches.is_empty() && lexical_matches.is_empty() {
                continue;
            }
            let structural_constraints = structural_constraints(&request.filters);
            let key = declaration.key();
            candidates.insert(
                key,
                Candidate {
                    declaration,
                    exact_matches,
                    lexical_matches,
                    structural_constraints,
                    graph_paths: Vec::new(),
                    discovered_by_graph: false,
                },
            );
        }

        let anchor_fq_id = self.anchor_fq_id(&request.anchor)?;
        if modes.graph {
            for candidate in candidates.values_mut() {
                candidate.graph_paths =
                    self.graph_paths_for(&candidate.declaration, &request.graph, anchor_fq_id)?;
            }
            if let Some(anchor_fq_id) = anchor_fq_id {
                for (key, paths) in self.graph_candidates(anchor_fq_id, &request.graph)? {
                    if let Some(declaration) = by_key.get(&key)
                        && request
                            .filters
                            .matches(declaration, compiled_file_glob.as_ref())
                    {
                        candidates
                            .entry(key)
                            .and_modify(|candidate| candidate.graph_paths.extend(paths.clone()))
                            .or_insert_with(|| Candidate {
                                declaration: declaration.clone(),
                                exact_matches: Vec::new(),
                                lexical_matches: Vec::new(),
                                structural_constraints: structural_constraints(&request.filters),
                                graph_paths: paths,
                                discovered_by_graph: true,
                            });
                    }
                }
            }
        }

        let mut ranked: Vec<_> = candidates.into_values().collect();
        ranked.sort_by(compare_candidates);
        ranked.truncate(request.limit);
        let include_next_requests = request.include_next_requests;
        let results = ranked
            .into_iter()
            .enumerate()
            .map(|(index, candidate)| {
                let components = rank_components(&candidate);
                let sort_score = sort_score(&components);
                SymbolQueryResult {
                    declaration: candidate.declaration.result(),
                    rank: Rank {
                        position: index + 1,
                        sort_score,
                        components,
                    },
                    signals: Signals {
                        exact: ExactSignal {
                            matched: !candidate.exact_matches.is_empty(),
                            matches: candidate.exact_matches,
                        },
                        lexical: LexicalSignal {
                            matched: !candidate.lexical_matches.is_empty(),
                            matches: candidate.lexical_matches,
                        },
                        structural: StructuralSignal {
                            matched: true,
                            constraints: candidate.structural_constraints,
                        },
                        graph: GraphSignal {
                            matched: !candidate.graph_paths.is_empty(),
                            paths: candidate.graph_paths,
                        },
                        semantic: SemanticSignal {
                            available: false,
                            matched: false,
                            discovery_only: true,
                            reason: if request.semantic.enabled {
                                "No semantic projection index configured"
                            } else {
                                "Semantic projection index is not configured"
                            },
                        },
                    },
                    next_requests: include_next_requests
                        .then(|| next_requests(&candidate.declaration)),
                }
            })
            .collect();

        Ok(SymbolQueryResponse {
            response_type: "SYMBOL_QUERY_SUCCESS",
            query: request.query,
            available_signals: AvailableSignals {
                exact: true,
                lexical: true,
                structural: true,
                graph: true,
                semantic: false,
            },
            hard_filters: hard_filters(&request.filters),
            results,
        })
    }

    fn declarations(&self) -> Result<Vec<DeclarationRow>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT declarations.fq_id,
                       names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       declarations.prefix_id,
                       COALESCE(prefixes.dir_path, '') AS dir_path,
                       declarations.filename,
                       declarations.declaration_offset,
                       COALESCE(declarations.module_path, meta.module_path) AS module_path,
                       COALESCE(declarations.source_set, meta.source_set) AS source_set,
                       package_names.fq_name AS package_fq_name
                FROM declarations
                JOIN fq_names names ON names.fq_id = declarations.fq_id
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                LEFT JOIN file_metadata meta
                  ON meta.prefix_id = declarations.prefix_id
                 AND meta.filename = declarations.filename
                LEFT JOIN fq_names package_names ON package_names.fq_id = meta.package_fq_id
                ORDER BY names.fq_name ASC, declarations.prefix_id ASC, declarations.filename ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| self.declaration_row(row))
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            values.push(row.map_err(sql_error)?);
        }
        Ok(values)
    }

    fn declaration_row(&self, row: &Row<'_>) -> rusqlite::Result<DeclarationRow> {
        let fq_name: String = row.get(1)?;
        let dir_path: String = row.get(5)?;
        let filename: String = row.get(6)?;
        Ok(DeclarationRow {
            fq_id: row.get(0)?,
            simple_name: simple_name(&fq_name).to_string(),
            fq_name,
            kind: row.get(2)?,
            visibility: row.get(3)?,
            prefix_id: row.get(4)?,
            relative_path: relative_path(&dir_path, &filename),
            path: compose_path(self.workspace_root, &dir_path, &filename),
            dir_path,
            filename,
            declaration_offset: row.get(7)?,
            module_path: row.get(8)?,
            source_set: row.get(9)?,
            package_fq_name: row.get(10)?,
        })
    }

    fn lexical_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
    ) -> Result<Vec<LexicalMatch>> {
        let mut matches = Vec::new();
        for term in terms {
            let needle = term.to_lowercase();
            let fq_lower = declaration.fq_name.to_lowercase();
            if fq_lower.contains(&needle) {
                matches.push(LexicalMatch {
                    field: "fq_names.fq_name",
                    term: term.clone(),
                    match_type: if declaration
                        .fq_name
                        .split(|ch: char| !ch.is_alphanumeric() && ch != '_')
                        .any(|token| token.eq_ignore_ascii_case(term))
                    {
                        "TOKEN"
                    } else {
                        "LIKE"
                    },
                    evidence: declaration.fq_name.clone(),
                });
            }
            if declaration.path.to_lowercase().contains(&needle) {
                matches.push(LexicalMatch {
                    field: "file_path",
                    term: term.clone(),
                    match_type: "LIKE",
                    evidence: declaration.path.clone(),
                });
            }
        }
        matches.extend(self.identifier_matches(terms, declaration)?);
        matches.extend(self.import_matches(terms, declaration)?);
        Ok(matches)
    }

    fn identifier_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
    ) -> Result<Vec<LexicalMatch>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT identifier
                FROM identifier_paths
                WHERE prefix_id = ? AND filename = ?
                ORDER BY identifier ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(
                params![declaration.prefix_id, declaration.filename],
                |row| row.get::<_, String>(0),
            )
            .map_err(sql_error)?;
        let mut identifiers = Vec::new();
        for row in rows {
            identifiers.push(row.map_err(sql_error)?);
        }
        let mut matches = Vec::new();
        for term in terms {
            let needle = term.to_lowercase();
            for identifier in &identifiers {
                if identifier.to_lowercase().contains(&needle) {
                    matches.push(LexicalMatch {
                        field: "identifier_paths.identifier",
                        term: term.clone(),
                        match_type: "LIKE",
                        evidence: identifier.clone(),
                    });
                }
            }
        }
        Ok(matches)
    }

    fn import_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
    ) -> Result<Vec<LexicalMatch>> {
        let mut matches = Vec::new();
        if table_exists(&self.conn, "file_imports")? {
            matches.extend(self.import_table_matches(terms, declaration, "file_imports")?);
        }
        if table_exists(&self.conn, "file_wildcard_imports")? {
            matches.extend(self.import_table_matches(
                terms,
                declaration,
                "file_wildcard_imports",
            )?);
        }
        Ok(matches)
    }

    fn import_table_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
        table_name: &str,
    ) -> Result<Vec<LexicalMatch>> {
        let sql = format!(
            r#"
            SELECT names.fq_name
            FROM {table_name} imports
            JOIN fq_names names ON names.fq_id = imports.fq_id
            WHERE imports.prefix_id = ? AND imports.filename = ?
            ORDER BY names.fq_name ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let rows = stmt
            .query_map(
                params![declaration.prefix_id, declaration.filename],
                |row| row.get::<_, String>(0),
            )
            .map_err(sql_error)?;
        let mut imports = Vec::new();
        for row in rows {
            imports.push(row.map_err(sql_error)?);
        }
        let mut matches = Vec::new();
        for term in terms {
            let needle = term.to_lowercase();
            for import in &imports {
                if import.to_lowercase().contains(&needle) {
                    matches.push(LexicalMatch {
                        field: "import_fq_name",
                        term: term.clone(),
                        match_type: "LIKE",
                        evidence: import.clone(),
                    });
                }
            }
        }
        Ok(matches)
    }

    fn anchor_fq_id(&self, anchor: &SymbolQueryAnchor) -> Result<Option<i64>> {
        if let Some(fq_name) = &anchor.fq_name {
            return self
                .conn
                .query_row(
                    "SELECT fq_id FROM fq_names WHERE fq_name = ?",
                    params![fq_name],
                    |row| row.get(0),
                )
                .optional()
                .map_err(sql_error);
        }
        Ok(None)
    }

    fn graph_paths_for(
        &self,
        declaration: &DeclarationRow,
        graph: &SymbolQueryGraph,
        anchor_fq_id: Option<i64>,
    ) -> Result<Vec<GraphPath>> {
        if let Some(anchor) = anchor_fq_id
            && declaration.fq_id != anchor
        {
            return Ok(Vec::new());
        }
        let mut paths = Vec::new();
        let max_edges = graph.max_edges_per_result as i64;
        if graph.direction == "INCOMING" || graph.direction == "BOTH" {
            paths.extend(self.symbol_reference_paths(
                "refs.target_fq_id = ?",
                declaration.fq_id,
                graph,
                max_edges,
            )?);
            if self.has_supertypes && graph_includes_inheritance(graph) {
                paths.extend(self.supertype_paths_for(declaration, true, max_edges)?);
            }
        }
        if graph.direction == "OUTGOING" || graph.direction == "BOTH" {
            paths.extend(self.symbol_reference_paths(
                "refs.source_fq_id = ?",
                declaration.fq_id,
                graph,
                max_edges,
            )?);
            if self.has_supertypes && graph_includes_inheritance(graph) {
                paths.extend(self.supertype_paths_for(declaration, false, max_edges)?);
            }
        }
        paths.truncate(graph.max_edges_per_result);
        Ok(paths)
    }

    fn graph_candidates(
        &self,
        anchor_fq_id: i64,
        graph: &SymbolQueryGraph,
    ) -> Result<BTreeMap<DeclarationKey, Vec<GraphPath>>> {
        let mut values = BTreeMap::new();
        if graph.direction == "INCOMING" || graph.direction == "BOTH" {
            self.graph_candidate_rows(
                &mut values,
                r#"
                SELECT source_declarations.fq_id,
                       source_declarations.prefix_id,
                       source_declarations.filename,
                       source_names.fq_name,
                       refs.edge_kind,
                       target_names.fq_name,
                       source_prefix.dir_path,
                       refs.src_filename,
                       refs.source_offset
                FROM symbol_references refs
                JOIN declarations source_declarations ON source_declarations.fq_id = refs.source_fq_id
                JOIN fq_names source_names ON source_names.fq_id = source_declarations.fq_id
                JOIN fq_names target_names ON target_names.fq_id = refs.target_fq_id
                JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                WHERE refs.target_fq_id = ?
                "#,
                anchor_fq_id,
                graph,
            )?;
        }
        if graph.direction == "OUTGOING" || graph.direction == "BOTH" {
            self.graph_candidate_rows(
                &mut values,
                r#"
                SELECT target_declarations.fq_id,
                       target_declarations.prefix_id,
                       target_declarations.filename,
                       source_names.fq_name,
                       refs.edge_kind,
                       target_names.fq_name,
                       source_prefix.dir_path,
                       refs.src_filename,
                       refs.source_offset
                FROM symbol_references refs
                JOIN declarations target_declarations ON target_declarations.fq_id = refs.target_fq_id
                JOIN fq_names source_names ON source_names.fq_id = refs.source_fq_id
                JOIN fq_names target_names ON target_names.fq_id = target_declarations.fq_id
                JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                WHERE refs.source_fq_id = ?
                "#,
                anchor_fq_id,
                graph,
            )?;
        }
        if self.has_supertypes && graph_includes_inheritance(graph) {
            self.supertype_candidate_rows(&mut values, anchor_fq_id, graph)?;
        }
        Ok(values)
    }

    fn supertype_candidate_rows(
        &self,
        values: &mut BTreeMap<DeclarationKey, Vec<GraphPath>>,
        anchor_fq_id: i64,
        graph: &SymbolQueryGraph,
    ) -> Result<()> {
        if graph.direction == "INCOMING" || graph.direction == "BOTH" {
            self.supertype_candidate_rows_for_direction(
                values,
                r#"
                SELECT declarations.fq_id,
                       declarations.prefix_id,
                       declarations.filename,
                       declaration_names.fq_name,
                       supertype_names.fq_name,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset
                FROM declaration_supertypes supertypes
                JOIN declarations ON declarations.fq_id = supertypes.declaration_fq_id
                JOIN fq_names declaration_names ON declaration_names.fq_id = supertypes.declaration_fq_id
                JOIN fq_names supertype_names ON supertype_names.fq_id = supertypes.supertype_fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE supertypes.supertype_fq_id = ?
                ORDER BY declaration_names.fq_name ASC
                LIMIT ?
                "#,
                anchor_fq_id,
                graph.max_edges_per_result as i64,
            )?;
        }
        if graph.direction == "OUTGOING" || graph.direction == "BOTH" {
            self.supertype_candidate_rows_for_direction(
                values,
                r#"
                SELECT declarations.fq_id,
                       declarations.prefix_id,
                       declarations.filename,
                       declaration_names.fq_name,
                       supertype_names.fq_name,
                       anchor_prefixes.dir_path,
                       anchor_declarations.filename,
                       anchor_declarations.declaration_offset
                FROM declaration_supertypes supertypes
                JOIN declarations ON declarations.fq_id = supertypes.supertype_fq_id
                JOIN declarations anchor_declarations ON anchor_declarations.fq_id = supertypes.declaration_fq_id
                JOIN fq_names declaration_names ON declaration_names.fq_id = supertypes.declaration_fq_id
                JOIN fq_names supertype_names ON supertype_names.fq_id = supertypes.supertype_fq_id
                JOIN path_prefixes anchor_prefixes ON anchor_prefixes.prefix_id = anchor_declarations.prefix_id
                WHERE supertypes.declaration_fq_id = ?
                ORDER BY supertype_names.fq_name ASC
                LIMIT ?
                "#,
                anchor_fq_id,
                graph.max_edges_per_result as i64,
            )?;
        }
        Ok(())
    }

    fn supertype_candidate_rows_for_direction(
        &self,
        values: &mut BTreeMap<DeclarationKey, Vec<GraphPath>>,
        sql: &str,
        anchor_fq_id: i64,
        limit: i64,
    ) -> Result<()> {
        let mut stmt = self.conn.prepare(sql).map_err(sql_error)?;
        let mut rows = stmt
            .query(params![anchor_fq_id, limit])
            .map_err(sql_error)?;
        while let Some(row) = rows.next().map_err(sql_error)? {
            let key = DeclarationKey {
                fq_id: row.get(0).map_err(sql_error)?,
                prefix_id: row.get(1).map_err(sql_error)?,
                filename: row.get(2).map_err(sql_error)?,
            };
            values.entry(key).or_default().push(GraphPath {
                from_fq_name: row.get(3).map_err(sql_error)?,
                edge_kind: "INHERITANCE".to_string(),
                to_fq_name: row.get(4).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(5).map_err(sql_error)?,
                    &row.get::<_, String>(6).map_err(sql_error)?,
                )),
                source_offset: row.get(7).map_err(sql_error)?,
            });
        }
        Ok(())
    }

    fn graph_candidate_rows(
        &self,
        values: &mut BTreeMap<DeclarationKey, Vec<GraphPath>>,
        base_sql: &str,
        anchor_fq_id: i64,
        graph: &SymbolQueryGraph,
    ) -> Result<()> {
        let sql = format!(
            "{base_sql} {} ORDER BY refs.source_offset ASC LIMIT ?",
            edge_filter_sql(graph)
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let mut rows = if graph.edge_kinds.is_empty() {
            stmt.query(params![anchor_fq_id, graph.max_edges_per_result as i64])
                .map_err(sql_error)?
        } else {
            let edge_kinds = graph.edge_kinds.join(",");
            stmt.query(params![
                anchor_fq_id,
                edge_kinds,
                graph.max_edges_per_result as i64
            ])
            .map_err(sql_error)?
        };
        while let Some(row) = rows.next().map_err(sql_error)? {
            let key = DeclarationKey {
                fq_id: row.get(0).map_err(sql_error)?,
                prefix_id: row.get(1).map_err(sql_error)?,
                filename: row.get(2).map_err(sql_error)?,
            };
            values.entry(key).or_default().push(GraphPath {
                from_fq_name: row.get(3).map_err(sql_error)?,
                edge_kind: row.get(4).map_err(sql_error)?,
                to_fq_name: row.get(5).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(6).map_err(sql_error)?,
                    &row.get::<_, String>(7).map_err(sql_error)?,
                )),
                source_offset: row.get(8).map_err(sql_error)?,
            });
        }
        Ok(())
    }

    fn symbol_reference_paths(
        &self,
        predicate: &str,
        fq_id: i64,
        graph: &SymbolQueryGraph,
        limit: i64,
    ) -> Result<Vec<GraphPath>> {
        let sql = format!(
            r#"
            SELECT source_names.fq_name,
                   refs.edge_kind,
                   target_names.fq_name,
                   source_prefix.dir_path,
                   refs.src_filename,
                   refs.source_offset
            FROM symbol_references refs
            LEFT JOIN fq_names source_names ON source_names.fq_id = refs.source_fq_id
            JOIN fq_names target_names ON target_names.fq_id = refs.target_fq_id
            JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
            WHERE {predicate}
              {}
            ORDER BY refs.source_offset ASC
            LIMIT ?
            "#,
            edge_filter_sql(graph)
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let mut rows = if graph.edge_kinds.is_empty() {
            stmt.query(params![fq_id, limit]).map_err(sql_error)?
        } else {
            let edge_kinds = graph.edge_kinds.join(",");
            stmt.query(params![fq_id, edge_kinds, limit])
                .map_err(sql_error)?
        };
        self.graph_path_rows(&mut rows)
    }

    fn graph_path_rows(&self, rows: &mut rusqlite::Rows<'_>) -> Result<Vec<GraphPath>> {
        let mut paths = Vec::new();
        while let Some(row) = rows.next().map_err(sql_error)? {
            paths.push(GraphPath {
                from_fq_name: row
                    .get::<_, Option<String>>(0)
                    .map_err(sql_error)?
                    .unwrap_or_else(|| "<unknown>".to_string()),
                edge_kind: row.get(1).map_err(sql_error)?,
                to_fq_name: row.get(2).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(3).map_err(sql_error)?,
                    &row.get::<_, String>(4).map_err(sql_error)?,
                )),
                source_offset: row.get(5).map_err(sql_error)?,
            });
        }
        Ok(paths)
    }

    fn supertype_paths_for(
        &self,
        declaration: &DeclarationRow,
        incoming: bool,
        limit: i64,
    ) -> Result<Vec<GraphPath>> {
        let (predicate, from_column, to_column) = if incoming {
            (
                "supertypes.supertype_fq_id = ?",
                "declaration_names.fq_name",
                "supertype_names.fq_name",
            )
        } else {
            (
                "supertypes.declaration_fq_id = ?",
                "declaration_names.fq_name",
                "supertype_names.fq_name",
            )
        };
        let mut stmt = self
            .conn
            .prepare(&format!(
                r#"
                SELECT {from_column},
                       {to_column},
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset
                FROM declaration_supertypes supertypes
                JOIN declarations ON declarations.fq_id = supertypes.declaration_fq_id
                JOIN fq_names declaration_names ON declaration_names.fq_id = supertypes.declaration_fq_id
                JOIN fq_names supertype_names ON supertype_names.fq_id = supertypes.supertype_fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE {predicate}
                LIMIT ?
                "#
            ))
            .map_err(sql_error)?;
        let mut rows = stmt
            .query(params![declaration.fq_id, limit])
            .map_err(sql_error)?;
        let mut paths = Vec::new();
        while let Some(row) = rows.next().map_err(sql_error)? {
            paths.push(GraphPath {
                from_fq_name: row.get(0).map_err(sql_error)?,
                edge_kind: "INHERITANCE".to_string(),
                to_fq_name: row.get(1).map_err(sql_error)?,
                source_file: Some(compose_path(
                    self.workspace_root,
                    &row.get::<_, String>(2).map_err(sql_error)?,
                    &row.get::<_, String>(3).map_err(sql_error)?,
                )),
                source_offset: row.get(4).map_err(sql_error)?,
            });
        }
        Ok(paths)
    }
}

#[derive(Debug, Clone)]
struct QueryModes {
    exact: bool,
    lexical: bool,
    graph: bool,
}

impl QueryModes {
    fn from_request(request: &SymbolQueryRequest) -> Self {
        if request.modes.is_empty() {
            return Self {
                exact: true,
                lexical: true,
                graph: request.graph.depth > 0,
            };
        }
        Self {
            exact: request.modes.iter().any(|mode| mode == "exact"),
            lexical: request.modes.iter().any(|mode| mode == "lexical"),
            graph: request.modes.iter().any(|mode| mode == "graph"),
        }
    }
}

impl SymbolQueryFilters {
    fn matches(&self, declaration: &DeclarationRow, file_glob: Option<&Pattern>) -> bool {
        if !self.kinds.is_empty() && !self.kinds.iter().any(|kind| kind == &declaration.kind) {
            return false;
        }
        if !self.visibility.is_empty()
            && !self
                .visibility
                .iter()
                .any(|visibility| visibility == &declaration.visibility)
        {
            return false;
        }
        if let Some(module_path) = &self.module_path
            && declaration.module_path.as_ref() != Some(module_path)
        {
            return false;
        }
        if let Some(source_set) = &self.source_set
            && declaration.source_set.as_ref() != Some(source_set)
        {
            return false;
        }
        if let Some(package_prefix) = &self.package_prefix
            && !declaration
                .package_fq_name
                .as_ref()
                .is_some_and(|package| package.starts_with(package_prefix))
        {
            return false;
        }
        if let Some(fq_name_prefix) = &self.fq_name_prefix
            && !declaration.fq_name.starts_with(fq_name_prefix)
        {
            return false;
        }
        if let Some(pattern) = file_glob
            && !pattern.matches_path(Path::new(&declaration.path))
            && !pattern.matches_path(Path::new(&declaration.relative_path))
            && !pattern.matches(&declaration.relative_path)
            && !pattern.matches(&declaration.filename)
        {
            return false;
        }
        true
    }
}

impl DeclarationRow {
    fn key(&self) -> DeclarationKey {
        DeclarationKey {
            fq_id: self.fq_id,
            prefix_id: self.prefix_id,
            filename: self.filename.clone(),
        }
    }

    fn result(&self) -> DeclarationResult {
        DeclarationResult {
            fq_id: self.fq_id,
            fq_name: self.fq_name.clone(),
            simple_name: self.simple_name.clone(),
            kind: self.kind.clone(),
            visibility: self.visibility.clone(),
            module_path: self.module_path.clone(),
            source_set: self.source_set.clone(),
            file: DeclarationFile {
                prefix_id: self.prefix_id,
                dir_path: self.dir_path.clone(),
                filename: self.filename.clone(),
                path: self.path.clone(),
            },
            declaration_offset: self.declaration_offset,
        }
    }
}

fn exact_matches(
    query: &str,
    declaration: &DeclarationRow,
    anchor: &SymbolQueryAnchor,
) -> Vec<SignalMatch> {
    let trimmed = query.trim();
    let mut matches = Vec::new();
    if !trimmed.is_empty() && declaration.fq_name == trimmed {
        matches.push(SignalMatch {
            field: "fq_names.fq_name",
            match_type: "EQUALS",
            evidence: Some(declaration.fq_name.clone()),
        });
    }
    if !trimmed.is_empty() && declaration.simple_name == trimmed {
        matches.push(SignalMatch {
            field: "fq_names.fq_name",
            match_type: "SIMPLE_NAME_EQUALS",
            evidence: Some(declaration.simple_name.clone()),
        });
    }
    if anchor.fq_name.as_ref() == Some(&declaration.fq_name) {
        matches.push(SignalMatch {
            field: "anchor.fqName",
            match_type: "EQUALS",
            evidence: Some(declaration.fq_name.clone()),
        });
    }
    matches
}

fn anchor_matches(anchor: &SymbolQueryAnchor, declaration: &DeclarationRow) -> bool {
    if anchor.fq_name.as_ref() == Some(&declaration.fq_name) {
        return true;
    }
    if anchor.symbol.as_ref() == Some(&declaration.simple_name)
        || anchor.symbol.as_ref() == Some(&declaration.fq_name)
    {
        return true;
    }
    if let Some(file_path) = &anchor.file_path
        && (Path::new(file_path) == Path::new(&declaration.path)
            || file_path.ends_with(&declaration.filename))
        && anchor
            .offset
            .is_none_or(|offset| Some(offset) == declaration.declaration_offset)
    {
        return true;
    }
    false
}

fn structural_constraints(filters: &SymbolQueryFilters) -> Vec<StructuralConstraint> {
    let mut constraints = Vec::new();
    if !filters.kinds.is_empty() {
        constraints.push(StructuralConstraint {
            field: "declarations.kind",
            operator: "IN",
            value: json!(filters.kinds),
            source: "sqlite",
        });
    }
    if !filters.visibility.is_empty() {
        constraints.push(StructuralConstraint {
            field: "declarations.visibility",
            operator: "IN",
            value: json!(filters.visibility),
            source: "sqlite",
        });
    }
    if let Some(module_path) = &filters.module_path {
        constraints.push(StructuralConstraint {
            field: "declarations.module_path",
            operator: "=",
            value: json!(module_path),
            source: "sqlite",
        });
    }
    if let Some(source_set) = &filters.source_set {
        constraints.push(StructuralConstraint {
            field: "declarations.source_set",
            operator: "=",
            value: json!(source_set),
            source: "sqlite",
        });
    }
    if let Some(file_glob) = &filters.file_glob {
        constraints.push(StructuralConstraint {
            field: "file_path",
            operator: "GLOB",
            value: json!(file_glob),
            source: "sqlite",
        });
    }
    if let Some(package_prefix) = &filters.package_prefix {
        constraints.push(StructuralConstraint {
            field: "file_metadata.package_fq_id",
            operator: "PREFIX",
            value: json!(package_prefix),
            source: "sqlite",
        });
    }
    if let Some(fq_name_prefix) = &filters.fq_name_prefix {
        constraints.push(StructuralConstraint {
            field: "fq_names.fq_name",
            operator: "PREFIX",
            value: json!(fq_name_prefix),
            source: "sqlite",
        });
    }
    constraints
}

fn hard_filters(filters: &SymbolQueryFilters) -> Vec<HardFilter> {
    let mut hard_filters = Vec::new();
    if !filters.kinds.is_empty() {
        hard_filters.push(HardFilter {
            field: "kinds".to_string(),
            value: json!(filters.kinds),
            source: "declarations.kind",
            satisfied_symbolically: true,
        });
    }
    if !filters.visibility.is_empty() {
        hard_filters.push(HardFilter {
            field: "visibility".to_string(),
            value: json!(filters.visibility),
            source: "declarations.visibility",
            satisfied_symbolically: true,
        });
    }
    if let Some(module_path) = &filters.module_path {
        hard_filters.push(HardFilter {
            field: "modulePath".to_string(),
            value: json!(module_path),
            source: "declarations.module_path",
            satisfied_symbolically: true,
        });
    }
    if let Some(source_set) = &filters.source_set {
        hard_filters.push(HardFilter {
            field: "sourceSet".to_string(),
            value: json!(source_set),
            source: "declarations.source_set",
            satisfied_symbolically: true,
        });
    }
    if let Some(file_glob) = &filters.file_glob {
        hard_filters.push(HardFilter {
            field: "fileGlob".to_string(),
            value: json!(file_glob),
            source: "path_prefixes.dir_path + declarations.filename",
            satisfied_symbolically: true,
        });
    }
    if let Some(package_prefix) = &filters.package_prefix {
        hard_filters.push(HardFilter {
            field: "packagePrefix".to_string(),
            value: json!(package_prefix),
            source: "file_metadata.package_fq_id",
            satisfied_symbolically: true,
        });
    }
    if let Some(fq_name_prefix) = &filters.fq_name_prefix {
        hard_filters.push(HardFilter {
            field: "fqNamePrefix".to_string(),
            value: json!(fq_name_prefix),
            source: "fq_names.fq_name",
            satisfied_symbolically: true,
        });
    }
    hard_filters
}

fn rank_components(candidate: &Candidate) -> RankComponents {
    RankComponents {
        exact: if candidate.exact_matches.is_empty() {
            0.0
        } else {
            1.0
        },
        lexical: (candidate.lexical_matches.len().min(5) as f64) / 5.0,
        structural: 1.0,
        graph: if candidate.graph_paths.is_empty() {
            if candidate.discovered_by_graph {
                0.25
            } else {
                0.0
            }
        } else {
            (candidate.graph_paths.len().min(5) as f64) / 5.0
        },
        semantic: None,
    }
}

fn sort_score(components: &RankComponents) -> f64 {
    components.exact + components.lexical * 0.7 + components.structural * 0.2 + components.graph
}

fn compare_candidates(left: &Candidate, right: &Candidate) -> Ordering {
    let left_components = rank_components(left);
    let right_components = rank_components(right);
    sort_score(&right_components)
        .partial_cmp(&sort_score(&left_components))
        .unwrap_or(Ordering::Equal)
        .then_with(|| left.declaration.fq_name.cmp(&right.declaration.fq_name))
        .then_with(|| left.declaration.path.cmp(&right.declaration.path))
}

fn next_requests(declaration: &DeclarationRow) -> NextRequests {
    let kind = declaration.kind.to_ascii_lowercase();
    let symbol_request = json!({
        "symbol": declaration.simple_name,
        "fileHint": declaration.filename,
        "kind": kind
    });
    NextRequests {
        symbol_resolve: NextRequest {
            method: "symbol/resolve",
            request: json!({
                "symbol": declaration.simple_name,
                "fileHint": declaration.filename,
                "kind": kind,
                "includeDeclarationScope": true
            }),
        },
        symbol_references: NextRequest {
            method: "symbol/references",
            request: json!({
                "symbol": declaration.simple_name,
                "fileHint": declaration.filename,
                "kind": kind,
                "includeDeclaration": true
            }),
        },
        symbol_callers: NextRequest {
            method: "symbol/callers",
            request: json!({
                "symbol": declaration.simple_name,
                "fileHint": declaration.filename,
                "kind": kind,
                "direction": "incoming",
                "depth": 1
            }),
        },
        raw_resolve: NextRequest {
            method: "raw/resolve",
            request: json!({
                "position": {
                    "filePath": declaration.path,
                    "offset": declaration.declaration_offset
                },
                "symbol": symbol_request
            }),
        },
    }
}

fn query_terms(query: &str) -> Vec<String> {
    query
        .split(|ch: char| !ch.is_alphanumeric() && ch != '_')
        .filter(|term| !term.is_empty())
        .map(str::to_string)
        .collect()
}

fn simple_name(fq_name: &str) -> &str {
    fq_name.rsplit('.').next().unwrap_or(fq_name)
}

fn compose_path(workspace_root: &Path, relative_dir: &str, filename: &str) -> String {
    let path = if let Some(absolute) = relative_dir.strip_prefix("__kast_abs__/") {
        PathBuf::from(absolute).join(filename)
    } else {
        let relative = relative_dir
            .strip_prefix("__kast_rel__/")
            .unwrap_or(relative_dir);
        relative
            .split('/')
            .filter(|segment| !segment.is_empty())
            .fold(workspace_root.to_path_buf(), |path, segment| {
                path.join(segment)
            })
            .join(filename)
    };
    config::normalize(path).display().to_string()
}

fn relative_path(relative_dir: &str, filename: &str) -> String {
    let relative = relative_dir
        .strip_prefix("__kast_rel__/")
        .or_else(|| relative_dir.strip_prefix("__kast_abs__/"))
        .unwrap_or(relative_dir);
    relative
        .split('/')
        .filter(|segment| !segment.is_empty())
        .fold(PathBuf::new(), |path, segment| path.join(segment))
        .join(filename)
        .display()
        .to_string()
}

fn schema_is_current(conn: &Connection) -> Result<bool> {
    let version = conn
        .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
            row.get::<_, i64>(0)
        })
        .optional()
        .map_err(sql_error)?;
    Ok(version == Some(SOURCE_INDEX_SCHEMA_VERSION) && required_tables_exist(conn)?)
}

fn required_tables_exist(conn: &Connection) -> Result<bool> {
    for table in [
        "path_prefixes",
        "fq_names",
        "symbol_references",
        "identifier_paths",
        "file_metadata",
        "file_manifest",
        "declarations",
    ] {
        if !table_exists(conn, table)? {
            return Ok(false);
        }
    }
    Ok(true)
}

fn table_exists(conn: &Connection, table: &str) -> Result<bool> {
    conn.query_row(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        params![table],
        |_| Ok(true),
    )
    .optional()
    .map(|value| value.unwrap_or(false))
    .map_err(sql_error)
}

fn edge_filter_sql(graph: &SymbolQueryGraph) -> &'static str {
    if graph.edge_kinds.is_empty() {
        ""
    } else {
        "AND instr(',' || ? || ',', ',' || refs.edge_kind || ',') > 0"
    }
}

fn graph_includes_inheritance(graph: &SymbolQueryGraph) -> bool {
    graph.edge_kinds.is_empty() || graph.edge_kinds.iter().any(|kind| kind == "INHERITANCE")
}

fn default_limit() -> usize {
    25
}

fn default_graph_direction() -> String {
    "BOTH".to_string()
}

fn default_graph_depth() -> usize {
    1
}

fn default_graph_max_edges() -> usize {
    10
}

fn sql_error(error: rusqlite::Error) -> CliError {
    CliError::new("SQLITE_ERROR", error.to_string())
}
