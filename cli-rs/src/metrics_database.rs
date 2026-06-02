use crate::config;
use crate::error::{CliError, Result};
use crate::metrics::MetricsRequest;
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use glob::Pattern;
use rusqlite::{Connection, ErrorCode, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::ffi::c_int;
use std::path::{Path, PathBuf};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::Instant;

#[derive(Debug, Clone)]
pub(crate) struct FileFilter {
    file_glob: Option<String>,
    folder_filter: Option<String>,
    compiled_glob: Option<Pattern>,
}

impl FileFilter {
    pub(crate) fn new(file_glob: Option<String>, folder_filter: Option<String>) -> Result<Self> {
        let compiled_glob =
            match file_glob.as_deref() {
                None => None,
                Some(pattern) if pattern.starts_with("regex:") => {
                    return Err(CliError::new(
                        "METRICS_FILTER_UNSUPPORTED",
                        "regex: file filters are not supported by the Rust CLI metrics reader",
                    ));
                }
                Some(pattern) => {
                    let normalized = pattern.strip_prefix("glob:").unwrap_or(pattern);
                    Some(Pattern::new(normalized).map_err(|error| {
                        CliError::new("METRICS_FILTER_INVALID", error.to_string())
                    })?)
                }
            };
        Ok(Self {
            file_glob,
            folder_filter,
            compiled_glob,
        })
    }

    pub(crate) fn file_glob(&self) -> Option<&str> {
        self.file_glob.as_deref()
    }

    pub(crate) fn folder_filter(&self) -> Option<&str> {
        self.folder_filter.as_deref()
    }

    fn is_empty(&self) -> bool {
        self.file_glob.is_none() && self.folder_filter.is_none()
    }

    fn matches(&self, path: Option<&str>) -> bool {
        if self.is_empty() {
            return true;
        }
        let Some(path) = path else {
            return false;
        };
        if let Some(folder) = &self.folder_filter {
            let normalized = if folder.ends_with('/') {
                folder.clone()
            } else {
                format!("{folder}/")
            };
            if !path.starts_with(&normalized) {
                return false;
            }
        }
        if let Some(pattern) = &self.compiled_glob {
            return pattern.matches_path(Path::new(path));
        }
        true
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Confidence {
    level: String,
    index_completeness: f64,
    semantic_basis: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FanInMetric {
    target_fq_name: String,
    target_path: Option<String>,
    target_module_path: Option<String>,
    target_source_set: Option<String>,
    occurrence_count: i64,
    source_file_count: i64,
    source_module_count: i64,
    by_edge_kind: BTreeMap<String, i64>,
    confidence: Confidence,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FanOutMetric {
    source_path: String,
    source_module_path: Option<String>,
    source_source_set: Option<String>,
    occurrence_count: i64,
    target_symbol_count: i64,
    target_file_count: i64,
    target_module_count: i64,
    external_target_count: i64,
    by_edge_kind: BTreeMap<String, i64>,
    confidence: Confidence,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModuleCouplingMetric {
    source_module_path: String,
    source_source_set: Option<String>,
    target_module_path: String,
    target_source_set: Option<String>,
    reference_count: i64,
    public_api_count: i64,
    internal_leak_count: i64,
    by_edge_kind: BTreeMap<String, i64>,
    confidence: Confidence,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeadCodeCandidate {
    fq_name: String,
    kind: String,
    visibility: String,
    path: Option<String>,
    module_path: Option<String>,
    source_set: Option<String>,
    confidence: Confidence,
    reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ChangeImpactNode {
    source_path: String,
    depth: usize,
    via_target_fq_name: String,
    edge_kind: Option<String>,
    occurrence_count: i64,
    confidence: Confidence,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MetricsGraph {
    focal_node_id: String,
    pub(crate) nodes: Vec<MetricsGraphNode>,
    edges: Vec<MetricsGraphEdge>,
    index: MetricsGraphIndex,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MetricsGraphNode {
    pub(crate) id: String,
    pub(crate) name: String,
    #[serde(rename = "type")]
    pub(crate) node_type: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) parent_id: Option<String>,
    pub(crate) children: Vec<String>,
    pub(crate) attributes: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricsGraphEdge {
    from: String,
    to: String,
    edge_type: &'static str,
    weight: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricsGraphIndex {
    symbol_count: usize,
    file_count: usize,
    reference_count: i64,
    max_depth: usize,
}

#[derive(Debug)]
pub(crate) enum DirectMetricsError {
    Unavailable(String),
    Query(CliError),
}

impl DirectMetricsError {
    pub(crate) fn into_cli_error(self) -> CliError {
        match self {
            DirectMetricsError::Unavailable(message) => {
                CliError::new("METRICS_DB_UNAVAILABLE", message)
            }
            DirectMetricsError::Query(error) => error,
        }
    }
}

pub(crate) type DirectResult<T> = std::result::Result<T, DirectMetricsError>;

#[derive(Debug, Clone)]
pub(crate) struct MetricsQueryControls {
    cancel_flag: Option<Arc<AtomicBool>>,
    deadline: Option<Instant>,
    progress_budget: Option<usize>,
    progress_ops: c_int,
}

impl Default for MetricsQueryControls {
    fn default() -> Self {
        Self {
            cancel_flag: None,
            deadline: None,
            progress_budget: None,
            progress_ops: 10_000,
        }
    }
}

impl MetricsQueryControls {
    #[allow(dead_code)]
    pub(crate) fn with_cancel_flag(mut self, cancel_flag: Arc<AtomicBool>) -> Self {
        self.cancel_flag = Some(cancel_flag);
        self
    }

    #[allow(dead_code)]
    pub(crate) fn with_deadline(mut self, deadline: Instant) -> Self {
        self.deadline = Some(deadline);
        self
    }

    #[cfg(test)]
    fn for_test_progress_budget(progress_budget: usize) -> Self {
        Self {
            progress_budget: Some(progress_budget),
            progress_ops: 1,
            ..Self::default()
        }
    }

    fn needs_progress_handler(&self) -> bool {
        self.cancel_flag.is_some() || self.deadline.is_some() || self.progress_budget.is_some()
    }

    fn should_cancel(&self, remaining_budget: &mut Option<usize>) -> bool {
        if self
            .cancel_flag
            .as_ref()
            .is_some_and(|flag| flag.load(Ordering::Relaxed))
        {
            return true;
        }
        if self
            .deadline
            .is_some_and(|deadline| Instant::now() >= deadline)
        {
            return true;
        }
        if let Some(remaining) = remaining_budget {
            if *remaining == 0 {
                return true;
            }
            *remaining -= 1;
        }
        false
    }
}

pub(crate) struct MetricsDatabase<'a> {
    request: &'a MetricsRequest,
    conn: Connection,
    controls: MetricsQueryControls,
}

impl<'a> MetricsDatabase<'a> {
    pub(crate) fn open(request: &'a MetricsRequest) -> DirectResult<Self> {
        Self::open_with_controls(request, MetricsQueryControls::default())
    }

    pub(crate) fn open_with_controls(
        request: &'a MetricsRequest,
        controls: MetricsQueryControls,
    ) -> DirectResult<Self> {
        if !request.database().is_file() {
            return Err(DirectMetricsError::Unavailable(format!(
                "No source-index database exists at {}",
                request.database().display()
            )));
        }
        let conn = Connection::open_with_flags(
            request.database(),
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        )
        .map_err(sql_error)?;
        source_index_db::configure_read_connection(&conn).map_err(sql_error)?;
        let db = Self {
            request,
            conn,
            controls,
        };
        if !db.schema_is_current().map_err(sql_error)? {
            return Err(DirectMetricsError::Unavailable(format!(
                "source-index schema at {} is missing or not version {}",
                request.database().display(),
                SOURCE_INDEX_SCHEMA_VERSION
            )));
        }
        Ok(db)
    }

    pub(crate) fn fan_in(&self, limit: usize) -> DirectResult<Value> {
        if limit == 0 {
            return Ok(json!([]));
        }
        let confidence = self.current_confidence()?;
        let edge_breakdowns = self.edge_breakdowns_by_target()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT target_name.fq_name,
                       target_prefix.dir_path,
                       refs.tgt_filename,
                       target_meta.module_path,
                       target_meta.source_set,
                       COUNT(*) AS occurrence_count,
                       COUNT(DISTINCT refs.src_prefix_id || ':' || refs.src_filename) AS source_file_count,
                       COUNT(DISTINCT source_meta.module_path) AS source_module_count
                FROM symbol_references refs
                LEFT JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                LEFT JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                LEFT JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                GROUP BY refs.target_fq_id, refs.tgt_prefix_id, refs.tgt_filename, target_meta.module_path, target_meta.source_set
                ORDER BY occurrence_count DESC,
                         target_name.fq_name ASC,
                         COALESCE(target_prefix.dir_path || '/' || refs.tgt_filename, '') ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(params![limit as i64], |row| {
                let target_fq_name: String = row.get(0)?;
                Ok(FanInMetric {
                    target_path: self.nullable_path(row, 1, 2)?,
                    target_module_path: row.get(3)?,
                    target_source_set: row.get(4)?,
                    occurrence_count: row.get(5)?,
                    source_file_count: row.get(6)?,
                    source_module_count: row.get(7)?,
                    by_edge_kind: edge_breakdowns
                        .get(&target_fq_name)
                        .cloned()
                        .unwrap_or_default(),
                    confidence: confidence.clone(),
                    target_fq_name,
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            let metric = row.map_err(sql_error)?;
            if self.request.filter().matches(metric.target_path.as_deref()) {
                values.push(metric);
            }
        }
        serde_json::to_value(values).map_err(json_direct_error)
    }

    pub(crate) fn fan_out(&self, limit: usize) -> DirectResult<Value> {
        if limit == 0 {
            return Ok(json!([]));
        }
        let confidence = self.current_confidence()?;
        let edge_breakdowns = self.edge_breakdowns_by_source()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_prefix.dir_path,
                       refs.src_filename,
                       source_meta.module_path,
                       source_meta.source_set,
                       COUNT(*) AS occurrence_count,
                       COUNT(DISTINCT refs.target_fq_id) AS target_symbol_count,
                       COUNT(DISTINCT CASE
                           WHEN refs.tgt_prefix_id IS NULL THEN NULL
                           ELSE refs.tgt_prefix_id || ':' || refs.tgt_filename
                       END) AS target_file_count,
                       COUNT(DISTINCT target_meta.module_path) AS target_module_count,
                       SUM(CASE WHEN refs.tgt_prefix_id IS NULL OR target_meta.prefix_id IS NULL THEN 1 ELSE 0 END)
                            AS external_target_count
                FROM symbol_references refs
                JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                LEFT JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                LEFT JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                GROUP BY refs.src_prefix_id, refs.src_filename, source_meta.module_path, source_meta.source_set
                ORDER BY occurrence_count DESC,
                         source_prefix.dir_path ASC,
                         refs.src_filename ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(params![limit as i64], |row| {
                let source_path =
                    self.compose_path(row.get::<_, String>(0)?, row.get::<_, String>(1)?);
                Ok(FanOutMetric {
                    by_edge_kind: edge_breakdowns
                        .get(&source_path)
                        .cloned()
                        .unwrap_or_default(),
                    source_path,
                    source_module_path: row.get(2)?,
                    source_source_set: row.get(3)?,
                    occurrence_count: row.get(4)?,
                    target_symbol_count: row.get(5)?,
                    target_file_count: row.get(6)?,
                    target_module_count: row.get(7)?,
                    external_target_count: row.get(8)?,
                    confidence: confidence.clone(),
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            let metric = row.map_err(sql_error)?;
            if self.request.filter().matches(Some(&metric.source_path)) {
                values.push(metric);
            }
        }
        serde_json::to_value(values).map_err(json_direct_error)
    }

    pub(crate) fn coupling(&self) -> DirectResult<Value> {
        let confidence = self.current_confidence()?;
        let edge_breakdowns = self.edge_breakdowns_by_module_pair()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_meta.module_path, source_meta.source_set,
                       target_meta.module_path, target_meta.source_set,
                       COUNT(*) AS reference_count,
                       SUM(CASE WHEN declarations.visibility = 'PUBLIC' THEN 1 ELSE 0 END) AS public_api_count,
                       SUM(CASE WHEN declarations.visibility = 'INTERNAL' THEN 1 ELSE 0 END) AS internal_leak_count
                FROM symbol_references refs
                JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                LEFT JOIN declarations ON declarations.fq_id = refs.target_fq_id
                WHERE source_meta.module_path IS NOT NULL
                  AND target_meta.module_path IS NOT NULL
                  AND source_meta.module_path <> target_meta.module_path
                GROUP BY source_meta.module_path, source_meta.source_set, target_meta.module_path, target_meta.source_set
                ORDER BY reference_count DESC, source_meta.module_path ASC, target_meta.module_path ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                let source_module_path: String = row.get(0)?;
                let target_module_path: String = row.get(2)?;
                Ok(ModuleCouplingMetric {
                    by_edge_kind: edge_breakdowns
                        .get(&(source_module_path.clone(), target_module_path.clone()))
                        .cloned()
                        .unwrap_or_default(),
                    source_module_path,
                    source_source_set: row.get(1)?,
                    target_module_path,
                    target_source_set: row.get(3)?,
                    reference_count: row.get(4)?,
                    public_api_count: row.get(5)?,
                    internal_leak_count: row.get(6)?,
                    confidence: confidence.clone(),
                })
            })
            .map_err(sql_error)?;
        collect_json(rows)
    }

    pub(crate) fn dead_code(&self) -> DirectResult<Value> {
        let confidence = self.current_confidence()?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.module_path,
                       declarations.source_set
                FROM declarations
                JOIN fq_names names ON names.fq_id = declarations.fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM symbol_references refs
                    WHERE refs.target_fq_id = declarations.fq_id
                )
                ORDER BY COALESCE(declarations.module_path, '') ASC,
                         prefixes.dir_path ASC,
                         declarations.filename ASC,
                         names.fq_name ASC
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                let visibility: String = row.get(2)?;
                Ok(DeadCodeCandidate {
                    fq_name: row.get(0)?,
                    kind: row.get(1)?,
                    path: self.nullable_path(row, 3, 4)?,
                    module_path: row.get(5)?,
                    source_set: row.get(6)?,
                    confidence: confidence.for_dead_code_visibility(&visibility),
                    reason: dead_code_reason(&visibility).to_string(),
                    visibility,
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            let candidate = row.map_err(sql_error)?;
            if self.request.filter().matches(candidate.path.as_deref()) {
                values.push(candidate);
            }
        }
        serde_json::to_value(values).map_err(json_direct_error)
    }

    pub(crate) fn impact(&self, fq_name: &str, depth: usize) -> DirectResult<Value> {
        serde_json::to_value(self.change_impact_nodes(fq_name, depth)?).map_err(json_direct_error)
    }

    pub(crate) fn search(&self, query: &str, limit: usize) -> DirectResult<Value> {
        if limit == 0 {
            return Ok(json!([]));
        }
        let trimmed = query.trim();
        let values = if trimmed.is_empty() {
            self.popular_symbols(limit)?
        } else {
            let mut values = self.exact_symbol_match(trimmed, limit)?;
            let mut seen: HashSet<_> = values.iter().cloned().collect();
            let matches = if source_index_db::is_short_trigram_query(trimmed) {
                self.short_symbol_matches(trimmed, limit)?
            } else {
                self.fts_symbol_matches(trimmed, limit).unwrap_or_default()
            };
            for item in matches {
                if seen.insert(item.clone()) {
                    values.push(item);
                }
                if values.len() == limit {
                    break;
                }
            }
            values
        };
        serde_json::to_value(values).map_err(json_direct_error)
    }

    pub(crate) fn graph(&self, fq_name: &str, depth: usize) -> DirectResult<MetricsGraph> {
        let focal = self.fan_in_metric(fq_name)?;
        let impact = self.change_impact_nodes(fq_name, depth)?;
        let direct_references: Vec<_> = impact
            .iter()
            .filter(|node| node.depth == 1 && node.via_target_fq_name == fq_name)
            .cloned()
            .collect();
        let child_ids_by_parent = self.child_ids_by_parent(focal.as_ref(), &impact, fq_name);
        let mut impact_by_source_path: BTreeMap<String, Vec<ChangeImpactNode>> = BTreeMap::new();
        for node in impact.iter().cloned() {
            impact_by_source_path
                .entry(node.source_path.clone())
                .or_default()
                .push(node);
        }

        let mut nodes = Vec::new();
        nodes.push(focal_symbol_node(
            fq_name,
            focal.as_ref(),
            &direct_references,
            &child_ids_by_parent,
        ));
        if let Some(target_path) = focal
            .as_ref()
            .and_then(|metric| metric.target_path.as_ref())
        {
            nodes.push(target_file_node(
                target_path,
                focal.as_ref().expect("focal checked"),
                &child_ids_by_parent,
            ));
        }
        for nodes_for_path in impact_by_source_path.values() {
            let representative = nodes_for_path
                .iter()
                .min_by_key(|node| node.depth)
                .expect("impact group");
            nodes.push(source_file_node(
                nodes_for_path,
                &child_ids_by_parent,
                &parent_id_for(representative, &impact, fq_name),
            ));
            for node in nodes_for_path {
                nodes.push(reference_edge_node(node));
            }
        }

        let mut edges = Vec::new();
        if let Some(target_path) = focal
            .as_ref()
            .and_then(|metric| metric.target_path.as_ref())
        {
            edges.push(MetricsGraphEdge {
                from: file_node_id(target_path),
                to: symbol_node_id(fq_name),
                edge_type: "CONTAINS",
                weight: 1,
            });
        }
        for nodes_for_path in impact_by_source_path.values() {
            let representative = nodes_for_path
                .iter()
                .min_by_key(|node| node.depth)
                .expect("impact group");
            edges.push(MetricsGraphEdge {
                from: parent_id_for(representative, &impact, fq_name),
                to: source_file_node_id(&representative.source_path),
                edge_type: "REFERENCED_BY",
                weight: nodes_for_path
                    .iter()
                    .map(|node| node.occurrence_count)
                    .sum(),
            });
            for node in nodes_for_path {
                edges.push(MetricsGraphEdge {
                    from: source_file_node_id(&node.source_path),
                    to: reference_edge_node_id(node),
                    edge_type: "REFERENCES",
                    weight: node.occurrence_count,
                });
            }
        }

        let mut symbols = BTreeSet::from([fq_name.to_string()]);
        for node in &impact {
            symbols.insert(node.via_target_fq_name.clone());
        }
        let mut files = BTreeSet::new();
        if let Some(target_path) = focal
            .as_ref()
            .and_then(|metric| metric.target_path.as_ref())
        {
            files.insert(target_path.clone());
        }
        for node in &impact {
            files.insert(node.source_path.clone());
        }

        Ok(MetricsGraph {
            focal_node_id: symbol_node_id(fq_name),
            nodes,
            edges,
            index: MetricsGraphIndex {
                symbol_count: symbols.len(),
                file_count: files.len(),
                reference_count: impact.iter().map(|node| node.occurrence_count).sum(),
                max_depth: impact.iter().map(|node| node.depth).max().unwrap_or(0),
            },
        })
    }

    fn fan_in_metric(&self, fq_name: &str) -> DirectResult<Option<FanInMetric>> {
        let confidence = self.current_confidence()?;
        let edge_breakdown = self.edge_breakdown_for_target(fq_name)?;
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT target_name.fq_name,
                       target_prefix.dir_path,
                       refs.tgt_filename,
                       target_meta.module_path,
                       target_meta.source_set,
                       COUNT(*) AS occurrence_count,
                       COUNT(DISTINCT refs.src_prefix_id || ':' || refs.src_filename) AS source_file_count,
                       COUNT(DISTINCT source_meta.module_path) AS source_module_count
                FROM symbol_references refs
                LEFT JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                LEFT JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                LEFT JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                WHERE target_name.fq_name = ?
                GROUP BY refs.target_fq_id,
                         refs.tgt_prefix_id,
                         refs.tgt_filename,
                         target_meta.module_path,
                         target_meta.source_set
                ORDER BY occurrence_count DESC,
                         COALESCE(target_prefix.dir_path || '/' || refs.tgt_filename, '') ASC
                LIMIT 1
                "#,
            )
            .map_err(sql_error)?;
        let metric = stmt
            .query_row(params![fq_name], |row| {
                Ok(FanInMetric {
                    target_fq_name: row.get(0)?,
                    target_path: self.nullable_path(row, 1, 2)?,
                    target_module_path: row.get(3)?,
                    target_source_set: row.get(4)?,
                    occurrence_count: row.get(5)?,
                    source_file_count: row.get(6)?,
                    source_module_count: row.get(7)?,
                    by_edge_kind: edge_breakdown.clone(),
                    confidence: confidence.clone(),
                })
            })
            .optional()
            .map_err(sql_error)?;
        Ok(metric.filter(|metric| self.request.filter().matches(metric.target_path.as_deref())))
    }

    fn change_impact_nodes(
        &self,
        fq_name: &str,
        depth: usize,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        if depth == 0 {
            return Ok(Vec::new());
        }
        let confidence = self.current_confidence()?;
        let values = if self.has_source_symbol_edges()? {
            self.symbol_level_impact(fq_name, depth, &confidence)?
        } else {
            self.file_level_impact(fq_name, depth, &confidence)?
        };
        Ok(values
            .into_iter()
            .filter(|node| self.request.filter().matches(Some(&node.source_path)))
            .collect())
    }

    fn current_confidence(&self) -> DirectResult<Confidence> {
        let declarations_count = self.count_rows("declarations")?;
        let identifiers_count = self.count_rows("identifier_paths")?;
        let manifest_count = self.count_rows("file_manifest")?;
        let indexed_file_count: i64 = self
            .conn
            .query_row(
                "SELECT COUNT(DISTINCT src_prefix_id || ':' || src_filename) FROM symbol_references",
                [],
                |row| row.get(0),
            )
            .map_err(sql_error)?;
        let index_completeness = if manifest_count == 0 {
            0.0
        } else {
            indexed_file_count.min(manifest_count) as f64 / manifest_count as f64
        };
        let semantic_basis = if declarations_count > 0 {
            "K2_RESOLVED"
        } else if identifiers_count > 0 {
            "LEXICAL"
        } else {
            "HEURISTIC"
        };
        let level = match (semantic_basis, index_completeness) {
            ("K2_RESOLVED", value) if value > 0.95 => "HIGH",
            ("K2_RESOLVED", value) if value > 0.5 => "MEDIUM",
            ("LEXICAL", _) => "LOW",
            _ => "SPECULATIVE",
        };
        Ok(Confidence {
            level: level.to_string(),
            index_completeness,
            semantic_basis: semantic_basis.to_string(),
        })
    }

    fn count_rows(&self, table_name: &str) -> DirectResult<i64> {
        self.conn
            .query_row(&format!("SELECT COUNT(*) FROM {table_name}"), [], |row| {
                row.get(0)
            })
            .map_err(sql_error)
    }

    fn edge_breakdowns_by_target(&self) -> DirectResult<BTreeMap<String, BTreeMap<String, i64>>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                GROUP BY names.fq_name, refs.edge_kind
                "#,
            )
            .map_err(sql_error)?;
        nested_string_map(stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
            ))
        }))
    }

    fn edge_breakdown_for_target(&self, fq_name: &str) -> DirectResult<BTreeMap<String, i64>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                WHERE names.fq_name = ?
                GROUP BY refs.edge_kind
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map(params![fq_name], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .map_err(sql_error)?;
        let mut values = BTreeMap::new();
        for row in rows {
            let (edge_kind, count) = row.map_err(sql_error)?;
            values.insert(edge_kind, count);
        }
        Ok(values)
    }

    fn edge_breakdowns_by_source(&self) -> DirectResult<BTreeMap<String, BTreeMap<String, i64>>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT prefixes.dir_path, refs.src_filename, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN path_prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                GROUP BY refs.src_prefix_id, refs.src_filename, refs.edge_kind
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    self.compose_path(row.get::<_, String>(0)?, row.get::<_, String>(1)?),
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            })
            .map_err(sql_error)?;
        let mut values: BTreeMap<String, BTreeMap<String, i64>> = BTreeMap::new();
        for row in rows {
            let (outer, inner, count) = row.map_err(sql_error)?;
            values.entry(outer).or_default().insert(inner, count);
        }
        Ok(values)
    }

    fn edge_breakdowns_by_module_pair(
        &self,
    ) -> DirectResult<BTreeMap<(String, String), BTreeMap<String, i64>>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_meta.module_path, target_meta.module_path, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                WHERE source_meta.module_path IS NOT NULL
                  AND target_meta.module_path IS NOT NULL
                  AND source_meta.module_path <> target_meta.module_path
                GROUP BY source_meta.module_path, target_meta.module_path, refs.edge_kind
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    (row.get::<_, String>(0)?, row.get::<_, String>(1)?),
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            })
            .map_err(sql_error)?;
        let mut values: BTreeMap<(String, String), BTreeMap<String, i64>> = BTreeMap::new();
        for row in rows {
            let (outer, inner, count) = row.map_err(sql_error)?;
            values.entry(outer).or_default().insert(inner, count);
        }
        Ok(values)
    }

    fn has_source_symbol_edges(&self) -> DirectResult<bool> {
        self.conn
            .query_row(
                "SELECT 1 FROM symbol_references WHERE source_fq_id IS NOT NULL LIMIT 1",
                [],
                |_| Ok(true),
            )
            .optional()
            .map(|value| value.unwrap_or(false))
            .map_err(sql_error)
    }

    fn symbol_level_impact(
        &self,
        fq_name: &str,
        depth: usize,
        confidence: &Confidence,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        self.with_query_progress(|| {
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    WITH RECURSIVE impacted(depth, source_fq_id, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                        SELECT 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                        FROM symbol_references refs
                        WHERE refs.target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                          AND refs.source_fq_id IS NOT NULL
                        UNION ALL
                        SELECT impacted.depth + 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                        FROM impacted
                        JOIN symbol_references refs ON refs.target_fq_id = impacted.source_fq_id
                        WHERE impacted.depth < ?
                          AND refs.source_fq_id IS NOT NULL
                    )
                    SELECT source_prefix.dir_path,
                           impacted.src_filename,
                           impacted.depth,
                           via_target_name.fq_name,
                           impacted.edge_kind,
                           COUNT(*) AS reference_count
                    FROM impacted
                    JOIN path_prefixes source_prefix ON source_prefix.prefix_id = impacted.src_prefix_id
                    JOIN fq_names via_target_name ON via_target_name.fq_id = impacted.via_target_fq_id
                    GROUP BY impacted.src_prefix_id, impacted.src_filename, impacted.depth, impacted.via_target_fq_id, impacted.edge_kind
                    ORDER BY impacted.depth ASC, reference_count DESC, source_prefix.dir_path ASC, impacted.src_filename ASC, via_target_name.fq_name ASC
                    "#,
                )
                .map_err(sql_error)?;
            self.impact_rows(stmt.query_map(params![fq_name, depth as i64], |row| {
                self.impact_row(row, confidence)
            }))
        })
    }

    fn file_level_impact(
        &self,
        fq_name: &str,
        depth: usize,
        confidence: &Confidence,
    ) -> DirectResult<Vec<ChangeImpactNode>> {
        self.with_query_progress(|| {
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    WITH RECURSIVE impacted_files(depth, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                        SELECT 1, src_prefix_id, src_filename, target_fq_id, edge_kind
                        FROM symbol_references
                        WHERE target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                        UNION ALL
                        SELECT impacted_files.depth + 1,
                               refs.src_prefix_id,
                               refs.src_filename,
                               refs.target_fq_id,
                               refs.edge_kind
                        FROM impacted_files
                        JOIN symbol_references refs
                          ON refs.tgt_prefix_id = impacted_files.src_prefix_id
                         AND refs.tgt_filename = impacted_files.src_filename
                        WHERE impacted_files.depth < ?
                    ),
                    first_hits AS (
                        SELECT src_prefix_id, src_filename, MIN(depth) AS depth
                        FROM impacted_files
                        GROUP BY src_prefix_id, src_filename
                    )
                    SELECT source_prefix.dir_path,
                           first_hits.src_filename,
                           first_hits.depth,
                           via_target_name.fq_name,
                           impacted_files.edge_kind,
                           COUNT(refs.source_offset) AS reference_count
                    FROM first_hits
                    JOIN impacted_files
                      ON impacted_files.src_prefix_id = first_hits.src_prefix_id
                     AND impacted_files.src_filename = first_hits.src_filename
                     AND impacted_files.depth = first_hits.depth
                    JOIN symbol_references refs
                      ON refs.src_prefix_id = impacted_files.src_prefix_id
                     AND refs.src_filename = impacted_files.src_filename
                     AND refs.target_fq_id = impacted_files.via_target_fq_id
                     AND refs.edge_kind = impacted_files.edge_kind
                    JOIN fq_names via_target_name ON via_target_name.fq_id = impacted_files.via_target_fq_id
                    JOIN path_prefixes source_prefix ON source_prefix.prefix_id = first_hits.src_prefix_id
                    GROUP BY first_hits.src_prefix_id,
                             first_hits.src_filename,
                             first_hits.depth,
                             impacted_files.via_target_fq_id,
                             via_target_name.fq_name,
                             impacted_files.edge_kind
                    ORDER BY first_hits.depth ASC,
                             reference_count DESC,
                             source_prefix.dir_path ASC,
                             first_hits.src_filename ASC,
                             via_target_name.fq_name ASC
                    "#,
                )
                .map_err(sql_error)?;
            self.impact_rows(stmt.query_map(params![fq_name, depth as i64], |row| {
                self.impact_row(row, confidence)
            }))
        })
    }

    fn impact_rows<I>(&self, rows: rusqlite::Result<I>) -> DirectResult<Vec<ChangeImpactNode>>
    where
        I: Iterator<Item = rusqlite::Result<ChangeImpactNode>>,
    {
        let mut values = Vec::new();
        for row in rows.map_err(sql_error)? {
            values.push(row.map_err(sql_error)?);
        }
        Ok(values)
    }

    fn impact_row(
        &self,
        row: &Row<'_>,
        confidence: &Confidence,
    ) -> rusqlite::Result<ChangeImpactNode> {
        Ok(ChangeImpactNode {
            source_path: self.compose_path(row.get::<_, String>(0)?, row.get::<_, String>(1)?),
            depth: row.get::<_, i64>(2)? as usize,
            via_target_fq_name: row.get(3)?,
            edge_kind: row.get(4)?,
            occurrence_count: row.get(5)?,
            confidence: confidence.clone(),
        })
    }

    fn with_query_progress<T>(
        &self,
        operation: impl FnOnce() -> DirectResult<T>,
    ) -> DirectResult<T> {
        if !self.controls.needs_progress_handler() {
            return operation();
        }
        let controls = self.controls.clone();
        let mut remaining_budget = controls.progress_budget;
        self.conn
            .progress_handler(
                controls.progress_ops,
                Some(move || controls.should_cancel(&mut remaining_budget)),
            )
            .map_err(sql_error)?;
        let result = operation();
        let clear_result = self
            .conn
            .progress_handler(0, None::<fn() -> bool>)
            .map_err(sql_error);
        match (result, clear_result) {
            (Ok(value), Ok(())) => Ok(value),
            (Err(error), _) => Err(error),
            (Ok(_), Err(error)) => Err(error),
        }
    }

    fn popular_symbols(&self, limit: usize) -> DirectResult<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name
                FROM fq_names names
                JOIN symbol_references refs ON refs.target_fq_id = names.fq_id
                GROUP BY names.fq_id
                ORDER BY COUNT(*) DESC, names.fq_name ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![limit as i64], |row| row.get(0)))
    }

    fn exact_symbol_match(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name
                FROM fq_names names
                WHERE names.fq_name = ?
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
    }

    fn short_symbol_matches(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        self.with_query_progress(|| {
            let needle = source_index_db::escape_like(&query.to_lowercase());
            let fq_prefix = format!("{needle}%");
            let segment_prefix = format!("%.{}%", needle);
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    SELECT names.fq_name
                    FROM fq_names names
                    WHERE LOWER(names.fq_name) LIKE ? ESCAPE '\'
                       OR LOWER(names.fq_name) LIKE ? ESCAPE '\'
                    ORDER BY
                        CASE
                            WHEN LOWER(names.fq_name) LIKE ? ESCAPE '\' THEN 0
                            ELSE 1
                        END,
                        LENGTH(names.fq_name),
                        names.fq_name
                    LIMIT ?
                    "#,
                )
                .map_err(sql_error)?;
            string_column(stmt.query_map(
                params![fq_prefix, segment_prefix, fq_prefix, limit as i64],
                |row| row.get(0),
            ))
        })
    }

    fn fts_symbol_matches(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        self.with_query_progress(|| {
            let query = source_index_db::trigram_fts_query(query);
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    SELECT fq_name
                    FROM fq_names_fts
                    WHERE fq_names_fts MATCH ?
                    ORDER BY rank, LENGTH(fq_name), fq_name
                    LIMIT ?
                    "#,
                )
                .map_err(sql_error)?;
            string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
        })
    }

    fn schema_is_current(&self) -> rusqlite::Result<bool> {
        let version = self
            .conn
            .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
                row.get::<_, i64>(0)
            })
            .optional()?;
        Ok(version == Some(SOURCE_INDEX_SCHEMA_VERSION)
            && self.required_tables_exist()?
            && source_index_db::persistent_symbol_fts_exists(&self.conn)?)
    }

    fn required_tables_exist(&self) -> rusqlite::Result<bool> {
        let required = [
            "path_prefixes",
            "fq_names",
            "symbol_references",
            "identifier_paths",
            "file_metadata",
            "file_manifest",
            "declarations",
        ];
        for table in required {
            let exists = self
                .conn
                .query_row(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                    params![table],
                    |_| Ok(true),
                )
                .optional()?
                .unwrap_or(false);
            if !exists {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn nullable_path(
        &self,
        row: &Row<'_>,
        dir_column: usize,
        filename_column: usize,
    ) -> rusqlite::Result<Option<String>> {
        let filename: Option<String> = row.get(filename_column)?;
        Ok(filename.map(|filename| {
            let dir = row
                .get::<_, Option<String>>(dir_column)
                .ok()
                .flatten()
                .unwrap_or_default();
            self.compose_path(dir, filename)
        }))
    }

    fn compose_path(&self, relative_dir: String, filename: String) -> String {
        let path = if let Some(absolute) = relative_dir.strip_prefix("__kast_abs__/") {
            PathBuf::from(absolute).join(filename)
        } else {
            let relative = relative_dir
                .strip_prefix("__kast_rel__/")
                .unwrap_or(&relative_dir);
            relative
                .split('/')
                .filter(|segment| !segment.is_empty())
                .fold(
                    self.request.workspace_root().to_path_buf(),
                    |path, segment| path.join(segment),
                )
                .join(filename)
        };
        config::normalize(path).display().to_string()
    }

    fn child_ids_by_parent(
        &self,
        focal: Option<&FanInMetric>,
        impact: &[ChangeImpactNode],
        fq_name: &str,
    ) -> BTreeMap<String, Vec<String>> {
        let mut values: BTreeMap<String, Vec<String>> = BTreeMap::new();
        if let Some(focal) = focal
            && let Some(target_path) = &focal.target_path
        {
            values.insert(
                file_node_id(target_path),
                vec![symbol_node_id(&focal.target_fq_name)],
            );
        }
        for node in impact {
            let parent_id = parent_id_for(node, impact, fq_name);
            let children = values.entry(parent_id).or_default();
            let child = source_file_node_id(&node.source_path);
            if !children.contains(&child) {
                children.push(child);
            }
        }
        for node in impact {
            values
                .entry(source_file_node_id(&node.source_path))
                .or_default()
                .push(reference_edge_node_id(node));
        }
        values
    }
}

impl Confidence {
    fn for_dead_code_visibility(&self, visibility: &str) -> Self {
        if self.semantic_basis != "K2_RESOLVED" {
            return self.clone();
        }
        let level = match visibility {
            "PUBLIC" | "INTERNAL" | "PROTECTED" => "MEDIUM",
            _ => "HIGH",
        };
        Self {
            level: level.to_string(),
            index_completeness: self.index_completeness,
            semantic_basis: self.semantic_basis.clone(),
        }
    }
}

fn nested_string_map<I>(
    rows: rusqlite::Result<I>,
) -> DirectResult<BTreeMap<String, BTreeMap<String, i64>>>
where
    I: Iterator<Item = rusqlite::Result<(String, String, i64)>>,
{
    let mut values: BTreeMap<String, BTreeMap<String, i64>> = BTreeMap::new();
    for row in rows.map_err(sql_error)? {
        let (outer, inner, count) = row.map_err(sql_error)?;
        values.entry(outer).or_default().insert(inner, count);
    }
    Ok(values)
}

fn collect_json<T, I>(rows: I) -> DirectResult<Value>
where
    T: Serialize,
    I: Iterator<Item = rusqlite::Result<T>>,
{
    let mut values = Vec::new();
    for row in rows {
        values.push(row.map_err(sql_error)?);
    }
    serde_json::to_value(values).map_err(json_direct_error)
}

fn string_column<I>(rows: rusqlite::Result<I>) -> DirectResult<Vec<String>>
where
    I: Iterator<Item = rusqlite::Result<String>>,
{
    let mut values = Vec::new();
    for row in rows.map_err(sql_error)? {
        values.push(row.map_err(sql_error)?);
    }
    Ok(values)
}

fn dead_code_reason(visibility: &str) -> &'static str {
    if visibility == "PUBLIC" {
        "Declaration has no inbound reference rows; public declarations may still be used externally."
    } else {
        "Declaration has no inbound reference rows in the K2 declaration registry."
    }
}

fn focal_symbol_node(
    fq_name: &str,
    focal: Option<&FanInMetric>,
    direct_references: &[ChangeImpactNode],
    child_ids_by_parent: &BTreeMap<String, Vec<String>>,
) -> MetricsGraphNode {
    let mut attributes = Vec::new();
    if let Some(target_path) = focal.and_then(|metric| metric.target_path.as_ref()) {
        attributes.push(format!("path={target_path}"));
    }
    if let Some(module) = focal.and_then(|metric| metric.target_module_path.as_ref()) {
        attributes.push(format!("module={module}"));
    }
    if let Some(source_set) = focal.and_then(|metric| metric.target_source_set.as_ref()) {
        attributes.push(format!("sourceSet={source_set}"));
    }
    attributes.push(format!(
        "incomingReferences={}",
        focal
            .map(|metric| metric.occurrence_count)
            .unwrap_or_else(|| direct_references
                .iter()
                .map(|node| node.occurrence_count)
                .sum())
    ));
    attributes.push(format!(
        "sourceFiles={}",
        focal
            .map(|metric| metric.source_file_count)
            .unwrap_or_else(|| {
                direct_references
                    .iter()
                    .map(|node| node.source_path.clone())
                    .collect::<BTreeSet<_>>()
                    .len() as i64
            })
    ));
    if let Some(source_module_count) = focal.map(|metric| metric.source_module_count) {
        attributes.push(format!("sourceModules={source_module_count}"));
    }
    let id = symbol_node_id(fq_name);
    MetricsGraphNode {
        id: id.clone(),
        name: fq_name.to_string(),
        node_type: "SYMBOL",
        parent_id: focal
            .and_then(|metric| metric.target_path.as_ref())
            .map(|path| file_node_id(path)),
        children: child_ids_by_parent.get(&id).cloned().unwrap_or_default(),
        attributes,
    }
}

fn target_file_node(
    target_path: &str,
    focal: &FanInMetric,
    child_ids_by_parent: &BTreeMap<String, Vec<String>>,
) -> MetricsGraphNode {
    let id = file_node_id(target_path);
    let mut attributes = vec!["role=target".to_string()];
    if let Some(module) = &focal.target_module_path {
        attributes.push(format!("module={module}"));
    }
    if let Some(source_set) = &focal.target_source_set {
        attributes.push(format!("sourceSet={source_set}"));
    }
    MetricsGraphNode {
        id: id.clone(),
        name: target_path.to_string(),
        node_type: "FILE",
        parent_id: None,
        children: child_ids_by_parent.get(&id).cloned().unwrap_or_default(),
        attributes,
    }
}

fn source_file_node(
    nodes: &[ChangeImpactNode],
    child_ids_by_parent: &BTreeMap<String, Vec<String>>,
    parent_id: &str,
) -> MetricsGraphNode {
    let representative = nodes.iter().min_by_key(|node| node.depth).expect("nodes");
    let id = source_file_node_id(&representative.source_path);
    MetricsGraphNode {
        id: id.clone(),
        name: representative.source_path.clone(),
        node_type: "FILE",
        parent_id: Some(parent_id.to_string()),
        children: child_ids_by_parent.get(&id).cloned().unwrap_or_default(),
        attributes: vec![
            format!("incomingDepth={}", representative.depth),
            format!(
                "references={}",
                nodes.iter().map(|node| node.occurrence_count).sum::<i64>()
            ),
            format!("via={}", representative.via_target_fq_name),
        ],
    }
}

fn reference_edge_node(node: &ChangeImpactNode) -> MetricsGraphNode {
    MetricsGraphNode {
        id: reference_edge_node_id(node),
        name: node.via_target_fq_name.clone(),
        node_type: "REFERENCE_EDGE",
        parent_id: Some(source_file_node_id(&node.source_path)),
        children: vec![],
        attributes: vec![
            format!("from={}", node.source_path),
            format!("to={}", node.via_target_fq_name),
            format!("references={}", node.occurrence_count),
        ],
    }
}

fn parent_id_for(node: &ChangeImpactNode, impact: &[ChangeImpactNode], fq_name: &str) -> String {
    impact
        .iter()
        .find(|candidate| {
            candidate.depth == node.depth.saturating_sub(1)
                && node.via_target_fq_name.rsplit('.').next().unwrap_or("")
                    == candidate
                        .source_path
                        .rsplit('/')
                        .next()
                        .unwrap_or("")
                        .strip_suffix(".kt")
                        .unwrap_or("")
        })
        .map(|candidate| source_file_node_id(&candidate.source_path))
        .unwrap_or_else(|| symbol_node_id(fq_name))
}

fn symbol_node_id(fq_name: &str) -> String {
    format!("symbol:{fq_name}")
}

fn file_node_id(path: &str) -> String {
    format!("file:{path}")
}

fn source_file_node_id(path: &str) -> String {
    format!("source-file:{path}")
}

fn reference_edge_node_id(node: &ChangeImpactNode) -> String {
    format!("via:{}:{}", node.via_target_fq_name, node.source_path)
}

fn sql_error(error: rusqlite::Error) -> DirectMetricsError {
    if error.sqlite_error_code() == Some(ErrorCode::OperationInterrupted) {
        return DirectMetricsError::Query(CliError::new(
            "METRICS_QUERY_CANCELLED",
            "metrics query was cancelled before it completed",
        ));
    }
    DirectMetricsError::Query(CliError::new("SQLITE_ERROR", error.to_string()))
}

fn json_direct_error(error: serde_json::Error) -> DirectMetricsError {
    DirectMetricsError::Query(CliError::new("JSON_ERROR", error.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metrics::MetricsRequest;
    use rusqlite::{Connection, params};
    use serde_json::Value;
    use std::path::{Path, PathBuf};

    struct Fixture {
        _temp: tempfile::TempDir,
        workspace: PathBuf,
        database: PathBuf,
    }

    impl Fixture {
        fn request(
            &self,
            metric: &'static str,
            symbol: Option<&str>,
            limit: usize,
            depth: usize,
        ) -> MetricsRequest {
            MetricsRequest::for_test(
                self.workspace.clone(),
                self.database.clone(),
                metric,
                symbol.map(str::to_string),
                limit,
                depth,
            )
            .expect("test metrics request")
        }
    }

    #[test]
    fn graph_resolves_focal_symbol_outside_small_fan_in_ranking() {
        let fixture = seed_fixture();
        let request = fixture.request("graph", Some("lib.Target"), 1, 1);
        let db = MetricsDatabase::open_with_controls(&request, MetricsQueryControls::default())
            .expect("open metrics db");

        let graph = db.graph("lib.Target", 1).expect("graph");

        assert_eq!(graph.focal_node_id, "symbol:lib.Target");
        assert!(graph.nodes.iter().any(|node| {
            node.id == format!("file:{}", fixture.workspace.join("lib/Target.kt").display())
                && node.children == vec!["symbol:lib.Target".to_string()]
        }));
    }

    #[test]
    fn search_uses_exact_match_then_persistent_trigram_fts() {
        let fixture = seed_fixture();
        let request = fixture.request("search", Some("Foo"), 10, 1);
        let db = MetricsDatabase::open_with_controls(&request, MetricsQueryControls::default())
            .expect("open metrics db");

        let before = db.conn.total_changes();
        let exact = strings(db.search("lib.Foo", 10).expect("exact search"));
        let after_first = db.conn.total_changes();
        let substring = strings(db.search("Widget", 10).expect("substring search"));
        let after_second = db.conn.total_changes();
        let short = strings(db.search("Fo", 10).expect("short search"));
        let after_short = db.conn.total_changes();

        assert_eq!(exact.first().map(String::as_str), Some("lib.Foo"));
        assert!(
            exact.iter().any(|item| item == "lib.FooWidget"),
            "persistent FTS should provide broader ranked results after the exact match: {exact:?}"
        );
        assert!(
            substring.iter().any(|item| item == "lib.FooWidget"),
            "substring search should use persistent trigram FTS: {substring:?}"
        );
        assert!(
            short.iter().any(|item| item == "lib.FooWidget"),
            "short search should use direct prefix fallback before trigram FTS: {short:?}"
        );
        assert_eq!(
            before, after_first,
            "search must not create temp FTS tables"
        );
        assert_eq!(
            after_first, after_second,
            "subsequent search must keep the read-only connection unchanged"
        );
        assert_eq!(
            after_second, after_short,
            "short search must keep the read-only connection unchanged"
        );
    }

    #[test]
    fn impact_progress_cancellation_maps_to_metrics_query_cancelled() {
        let fixture = seed_fixture();
        let request = fixture.request("impact", Some("lib.Popular"), 50, 3);
        let controls = MetricsQueryControls::for_test_progress_budget(0);
        let db = MetricsDatabase::open_with_controls(&request, controls).expect("open metrics db");

        let error = db
            .impact("lib.Popular", 3)
            .expect_err("impact should be interrupted")
            .into_cli_error();

        assert_eq!(error.code, "METRICS_QUERY_CANCELLED");
    }

    #[test]
    fn metrics_connection_applies_read_only_pragmas() {
        let fixture = seed_fixture();
        let request = fixture.request("fanIn", None, 10, 1);
        let db = MetricsDatabase::open_with_controls(&request, MetricsQueryControls::default())
            .expect("open metrics db");

        assert_eq!(pragma_i64(&db.conn, "query_only"), 1);
        assert_eq!(pragma_i64(&db.conn, "mmap_size"), 268_435_456);
        assert_eq!(pragma_i64(&db.conn, "cache_size"), -64_000);
        assert_eq!(pragma_i64(&db.conn, "temp_store"), 2);
        assert_eq!(pragma_i64(&db.conn, "busy_timeout"), 5_000);
    }

    fn strings(value: Value) -> Vec<String> {
        serde_json::from_value(value).expect("string array")
    }

    fn pragma_i64(conn: &Connection, name: &str) -> i64 {
        conn.query_row(&format!("PRAGMA {name}"), [], |row| row.get(0))
            .expect("pragma")
    }

    fn seed_fixture() -> Fixture {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let database = workspace.join(".gradle/kast/cache/source-index.db");
        std::fs::create_dir_all(database.parent().expect("db parent")).expect("db parent");
        seed_source_files(&workspace);
        let conn = Connection::open(&database).expect("sqlite");
        seed_schema(&conn);
        seed_rows(&conn);
        drop(conn);
        Fixture {
            _temp: temp,
            workspace,
            database,
        }
    }

    fn seed_schema(conn: &Connection) {
        conn.execute_batch(&format!(
            r#"
            CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0, head_commit TEXT);
            INSERT INTO schema_version (version, generation, head_commit) VALUES ({}, 0, NULL);
            CREATE TABLE path_prefixes (prefix_id INTEGER PRIMARY KEY, dir_path TEXT NOT NULL UNIQUE);
            CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE);
            CREATE VIRTUAL TABLE fq_names_fts USING fts5(fq_name, tokenize='trigram');
            CREATE TRIGGER fq_names_ai AFTER INSERT ON fq_names BEGIN
                INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
            END;
            CREATE TRIGGER fq_names_ad AFTER DELETE ON fq_names BEGIN
                DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
            END;
            CREATE TRIGGER fq_names_au AFTER UPDATE OF fq_name ON fq_names BEGIN
                DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
                INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
            END;
            CREATE TABLE identifier_paths (identifier TEXT NOT NULL, prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, PRIMARY KEY (identifier, prefix_id, filename));
            CREATE TABLE file_metadata (prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, package_fq_id INTEGER, module_path TEXT, source_set TEXT, PRIMARY KEY (prefix_id, filename));
            CREATE TABLE file_manifest (prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, last_modified_millis INTEGER NOT NULL, PRIMARY KEY (prefix_id, filename));
            CREATE TABLE declarations (
                fq_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                visibility TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                declaration_offset INTEGER,
                module_path TEXT,
                source_set TEXT,
                PRIMARY KEY (fq_id, prefix_id, filename)
            );
            CREATE TABLE symbol_references (
                src_prefix_id INTEGER NOT NULL,
                src_filename TEXT NOT NULL,
                source_offset INTEGER NOT NULL,
                source_fq_id INTEGER,
                target_fq_id INTEGER NOT NULL,
                tgt_prefix_id INTEGER,
                tgt_filename TEXT,
                target_offset INTEGER,
                edge_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                PRIMARY KEY (src_prefix_id, src_filename, source_offset, target_fq_id)
            );
            "#,
            SOURCE_INDEX_SCHEMA_VERSION,
        ))
        .expect("schema");
    }

    fn seed_rows(conn: &Connection) {
        conn.execute("INSERT INTO path_prefixes VALUES (1, 'app')", [])
            .expect("app prefix");
        conn.execute("INSERT INTO path_prefixes VALUES (2, 'lib')", [])
            .expect("lib prefix");
        for (id, name) in [
            (1, "app.A"),
            (2, "app.B"),
            (3, "app.C"),
            (4, "lib.Foo"),
            (5, "lib.FooWidget"),
            (6, "lib.Target"),
            (7, "lib.Popular"),
        ] {
            conn.execute(
                "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
                params![id, name],
            )
            .expect("fq name");
        }
        for (prefix, filename, module) in [
            (1, "A.kt", ":app"),
            (1, "B.kt", ":app"),
            (1, "C.kt", ":app"),
            (2, "Foo.kt", ":lib"),
            (2, "FooWidget.kt", ":lib"),
            (2, "Target.kt", ":lib"),
            (2, "Popular.kt", ":lib"),
        ] {
            conn.execute(
                "INSERT INTO file_metadata(prefix_id, filename, module_path, source_set) VALUES (?, ?, ?, 'main')",
                params![prefix, filename, module],
            )
            .expect("file metadata");
            conn.execute(
                "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (?, ?, 1)",
                params![prefix, filename],
            )
            .expect("file manifest");
            conn.execute(
                "INSERT INTO identifier_paths(identifier, prefix_id, filename) VALUES (?, ?, ?)",
                params![filename.trim_end_matches(".kt"), prefix, filename],
            )
            .expect("identifier path");
        }
        for (fq_id, prefix, filename, module) in [
            (1, 1, "A.kt", ":app"),
            (2, 1, "B.kt", ":app"),
            (3, 1, "C.kt", ":app"),
            (4, 2, "Foo.kt", ":lib"),
            (5, 2, "FooWidget.kt", ":lib"),
            (6, 2, "Target.kt", ":lib"),
            (7, 2, "Popular.kt", ":lib"),
        ] {
            conn.execute(
                "INSERT INTO declarations(fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set) VALUES (?, 'CLASS', 'PUBLIC', ?, ?, 1, ?, 'main')",
                params![fq_id, prefix, filename, module],
            )
            .expect("declaration");
        }

        insert_ref(conn, 1, "B.kt", 10, 2, 6, 2, "Target.kt", "CALL");
        insert_ref(conn, 1, "A.kt", 11, 1, 4, 2, "Foo.kt", "CALL");
        insert_ref(conn, 1, "A.kt", 12, 1, 5, 2, "FooWidget.kt", "CALL");
        for offset in 100..130 {
            insert_ref(conn, 1, "A.kt", offset, 1, 7, 2, "Popular.kt", "CALL");
        }
        for offset in 200..230 {
            insert_ref(conn, 1, "B.kt", offset, 2, 7, 2, "Popular.kt", "CALL");
        }
        for offset in 300..330 {
            insert_ref(conn, 1, "C.kt", offset, 3, 7, 2, "Popular.kt", "CALL");
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn insert_ref(
        conn: &Connection,
        src_prefix: i64,
        src_filename: &str,
        offset: i64,
        source_fq_id: i64,
        target_fq_id: i64,
        tgt_prefix: i64,
        tgt_filename: &str,
        edge_kind: &str,
    ) {
        conn.execute(
            "INSERT INTO symbol_references(src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id, tgt_prefix_id, tgt_filename, target_offset, edge_kind) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)",
            params![src_prefix, src_filename, offset, source_fq_id, target_fq_id, tgt_prefix, tgt_filename, edge_kind],
        )
        .expect("reference");
    }

    fn seed_source_files(workspace: &Path) {
        std::fs::create_dir_all(workspace.join("app")).expect("app sources");
        std::fs::create_dir_all(workspace.join("lib")).expect("lib sources");
        for path in [
            "app/A.kt",
            "app/B.kt",
            "app/C.kt",
            "lib/Foo.kt",
            "lib/FooWidget.kt",
            "lib/Target.kt",
            "lib/Popular.kt",
        ] {
            std::fs::write(workspace.join(path), "class Placeholder\n").expect("source file");
        }
    }
}
