use crate::SCHEMA_VERSION;
use crate::cli::{DemoArgs, DemoView};
use crate::config;
use crate::error::{CliError, Result};
use crate::source_index_db;
use crate::source_index_schema::SOURCE_INDEX_SCHEMA_VERSION;
use crossterm::event::{self, Event, KeyCode, KeyEvent, KeyModifiers};
use crossterm::execute;
use crossterm::terminal::{
    EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode,
};
use ratatui::backend::CrosstermBackend;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Wrap};
use ratatui::{Frame, Terminal};
use rusqlite::{Connection, OpenFlags, OptionalExtension, Row, params};
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::io::{self, IsTerminal, Stdout};
use std::path::PathBuf;
use std::time::Duration;

const PREVIEW_RADIUS: usize = 7;

#[derive(Debug, Clone)]
struct DemoRequest {
    workspace_root: PathBuf,
    database: PathBuf,
    symbol: Option<String>,
    query: Option<String>,
    limit: usize,
    json: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoResponse {
    ok: bool,
    snapshot: DemoSnapshot,
    schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoSnapshot {
    mode: &'static str,
    workspace_root: String,
    database: String,
    query: String,
    current: Option<SymbolDetail>,
    search_results: Vec<SymbolHit>,
    incoming: Vec<SymbolRelation>,
    outgoing: Vec<SymbolRelation>,
    preview: SourcePreview,
    trail: Vec<String>,
    index: DemoIndex,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoIndex {
    symbol_count: i64,
    file_count: i64,
    reference_count: i64,
    confidence: DemoConfidence,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoConfidence {
    level: String,
    index_completeness: f64,
    semantic_basis: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SymbolHit {
    fq_name: String,
    simple_name: String,
    kind: Option<String>,
    path: Option<String>,
    declaration_offset: Option<i64>,
    module_path: Option<String>,
    incoming_references: i64,
    outgoing_references: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SymbolDetail {
    fq_name: String,
    simple_name: String,
    kind: Option<String>,
    visibility: Option<String>,
    path: Option<String>,
    declaration_offset: Option<i64>,
    module_path: Option<String>,
    source_set: Option<String>,
    incoming_references: i64,
    outgoing_references: i64,
    by_edge_kind: BTreeMap<String, i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SymbolRelation {
    direction: &'static str,
    fq_name: Option<String>,
    label: String,
    simple_name: String,
    path: Option<String>,
    offset: Option<i64>,
    edge_kind: String,
    references: i64,
    module_path: Option<String>,
    source_set: Option<String>,
    walkable: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SourcePreview {
    title: String,
    path: Option<String>,
    focused_line: Option<usize>,
    lines: Vec<PreviewLine>,
    message: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PreviewLine {
    number: usize,
    text: String,
    highlighted: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompareDemoResponse {
    ok: bool,
    snapshot: CompareSnapshot,
    schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompareSnapshot {
    mode: &'static str,
    workspace_root: String,
    database: String,
    query: String,
    view_mode: CompareViewMode,
    sort: CompareSort,
    filters: CompareFilterSnapshot,
    left_pane: ComparePaneSnapshot,
    right_pane: ComparePaneSnapshot,
    diff_buckets: CompareDiffBuckets,
    selection: CompareSelection,
    preview: SourcePreview,
    index: DemoIndex,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum CompareViewMode {
    Full,
    Difference,
}

impl CompareViewMode {
    fn toggle(self) -> Self {
        match self {
            Self::Full => Self::Difference,
            Self::Difference => Self::Full,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum CompareSort {
    Module,
    Visibility,
    Kind,
    Alphabetical,
}

impl CompareSort {
    fn next(self) -> Self {
        match self {
            Self::Module => Self::Visibility,
            Self::Visibility => Self::Kind,
            Self::Kind => Self::Alphabetical,
            Self::Alphabetical => Self::Module,
        }
    }

    fn previous(self) -> Self {
        match self {
            Self::Module => Self::Alphabetical,
            Self::Visibility => Self::Module,
            Self::Kind => Self::Visibility,
            Self::Alphabetical => Self::Kind,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum CompareBadge {
    Common,
    LexicalOnly,
    SemanticOnly,
    FilteredOut,
}

#[derive(Debug, Clone, Default)]
struct CompareFilters {
    kind: Option<String>,
    visibility: Option<String>,
    source_set: Option<String>,
    module: Option<String>,
    relation: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompareFilterSnapshot {
    chips: Vec<CompareFilterChip>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompareFilterChip {
    key: &'static str,
    label: &'static str,
    selected: String,
    options: Vec<String>,
    color: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ComparePaneSnapshot {
    title: &'static str,
    rows: Vec<CompareRow>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompareRow {
    id: String,
    label: String,
    fq_name: Option<String>,
    kind: Option<String>,
    visibility: Option<String>,
    path: Option<String>,
    module_path: Option<String>,
    source_set: Option<String>,
    relation_kinds: Vec<String>,
    incoming_references: i64,
    outgoing_references: i64,
    group_path: Vec<String>,
    depth: usize,
    badge: CompareBadge,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompareDiffBuckets {
    lexical_only: Vec<CompareRow>,
    semantic_only: Vec<CompareRow>,
    filtered_out: Vec<CompareRow>,
    common_count: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompareSelection {
    pane: &'static str,
    row: usize,
    fq_name: Option<String>,
    label: Option<String>,
}

struct DemoDatabase {
    request: DemoRequest,
    conn: Connection,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DemoPane {
    Search,
    Incoming,
    Outgoing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum InputMode {
    Navigate,
    Search,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CompareFocus {
    Search,
    Filters,
    Sort,
    Lexical,
    Semantic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ComparePane {
    Lexical,
    Semantic,
}

impl ComparePane {
    fn as_str(self) -> &'static str {
        match self {
            Self::Lexical => "lexical",
            Self::Semantic => "semantic",
        }
    }
}

struct CompareSnapshotRequest<'a> {
    query: &'a str,
    filters: &'a CompareFilters,
    sort: CompareSort,
    view_mode: CompareViewMode,
    requested_symbol: Option<&'a str>,
    selected_lexical: usize,
    selected_semantic: usize,
    active_pane: ComparePane,
}

struct CompareApp {
    db: DemoDatabase,
    request: DemoRequest,
    query: String,
    filters: CompareFilters,
    sort: CompareSort,
    view_mode: CompareViewMode,
    snapshot: CompareSnapshot,
    focus: CompareFocus,
    active_filter: usize,
    selected_lexical: usize,
    selected_semantic: usize,
    message: String,
    should_quit: bool,
}

struct DemoApp {
    db: DemoDatabase,
    request: DemoRequest,
    search_query: String,
    search_results: Vec<SymbolHit>,
    current: Option<SymbolDetail>,
    incoming: Vec<SymbolRelation>,
    outgoing: Vec<SymbolRelation>,
    preview: SourcePreview,
    index: DemoIndex,
    trail: Vec<String>,
    focus: DemoPane,
    input_mode: InputMode,
    selected_search: usize,
    selected_incoming: usize,
    selected_outgoing: usize,
    message: String,
    should_quit: bool,
}

pub fn run(args: DemoArgs) -> Result<i32> {
    match args.view {
        DemoView::Compare => run_compare_demo(args),
        DemoView::Symbol => run_symbol_demo(args),
    }
}

fn run_compare_demo(args: DemoArgs) -> Result<i32> {
    let request = DemoRequest::from_args(args)?;
    let mut db = DemoDatabase::open(request.clone())?;
    let initial_query = request
        .query
        .clone()
        .or_else(|| {
            request
                .symbol
                .as_deref()
                .map(simple_symbol_name)
                .map(str::to_string)
        })
        .unwrap_or_default();
    let snapshot = db.compare_snapshot(CompareSnapshotRequest {
        query: &initial_query,
        filters: &CompareFilters::default(),
        sort: CompareSort::Module,
        view_mode: CompareViewMode::Full,
        requested_symbol: request.symbol.as_deref(),
        selected_lexical: 0,
        selected_semantic: 0,
        active_pane: ComparePane::Semantic,
    })?;

    if request.json || !io::stdout().is_terminal() {
        return print_compare_json_snapshot(snapshot);
    }

    run_compare_tui(CompareApp::from_snapshot(db, request, snapshot))
}

fn run_symbol_demo(args: DemoArgs) -> Result<i32> {
    let request = DemoRequest::from_args(args)?;
    let mut db = DemoDatabase::open(request.clone())?;
    let snapshot = db.snapshot(
        request.symbol.as_deref(),
        request.query.as_deref().unwrap_or_default(),
        Vec::new(),
    )?;

    if request.json || !io::stdout().is_terminal() {
        return print_json_snapshot(snapshot);
    }

    run_demo_tui(DemoApp::from_snapshot(db, request, snapshot))
}

impl DemoRequest {
    fn from_args(args: DemoArgs) -> Result<Self> {
        let workspace_root = config::normalize(args.workspace_root.unwrap_or(env::current_dir()?));
        let database = args
            .database
            .map(config::normalize)
            .unwrap_or(config::workspace_database_path(&workspace_root)?);
        Ok(Self {
            workspace_root,
            database,
            symbol: args.symbol,
            query: args.query,
            limit: args.limit,
            json: args.json,
        })
    }
}

impl DemoDatabase {
    fn open(request: DemoRequest) -> Result<Self> {
        if !request.database.is_file() {
            return Err(CliError::new(
                "DEMO_SOURCE_INDEX_MISSING",
                format!(
                    "No source-index database exists at {}. Run `kast up` for this workspace first.",
                    request.database.display()
                ),
            ));
        }
        let conn = Connection::open_with_flags(
            &request.database,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI,
        )
        .map_err(sql_error)?;
        source_index_db::configure_read_connection(&conn).map_err(sql_error)?;
        let db = Self { request, conn };
        if !db.schema_is_current()? {
            return Err(CliError::new(
                "DEMO_SOURCE_INDEX_STALE",
                format!(
                    "source-index schema at {} is missing or not version {}",
                    db.request.database.display(),
                    SOURCE_INDEX_SCHEMA_VERSION
                ),
            ));
        }
        Ok(db)
    }

    fn snapshot(
        &mut self,
        requested_symbol: Option<&str>,
        query: &str,
        trail: Vec<String>,
    ) -> Result<DemoSnapshot> {
        let search_results = self.search(query, self.request.limit)?;
        let current_name = requested_symbol
            .map(str::to_string)
            .or_else(|| search_results.first().map(|hit| hit.fq_name.clone()));
        let current = current_name
            .as_deref()
            .map(|symbol| self.symbol_detail(symbol))
            .transpose()?
            .flatten();
        let incoming = current
            .as_ref()
            .map(|symbol| self.incoming_relations(&symbol.fq_name, self.request.limit))
            .transpose()?
            .unwrap_or_default();
        let outgoing = current
            .as_ref()
            .map(|symbol| self.outgoing_relations(&symbol.fq_name, self.request.limit))
            .transpose()?
            .unwrap_or_default();
        let preview = current
            .as_ref()
            .map(|symbol| {
                SourcePreview::from_location(
                    symbol.path.as_deref(),
                    symbol.declaration_offset,
                    format!("Declaration: {}", symbol.simple_name),
                )
            })
            .unwrap_or_else(|| SourcePreview::message("No symbol selected"));
        Ok(DemoSnapshot {
            mode: "symbolWalk",
            workspace_root: self.request.workspace_root.display().to_string(),
            database: self.request.database.display().to_string(),
            query: query.to_string(),
            current,
            search_results,
            incoming,
            outgoing,
            preview,
            trail,
            index: self.index()?,
        })
    }

    fn compare_snapshot(&mut self, request: CompareSnapshotRequest<'_>) -> Result<CompareSnapshot> {
        let mut lexical_rows = self.lexical_compare_rows(request.query, self.request.limit)?;
        let mut semantic_rows = self.semantic_compare_rows(request.query, self.request.limit)?;
        let mut semantic_filtered = apply_compare_filters(&semantic_rows, request.filters);
        sort_compare_rows(&mut lexical_rows, request.sort);
        sort_compare_rows(&mut semantic_rows, request.sort);
        sort_compare_rows(&mut semantic_filtered, request.sort);

        let diff_buckets =
            build_compare_diff_buckets(&lexical_rows, &semantic_rows, &semantic_filtered);
        apply_compare_badges(&mut lexical_rows, &semantic_rows, true);
        apply_compare_badges(&mut semantic_filtered, &lexical_rows, false);

        let (left_rows, right_rows) = match request.view_mode {
            CompareViewMode::Full => (lexical_rows.clone(), semantic_filtered.clone()),
            CompareViewMode::Difference => {
                let mut right = diff_buckets.semantic_only.clone();
                right.extend(diff_buckets.filtered_out.clone());
                (diff_buckets.lexical_only.clone(), right)
            }
        };
        let selected_semantic = request
            .selected_semantic
            .min(right_rows.len().saturating_sub(1));
        let selected_lexical = request
            .selected_lexical
            .min(left_rows.len().saturating_sub(1));
        let selected = selected_compare_row(
            request.requested_symbol,
            &left_rows,
            &right_rows,
            selected_lexical,
            selected_semantic,
            request.active_pane,
        );
        let selected_row = selected.map(|(_, _, row)| row);
        let preview = selected_row
            .map(|row| {
                SourcePreview::from_location(
                    row.path.as_deref(),
                    None,
                    format!("Compare: {}", row.label),
                )
            })
            .unwrap_or_else(|| SourcePreview::message("No compare row selected"));
        let selection = CompareSelection {
            pane: selected
                .map(|(pane, _, _)| pane.as_str())
                .unwrap_or_else(|| request.active_pane.as_str()),
            row: selected.map(|(_, index, _)| index).unwrap_or(0),
            fq_name: selected_row.and_then(|row| row.fq_name.clone()),
            label: selected_row.map(|row| row.label.clone()),
        };

        Ok(CompareSnapshot {
            mode: "searchCompare",
            workspace_root: self.request.workspace_root.display().to_string(),
            database: self.request.database.display().to_string(),
            query: request.query.to_string(),
            view_mode: request.view_mode,
            sort: request.sort,
            filters: compare_filter_snapshot(request.filters, &semantic_rows),
            left_pane: ComparePaneSnapshot {
                title: "Lexical index",
                rows: left_rows,
            },
            right_pane: ComparePaneSnapshot {
                title: "Kast semantic",
                rows: right_rows,
            },
            diff_buckets,
            selection,
            preview,
            index: self.index()?,
        })
    }

    fn index(&self) -> Result<DemoIndex> {
        Ok(DemoIndex {
            symbol_count: self.count_rows("fq_names")?,
            file_count: self.count_rows("file_manifest")?,
            reference_count: self.count_rows("symbol_references")?,
            confidence: self.current_confidence()?,
        })
    }

    fn search(&self, query: &str, limit: usize) -> Result<Vec<SymbolHit>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let names = if query.trim().is_empty() {
            self.popular_symbols(limit)?
        } else {
            self.search_symbol_names(query, limit)?
        };
        names
            .into_iter()
            .map(|name| self.symbol_hit(&name))
            .collect()
    }

    fn semantic_compare_rows(&self, query: &str, limit: usize) -> Result<Vec<CompareRow>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let names = if query.trim().is_empty() {
            self.popular_symbols(limit)?
        } else {
            self.search_symbol_names(query, limit)?
        };
        let mut rows = Vec::new();
        for name in names {
            if let Some(detail) = self.symbol_detail(&name)?
                && detail.kind.is_some()
            {
                rows.push(compare_row_from_detail(detail, CompareBadge::Common));
            }
        }
        Ok(rows)
    }

    fn lexical_compare_rows(&self, query: &str, limit: usize) -> Result<Vec<CompareRow>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut rows = Vec::new();
        let mut seen = BTreeSet::new();
        for row in self.semantic_compare_rows(query, limit)? {
            seen.insert(compare_row_key(&row));
            rows.push(row);
        }
        if query.trim().is_empty() || rows.len() >= limit {
            return Ok(rows);
        }

        let needle = source_index_db::escape_like(&query.to_lowercase());
        let pattern = format!("%{needle}%");
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT paths.identifier,
                       prefixes.dir_path,
                       paths.filename,
                       metadata.module_path,
                       metadata.source_set
                FROM identifier_paths paths
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = paths.prefix_id
                LEFT JOIN file_metadata metadata
                  ON metadata.prefix_id = paths.prefix_id
                 AND metadata.filename = paths.filename
                WHERE LOWER(paths.identifier) LIKE ? ESCAPE '\'
                ORDER BY LENGTH(paths.identifier),
                         paths.identifier,
                         COALESCE(prefixes.dir_path, ''),
                         paths.filename
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        let candidates = stmt
            .query_map(params![pattern, limit as i64], |row| {
                let dir = row.get::<_, Option<String>>(1)?.unwrap_or_default();
                let filename: String = row.get(2)?;
                Ok((
                    row.get::<_, String>(0)?,
                    self.compose_path(dir, filename),
                    row.get::<_, Option<String>>(3)?,
                    row.get::<_, Option<String>>(4)?,
                ))
            })
            .map_err(sql_error)?;
        for candidate in candidates {
            let (identifier, path, module_path, source_set) = candidate.map_err(sql_error)?;
            let row = CompareRow {
                id: format!("lexical:{path}:{identifier}"),
                label: identifier,
                fq_name: None,
                kind: None,
                visibility: None,
                path: Some(path),
                module_path,
                source_set,
                relation_kinds: Vec::new(),
                incoming_references: 0,
                outgoing_references: 0,
                group_path: Vec::new(),
                depth: 0,
                badge: CompareBadge::LexicalOnly,
            };
            if seen.insert(compare_row_key(&row)) {
                rows.push(row);
            }
            if rows.len() == limit {
                break;
            }
        }
        Ok(rows)
    }

    fn search_symbol_names(&self, query: &str, limit: usize) -> Result<Vec<String>> {
        let query = query.trim();
        let mut values = self.exact_symbol_match(query, limit)?;
        if values.len() < limit {
            let seen: BTreeSet<_> = values.iter().cloned().collect();
            let matches = if source_index_db::is_short_trigram_query(query) {
                self.short_symbol_matches(query, limit)?
            } else {
                self.fts_symbol_matches(query, limit)?
            };
            for name in matches {
                if !seen.contains(&name) {
                    values.push(name);
                }
                if values.len() == limit {
                    break;
                }
            }
        }
        Ok(values)
    }

    fn popular_symbols(&self, limit: usize) -> Result<Vec<String>> {
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
        let mut values = string_column(stmt.query_map(params![limit as i64], |row| row.get(0)))?;
        if values.is_empty() {
            let mut fallback = self
                .conn
                .prepare("SELECT fq_name FROM fq_names ORDER BY fq_name ASC LIMIT ?")
                .map_err(sql_error)?;
            values = string_column(fallback.query_map(params![limit as i64], |row| row.get(0)))?;
        }
        Ok(values)
    }

    fn exact_symbol_match(&self, query: &str, limit: usize) -> Result<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT fq_name
                FROM fq_names
                WHERE fq_name = ?
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
    }

    fn short_symbol_matches(&self, query: &str, limit: usize) -> Result<Vec<String>> {
        let needle = source_index_db::escape_like(&query.to_lowercase());
        let fq_prefix = format!("{needle}%");
        let segment_prefix = format!("%.{}%", needle);
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT fq_name
                FROM fq_names
                WHERE LOWER(fq_name) LIKE ? ESCAPE '\'
                   OR LOWER(fq_name) LIKE ? ESCAPE '\'
                ORDER BY
                    CASE
                        WHEN LOWER(fq_name) LIKE ? ESCAPE '\' THEN 0
                        ELSE 1
                    END,
                    LENGTH(fq_name),
                    fq_name
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(
            params![fq_prefix, segment_prefix, fq_prefix, limit as i64],
            |row| row.get(0),
        ))
    }

    fn fts_symbol_matches(&self, query: &str, limit: usize) -> Result<Vec<String>> {
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
    }

    fn symbol_hit(&self, fq_name: &str) -> Result<SymbolHit> {
        let detail = self.symbol_detail(fq_name)?;
        Ok(detail
            .map(|detail| SymbolHit {
                fq_name: detail.fq_name,
                simple_name: detail.simple_name,
                kind: detail.kind,
                path: detail.path,
                declaration_offset: detail.declaration_offset,
                module_path: detail.module_path,
                incoming_references: detail.incoming_references,
                outgoing_references: detail.outgoing_references,
            })
            .unwrap_or_else(|| SymbolHit {
                fq_name: fq_name.to_string(),
                simple_name: simple_symbol_name(fq_name).to_string(),
                kind: None,
                path: None,
                declaration_offset: None,
                module_path: None,
                incoming_references: 0,
                outgoing_references: 0,
            }))
    }

    fn symbol_detail(&self, fq_name: &str) -> Result<Option<SymbolDetail>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset,
                       declarations.module_path,
                       declarations.source_set
                FROM fq_names names
                LEFT JOIN declarations ON declarations.fq_id = names.fq_id
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE names.fq_name = ?
                ORDER BY
                    CASE declarations.kind
                        WHEN 'CLASS' THEN 0
                        WHEN 'OBJECT' THEN 1
                        WHEN 'INTERFACE' THEN 2
                        WHEN 'FUNCTION' THEN 3
                        WHEN 'PROPERTY' THEN 4
                        ELSE 5
                    END,
                    COALESCE(declarations.filename, '') ASC
                LIMIT 1
                "#,
            )
            .map_err(sql_error)?;
        stmt.query_row(params![fq_name], |row| {
            let fq_name = row.get::<_, String>(0)?;
            Ok(SymbolDetail {
                simple_name: simple_symbol_name(&fq_name).to_string(),
                kind: row.get(1)?,
                visibility: row.get(2)?,
                path: self.nullable_path(row, 3, 4)?,
                declaration_offset: row.get(5)?,
                module_path: row.get(6)?,
                source_set: row.get(7)?,
                incoming_references: self.reference_count_for_target(&fq_name).unwrap_or(0),
                outgoing_references: self.reference_count_for_source(&fq_name).unwrap_or(0),
                by_edge_kind: self.edge_breakdown_for_target(&fq_name).unwrap_or_default(),
                fq_name,
            })
        })
        .optional()
        .map_err(sql_error)
    }

    fn incoming_relations(&self, fq_name: &str, limit: usize) -> Result<Vec<SymbolRelation>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT source_name.fq_name,
                       source_prefix.dir_path,
                       refs.src_filename,
                       MIN(refs.source_offset) AS first_offset,
                       refs.edge_kind,
                       COUNT(*) AS reference_count,
                       source_meta.module_path,
                       source_meta.source_set
                FROM symbol_references refs
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                LEFT JOIN fq_names source_name ON source_name.fq_id = refs.source_fq_id
                LEFT JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                LEFT JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                WHERE target_name.fq_name = ?
                GROUP BY source_name.fq_name,
                         refs.src_prefix_id,
                         refs.src_filename,
                         refs.edge_kind,
                         source_meta.module_path,
                         source_meta.source_set
                ORDER BY reference_count DESC,
                         COALESCE(source_name.fq_name, '') ASC,
                         COALESCE(source_prefix.dir_path, '') ASC,
                         refs.src_filename ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        collect_relations(stmt.query_map(params![fq_name, limit as i64], |row| {
            self.relation_row(row, "incoming")
        }))
    }

    fn outgoing_relations(&self, fq_name: &str, limit: usize) -> Result<Vec<SymbolRelation>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT target_name.fq_name,
                       target_prefix.dir_path,
                       refs.tgt_filename,
                       MIN(refs.target_offset) AS first_offset,
                       refs.edge_kind,
                       COUNT(*) AS reference_count,
                       target_meta.module_path,
                       target_meta.source_set
                FROM symbol_references refs
                JOIN fq_names source_name ON source_name.fq_id = refs.source_fq_id
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                LEFT JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                LEFT JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                WHERE source_name.fq_name = ?
                GROUP BY target_name.fq_name,
                         refs.tgt_prefix_id,
                         refs.tgt_filename,
                         refs.edge_kind,
                         target_meta.module_path,
                         target_meta.source_set
                ORDER BY reference_count DESC,
                         target_name.fq_name ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        collect_relations(stmt.query_map(params![fq_name, limit as i64], |row| {
            self.relation_row(row, "outgoing")
        }))
    }

    fn relation_row(
        &self,
        row: &Row<'_>,
        direction: &'static str,
    ) -> rusqlite::Result<SymbolRelation> {
        let fq_name: Option<String> = row.get(0)?;
        let path = self.nullable_path(row, 1, 2)?;
        let fallback_label = path
            .as_deref()
            .map(simple_file_name)
            .unwrap_or("unknown source")
            .to_string();
        let label = fq_name.clone().unwrap_or(fallback_label);
        let simple_name = simple_symbol_name(&label).to_string();
        Ok(SymbolRelation {
            direction,
            fq_name: fq_name.clone(),
            label,
            simple_name,
            path,
            offset: row.get(3)?,
            edge_kind: row.get(4)?,
            references: row.get(5)?,
            module_path: row.get(6)?,
            source_set: row.get(7)?,
            walkable: fq_name.is_some(),
        })
    }

    fn reference_count_for_target(&self, fq_name: &str) -> Result<i64> {
        self.conn
            .query_row(
                r#"
                SELECT COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                WHERE names.fq_name = ?
                "#,
                params![fq_name],
                |row| row.get(0),
            )
            .map_err(sql_error)
    }

    fn reference_count_for_source(&self, fq_name: &str) -> Result<i64> {
        self.conn
            .query_row(
                r#"
                SELECT COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.source_fq_id
                WHERE names.fq_name = ?
                "#,
                params![fq_name],
                |row| row.get(0),
            )
            .map_err(sql_error)
    }

    fn edge_breakdown_for_target(&self, fq_name: &str) -> Result<BTreeMap<String, i64>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                WHERE names.fq_name = ?
                GROUP BY refs.edge_kind
                ORDER BY COUNT(*) DESC, refs.edge_kind ASC
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
            let (kind, count) = row.map_err(sql_error)?;
            values.insert(kind, count);
        }
        Ok(values)
    }

    fn current_confidence(&self) -> Result<DemoConfidence> {
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
        Ok(DemoConfidence {
            level: level.to_string(),
            index_completeness,
            semantic_basis: semantic_basis.to_string(),
        })
    }

    fn schema_is_current(&self) -> Result<bool> {
        let version = self
            .conn
            .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
                row.get::<_, i64>(0)
            })
            .optional()
            .map_err(sql_error)?;
        Ok(version == Some(SOURCE_INDEX_SCHEMA_VERSION)
            && self.required_tables_exist()?
            && source_index_db::persistent_symbol_fts_exists(&self.conn).map_err(sql_error)?)
    }

    fn required_tables_exist(&self) -> Result<bool> {
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
                .optional()
                .map_err(sql_error)?
                .unwrap_or(false);
            if !exists {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn count_rows(&self, table_name: &str) -> Result<i64> {
        self.conn
            .query_row(&format!("SELECT COUNT(*) FROM {table_name}"), [], |row| {
                row.get(0)
            })
            .map_err(sql_error)
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
                .fold(self.request.workspace_root.clone(), |path, segment| {
                    path.join(segment)
                })
                .join(filename)
        };
        config::normalize(path).display().to_string()
    }
}

impl DemoApp {
    fn from_snapshot(db: DemoDatabase, request: DemoRequest, snapshot: DemoSnapshot) -> Self {
        Self {
            db,
            request,
            search_query: snapshot.query,
            search_results: snapshot.search_results,
            current: snapshot.current,
            incoming: snapshot.incoming,
            outgoing: snapshot.outgoing,
            preview: snapshot.preview,
            index: snapshot.index,
            trail: snapshot.trail,
            focus: DemoPane::Incoming,
            input_mode: InputMode::Navigate,
            selected_search: 0,
            selected_incoming: 0,
            selected_outgoing: 0,
            message: "Enter walks into a symbol. / searches. Tab changes pane. b goes back."
                .to_string(),
            should_quit: false,
        }
    }

    fn on_key(&mut self, key: KeyEvent) -> Result<()> {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            self.should_quit = true;
            return Ok(());
        }
        match self.input_mode {
            InputMode::Search => self.on_search_key(key),
            InputMode::Navigate => self.on_navigation_key(key),
        }
    }

    fn on_search_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Esc => {
                self.input_mode = InputMode::Navigate;
                self.message = "Search cancelled".to_string();
            }
            KeyCode::Enter => {
                self.input_mode = InputMode::Navigate;
                self.focus = DemoPane::Search;
                self.activate_selection()?;
            }
            KeyCode::Backspace => {
                self.search_query.pop();
                self.refresh_search()?;
            }
            KeyCode::Char(value) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.search_query.push(value);
                self.refresh_search()?;
            }
            KeyCode::Up => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), -1);
                self.refresh_preview();
            }
            KeyCode::Down => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), 1);
                self.refresh_preview();
            }
            _ => {}
        }
        Ok(())
    }

    fn on_navigation_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc => self.should_quit = true,
            KeyCode::Char('/') => {
                self.search_query.clear();
                self.selected_search = 0;
                self.refresh_search()?;
                self.input_mode = InputMode::Search;
                self.focus = DemoPane::Search;
                self.message = "Type to search; Enter opens the selected symbol".to_string();
            }
            KeyCode::Tab => {
                self.focus = self.focus.next();
                self.refresh_preview();
            }
            KeyCode::BackTab => {
                self.focus = self.focus.previous();
                self.refresh_preview();
            }
            KeyCode::Left | KeyCode::Char('h') => {
                self.focus = self.focus.previous();
                self.refresh_preview();
            }
            KeyCode::Right | KeyCode::Char('l') => {
                self.focus = self.focus.next();
                self.refresh_preview();
            }
            KeyCode::Up | KeyCode::Char('k') => self.move_selection(-1),
            KeyCode::Down | KeyCode::Char('j') => self.move_selection(1),
            KeyCode::Enter => self.activate_selection()?,
            KeyCode::Char('b') | KeyCode::Backspace => self.back()?,
            KeyCode::Char('r') => self.reload_current()?,
            _ => {}
        }
        Ok(())
    }

    fn refresh_search(&mut self) -> Result<()> {
        self.search_results = self.db.search(&self.search_query, self.request.limit)?;
        self.selected_search = self
            .selected_search
            .min(self.search_results.len().saturating_sub(1));
        self.message = format!("{} symbol matches", self.search_results.len());
        self.refresh_preview();
        Ok(())
    }

    fn move_selection(&mut self, delta: isize) {
        match self.focus {
            DemoPane::Search => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), delta);
            }
            DemoPane::Incoming => {
                self.selected_incoming =
                    move_index(self.selected_incoming, self.incoming.len(), delta);
            }
            DemoPane::Outgoing => {
                self.selected_outgoing =
                    move_index(self.selected_outgoing, self.outgoing.len(), delta);
            }
        }
        self.refresh_preview();
    }

    fn activate_selection(&mut self) -> Result<()> {
        match self.focus {
            DemoPane::Search => {
                if let Some(hit) = self.search_results.get(self.selected_search) {
                    self.open_symbol(&hit.fq_name.clone(), true)?;
                }
            }
            DemoPane::Incoming => {
                let relation = self.incoming.get(self.selected_incoming).cloned();
                self.open_relation(relation)?;
            }
            DemoPane::Outgoing => {
                let relation = self.outgoing.get(self.selected_outgoing).cloned();
                self.open_relation(relation)?;
            }
        }
        Ok(())
    }

    fn open_relation(&mut self, relation: Option<SymbolRelation>) -> Result<()> {
        let Some(relation) = relation else {
            self.message = "No relation selected".to_string();
            return Ok(());
        };
        if let Some(symbol) = relation.fq_name {
            self.open_symbol(&symbol, true)
        } else {
            self.preview = SourcePreview::from_location(
                relation.path.as_deref(),
                relation.offset,
                format!("{} reference", relation.edge_kind),
            );
            self.message = "This row is file-level only; no source symbol was indexed".to_string();
            Ok(())
        }
    }

    fn open_symbol(&mut self, fq_name: &str, push_current: bool) -> Result<()> {
        if push_current
            && let Some(current) = &self.current
            && current.fq_name != fq_name
        {
            self.trail.push(current.fq_name.clone());
            if self.trail.len() > 10 {
                self.trail.remove(0);
            }
        }
        self.load_symbol(fq_name)?;
        self.focus = DemoPane::Incoming;
        Ok(())
    }

    fn load_symbol(&mut self, fq_name: &str) -> Result<()> {
        let Some(detail) = self.db.symbol_detail(fq_name)? else {
            self.message = format!("Symbol not found in source-index.db: {fq_name}");
            return Ok(());
        };
        self.incoming = self
            .db
            .incoming_relations(&detail.fq_name, self.request.limit)?;
        self.outgoing = self
            .db
            .outgoing_relations(&detail.fq_name, self.request.limit)?;
        self.current = Some(detail);
        self.selected_incoming = 0;
        self.selected_outgoing = 0;
        self.index = self.db.index()?;
        self.refresh_preview();
        if let Some(current) = &self.current {
            self.message = format!(
                "{}: {} incoming, {} outgoing",
                current.simple_name, current.incoming_references, current.outgoing_references
            );
        }
        Ok(())
    }

    fn back(&mut self) -> Result<()> {
        if let Some(symbol) = self.trail.pop() {
            self.load_symbol(&symbol)?;
            self.message = format!("Back to {symbol}");
        } else {
            self.message = "No previous symbol in this walk".to_string();
        }
        Ok(())
    }

    fn reload_current(&mut self) -> Result<()> {
        if let Some(symbol) = self.current.as_ref().map(|symbol| symbol.fq_name.clone()) {
            self.load_symbol(&symbol)?;
            self.message = "Reloaded source-index.db view".to_string();
        }
        Ok(())
    }

    fn refresh_preview(&mut self) {
        self.preview = match self.focus {
            DemoPane::Search => self
                .search_results
                .get(self.selected_search)
                .map(|hit| {
                    SourcePreview::from_location(
                        hit.path.as_deref(),
                        hit.declaration_offset,
                        format!("Search hit: {}", hit.simple_name),
                    )
                })
                .unwrap_or_else(|| SourcePreview::message("No search hit selected")),
            DemoPane::Incoming => self
                .incoming
                .get(self.selected_incoming)
                .map(|relation| {
                    SourcePreview::from_location(
                        relation.path.as_deref(),
                        relation.offset,
                        format!("Incoming: {}", relation.simple_name),
                    )
                })
                .or_else(|| self.current_preview())
                .unwrap_or_else(|| SourcePreview::message("No incoming reference selected")),
            DemoPane::Outgoing => self
                .outgoing
                .get(self.selected_outgoing)
                .map(|relation| {
                    SourcePreview::from_location(
                        relation.path.as_deref(),
                        relation.offset,
                        format!("Outgoing: {}", relation.simple_name),
                    )
                })
                .or_else(|| self.current_preview())
                .unwrap_or_else(|| SourcePreview::message("No outgoing reference selected")),
        };
    }

    fn current_preview(&self) -> Option<SourcePreview> {
        self.current.as_ref().map(|symbol| {
            SourcePreview::from_location(
                symbol.path.as_deref(),
                symbol.declaration_offset,
                format!("Declaration: {}", symbol.simple_name),
            )
        })
    }
}

impl CompareApp {
    fn from_snapshot(db: DemoDatabase, request: DemoRequest, snapshot: CompareSnapshot) -> Self {
        Self {
            db,
            request,
            query: snapshot.query.clone(),
            filters: CompareFilters::default(),
            sort: snapshot.sort,
            view_mode: snapshot.view_mode,
            snapshot,
            focus: CompareFocus::Search,
            active_filter: 0,
            selected_lexical: 0,
            selected_semantic: 0,
            message: "Type a query, Enter searches, Tab reaches filters, v toggles differences."
                .to_string(),
            should_quit: false,
        }
    }

    fn on_key(&mut self, key: KeyEvent) -> Result<()> {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            self.should_quit = true;
            return Ok(());
        }
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc if self.focus != CompareFocus::Search => {
                self.should_quit = true
            }
            KeyCode::Tab => self.focus = self.focus.next(),
            KeyCode::BackTab => self.focus = self.focus.previous(),
            KeyCode::Char('v') | KeyCode::Char('V') => {
                self.view_mode = self.view_mode.toggle();
                self.refresh_snapshot()?;
                self.message = format!("View mode: {:?}", self.view_mode);
            }
            _ => match self.focus {
                CompareFocus::Search => self.on_search_key(key)?,
                CompareFocus::Filters => self.on_filter_key(key)?,
                CompareFocus::Sort => self.on_sort_key(key)?,
                CompareFocus::Lexical => self.on_pane_key(key, true)?,
                CompareFocus::Semantic => self.on_pane_key(key, false)?,
            },
        }
        Ok(())
    }

    fn on_search_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Esc => self.should_quit = true,
            KeyCode::Enter => {
                self.selected_lexical = 0;
                self.selected_semantic = 0;
                self.refresh_snapshot()?;
                self.message = format!(
                    "{} lexical, {} semantic",
                    self.snapshot.left_pane.rows.len(),
                    self.snapshot.right_pane.rows.len()
                );
            }
            KeyCode::Backspace => {
                self.query.pop();
            }
            KeyCode::Char(value) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.query.push(value);
            }
            KeyCode::Down => self.focus = CompareFocus::Semantic,
            _ => {}
        }
        Ok(())
    }

    fn on_filter_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Left | KeyCode::Char('h') => {
                self.active_filter = self.active_filter.saturating_sub(1);
            }
            KeyCode::Right | KeyCode::Char('l') => {
                self.active_filter = (self.active_filter + 1).min(4);
            }
            KeyCode::Enter | KeyCode::Char(' ') => {
                self.cycle_active_filter()?;
            }
            KeyCode::Down => self.focus = CompareFocus::Lexical,
            _ => {}
        }
        Ok(())
    }

    fn on_sort_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Left | KeyCode::Char('h') => self.sort = self.sort.previous(),
            KeyCode::Right | KeyCode::Char('l') | KeyCode::Enter | KeyCode::Char(' ') => {
                self.sort = self.sort.next()
            }
            KeyCode::Down => self.focus = CompareFocus::Lexical,
            _ => {}
        }
        self.refresh_snapshot()
    }

    fn on_pane_key(&mut self, key: KeyEvent, lexical: bool) -> Result<()> {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => {
                self.move_selection(lexical, -1);
                self.refresh_snapshot()?;
            }
            KeyCode::Down | KeyCode::Char('j') => {
                self.move_selection(lexical, 1);
                self.refresh_snapshot()?;
            }
            KeyCode::Left | KeyCode::Char('h') if !lexical => {
                self.focus = CompareFocus::Lexical;
                self.refresh_snapshot()?;
            }
            KeyCode::Right | KeyCode::Char('l') if lexical => {
                self.focus = CompareFocus::Semantic;
                self.refresh_snapshot()?;
            }
            KeyCode::Enter => self.refresh_snapshot()?,
            _ => {}
        }
        Ok(())
    }

    fn cycle_active_filter(&mut self) -> Result<()> {
        let chips = &self.snapshot.filters.chips;
        let Some(chip) = chips.get(self.active_filter) else {
            return Ok(());
        };
        let current = chip.selected.as_str();
        let index = chip
            .options
            .iter()
            .position(|option| option == current)
            .unwrap_or(0);
        let next = chip.options[(index + 1) % chip.options.len()].clone();
        let value = if next == "any" { None } else { Some(next) };
        match chip.key {
            "kind" => self.filters.kind = value,
            "visibility" => self.filters.visibility = value,
            "sourceSet" => self.filters.source_set = value,
            "module" => self.filters.module = value,
            "relation" => self.filters.relation = value,
            _ => {}
        }
        self.selected_semantic = 0;
        self.refresh_snapshot()
    }

    fn move_selection(&mut self, lexical: bool, delta: isize) {
        if lexical {
            self.selected_lexical = move_index(
                self.selected_lexical,
                self.snapshot.left_pane.rows.len(),
                delta,
            );
        } else {
            self.selected_semantic = move_index(
                self.selected_semantic,
                self.snapshot.right_pane.rows.len(),
                delta,
            );
        }
    }

    fn refresh_snapshot(&mut self) -> Result<()> {
        self.snapshot = self.db.compare_snapshot(CompareSnapshotRequest {
            query: &self.query,
            filters: &self.filters,
            sort: self.sort,
            view_mode: self.view_mode,
            requested_symbol: None,
            selected_lexical: self.selected_lexical,
            selected_semantic: self.selected_semantic,
            active_pane: self.focus.compare_pane(),
        })?;
        self.selected_lexical = self
            .selected_lexical
            .min(self.snapshot.left_pane.rows.len().saturating_sub(1));
        self.selected_semantic = self
            .selected_semantic
            .min(self.snapshot.right_pane.rows.len().saturating_sub(1));
        Ok(())
    }
}

impl CompareFocus {
    fn next(self) -> Self {
        match self {
            Self::Search => Self::Filters,
            Self::Filters => Self::Sort,
            Self::Sort => Self::Lexical,
            Self::Lexical => Self::Semantic,
            Self::Semantic => Self::Search,
        }
    }

    fn previous(self) -> Self {
        match self {
            Self::Search => Self::Semantic,
            Self::Filters => Self::Search,
            Self::Sort => Self::Filters,
            Self::Lexical => Self::Sort,
            Self::Semantic => Self::Lexical,
        }
    }

    fn title(self) -> &'static str {
        match self {
            Self::Search => "search",
            Self::Filters => "filters",
            Self::Sort => "sort",
            Self::Lexical => "lexical",
            Self::Semantic => "semantic",
        }
    }

    fn compare_pane(self) -> ComparePane {
        match self {
            Self::Lexical => ComparePane::Lexical,
            Self::Search | Self::Filters | Self::Sort | Self::Semantic => ComparePane::Semantic,
        }
    }
}

impl DemoPane {
    fn next(self) -> Self {
        match self {
            Self::Search => Self::Incoming,
            Self::Incoming => Self::Outgoing,
            Self::Outgoing => Self::Search,
        }
    }

    fn previous(self) -> Self {
        match self {
            Self::Search => Self::Outgoing,
            Self::Incoming => Self::Search,
            Self::Outgoing => Self::Incoming,
        }
    }

    fn title(self) -> &'static str {
        match self {
            Self::Search => "search",
            Self::Incoming => "incoming",
            Self::Outgoing => "outgoing",
        }
    }
}

impl SourcePreview {
    fn from_location(path: Option<&str>, offset: Option<i64>, title: String) -> Self {
        let Some(path) = path else {
            return Self::message(format!(
                "{title}\nNo file path was recorded for this symbol."
            ));
        };
        let content = match fs::read_to_string(path) {
            Ok(content) => content,
            Err(error) => {
                return Self {
                    title,
                    path: Some(path.to_string()),
                    focused_line: None,
                    lines: Vec::new(),
                    message: Some(format!("Cannot read {}: {error}", compact_path(path))),
                };
            }
        };
        let focus = offset
            .filter(|value| *value >= 0)
            .map(|value| line_number_for_offset(&content, value as usize))
            .unwrap_or(1);
        let source_lines: Vec<&str> = if content.is_empty() {
            vec![""]
        } else {
            content.lines().collect()
        };
        let total = source_lines.len().max(1);
        let focused_line = focus.clamp(1, total);
        let start = focused_line.saturating_sub(PREVIEW_RADIUS + 1);
        let end = (focused_line + PREVIEW_RADIUS).min(total);
        let lines = source_lines[start..end]
            .iter()
            .enumerate()
            .map(|(index, text)| {
                let number = start + index + 1;
                PreviewLine {
                    number,
                    text: truncate_chars(text, 180),
                    highlighted: number == focused_line,
                }
            })
            .collect();
        Self {
            title,
            path: Some(path.to_string()),
            focused_line: Some(focused_line),
            lines,
            message: None,
        }
    }

    fn message(message: impl Into<String>) -> Self {
        Self {
            title: "Source preview".to_string(),
            path: None,
            focused_line: None,
            lines: Vec::new(),
            message: Some(message.into()),
        }
    }
}

fn run_demo_tui(mut app: DemoApp) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let result = run_demo_event_loop(&mut terminal, &mut app);
    disable_raw_mode().ok();
    execute!(terminal.backend_mut(), LeaveAlternateScreen).ok();
    terminal.show_cursor().ok();
    result
}

fn run_compare_tui(mut app: CompareApp) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let result = run_compare_event_loop(&mut terminal, &mut app);
    disable_raw_mode().ok();
    execute!(terminal.backend_mut(), LeaveAlternateScreen).ok();
    terminal.show_cursor().ok();
    result
}

fn run_demo_event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut DemoApp,
) -> Result<i32> {
    loop {
        terminal.draw(|frame| render_demo(frame, app))?;
        if app.should_quit {
            break Ok(0);
        }
        if event::poll(Duration::from_millis(120))?
            && let Event::Key(key) = event::read()?
        {
            app.on_key(key)?;
        }
    }
}

fn run_compare_event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut CompareApp,
) -> Result<i32> {
    loop {
        terminal.draw(|frame| render_compare_demo(frame, app))?;
        if app.should_quit {
            break Ok(0);
        }
        if event::poll(Duration::from_millis(120))?
            && let Event::Key(key) = event::read()?
        {
            app.on_key(key)?;
        }
    }
}

fn render_demo(frame: &mut Frame<'_>, app: &DemoApp) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(5),
            Constraint::Min(10),
            Constraint::Length(2),
        ])
        .split(frame.area());
    render_header(frame, root[0], app);
    render_body(frame, root[1], app);
    render_footer(frame, root[2], app);
}

fn render_compare_demo(frame: &mut Frame<'_>, app: &CompareApp) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(7),
            Constraint::Min(10),
            Constraint::Length(2),
        ])
        .split(frame.area());
    render_compare_header(frame, root[0], app);
    render_compare_body(frame, root[1], app);
    render_compare_footer(frame, root[2], app);
}

fn render_compare_header(frame: &mut Frame<'_>, area: Rect, app: &CompareApp) {
    let search_style = if app.focus == CompareFocus::Search {
        Style::default().fg(Color::Black).bg(Color::Cyan)
    } else {
        Style::default().fg(Color::Cyan)
    };
    let chips = app
        .snapshot
        .filters
        .chips
        .iter()
        .enumerate()
        .map(|(index, chip)| {
            let active = app.focus == CompareFocus::Filters && app.active_filter == index;
            Span::styled(
                format!(" {}:{} ", chip.label, chip.selected),
                Style::default()
                    .fg(compare_chip_color(chip.color))
                    .add_modifier(if active {
                        Modifier::REVERSED
                    } else {
                        Modifier::empty()
                    }),
            )
        })
        .collect::<Vec<_>>();
    let sort_style = if app.focus == CompareFocus::Sort {
        Style::default()
            .fg(Color::Black)
            .bg(Color::Yellow)
            .add_modifier(Modifier::BOLD)
    } else {
        Style::default().fg(Color::Yellow)
    };
    let lines = vec![
        Line::from(vec![
            Span::styled(
                "Kast Search Compare",
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
            Span::styled(format!(" search: {} ", app.query), search_style),
        ]),
        Line::from(chips),
        Line::from(vec![
            Span::styled("sort ", Style::default().fg(Color::DarkGray)),
            Span::styled(format!("{:?}", app.sort).to_lowercase(), sort_style),
            Span::raw(format!(
                "  view {:?}  common {}  lexical-only {}  semantic-only {}  filtered {}",
                app.view_mode,
                app.snapshot.diff_buckets.common_count,
                app.snapshot.diff_buckets.lexical_only.len(),
                app.snapshot.diff_buckets.semantic_only.len(),
                app.snapshot.diff_buckets.filtered_out.len()
            )),
        ]),
        Line::from(vec![
            Span::styled("focus ", Style::default().fg(Color::DarkGray)),
            Span::styled(app.focus.title(), Style::default().fg(Color::Green)),
            Span::raw(format!("  {}", app.message)),
        ]),
    ];
    frame.render_widget(
        Paragraph::new(lines).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::DarkGray)),
        ),
        area,
    );
}

fn render_header(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let current = app
        .current
        .as_ref()
        .map(|symbol| symbol.fq_name.as_str())
        .unwrap_or("no symbol");
    let lines = vec![
        Line::from(vec![
            Span::styled(
                "Kast Symbol Walk",
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
            Span::styled(
                current.to_string(),
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD),
            ),
        ]),
        Line::from(vec![
            Span::styled("focus ", Style::default().fg(Color::DarkGray)),
            Span::styled(app.focus.title(), Style::default().fg(Color::Green)),
            Span::raw(format!(
                "  symbols {}  files {}  refs {}  confidence {}",
                app.index.symbol_count,
                app.index.file_count,
                app.index.reference_count,
                app.index.confidence.level
            )),
        ]),
        Line::from(vec![Span::raw(&app.message)]),
    ];
    frame.render_widget(
        Paragraph::new(lines).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::DarkGray)),
        ),
        area,
    );
}

fn render_body(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    if area.width < 110 {
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Percentage(28),
                Constraint::Percentage(36),
                Constraint::Percentage(36),
            ])
            .split(area);
        render_search(frame, rows[0], app);
        render_symbol_and_relations(frame, rows[1], app);
        render_preview(frame, rows[2], app);
        return;
    }

    let columns = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage(28),
            Constraint::Percentage(38),
            Constraint::Percentage(34),
        ])
        .split(area);
    let left = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Percentage(72), Constraint::Percentage(28)])
        .split(columns[0]);
    render_search(frame, left[0], app);
    render_trail(frame, left[1], app);
    render_symbol_and_relations(frame, columns[1], app);
    render_preview(frame, columns[2], app);
}

fn render_compare_body(frame: &mut Frame<'_>, area: Rect, app: &CompareApp) {
    if area.width < 110 {
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Percentage(32),
                Constraint::Percentage(32),
                Constraint::Percentage(36),
            ])
            .split(area);
        render_compare_rows(
            frame,
            rows[0],
            &app.snapshot.left_pane,
            app.selected_lexical,
            app.focus == CompareFocus::Lexical,
            app.sort,
        );
        render_compare_rows(
            frame,
            rows[1],
            &app.snapshot.right_pane,
            app.selected_semantic,
            app.focus == CompareFocus::Semantic,
            app.sort,
        );
        render_source_preview(frame, rows[2], &app.snapshot.preview);
        return;
    }

    let columns = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage(32),
            Constraint::Percentage(34),
            Constraint::Percentage(34),
        ])
        .split(area);
    render_compare_rows(
        frame,
        columns[0],
        &app.snapshot.left_pane,
        app.selected_lexical,
        app.focus == CompareFocus::Lexical,
        app.sort,
    );
    render_compare_rows(
        frame,
        columns[1],
        &app.snapshot.right_pane,
        app.selected_semantic,
        app.focus == CompareFocus::Semantic,
        app.sort,
    );
    render_source_preview(frame, columns[2], &app.snapshot.preview);
}

fn render_compare_rows(
    frame: &mut Frame<'_>,
    area: Rect,
    pane: &ComparePaneSnapshot,
    selected: usize,
    focused: bool,
    sort: CompareSort,
) {
    let items: Vec<ListItem<'_>> = pane
        .rows
        .iter()
        .map(|row| {
            let indent = if sort == CompareSort::Module {
                "  ".repeat(row.depth.saturating_sub(1))
            } else {
                String::new()
            };
            ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{}{: <12}", indent, compare_badge_label(&row.badge)),
                    compare_badge_style(&row.badge),
                ),
                Span::styled(
                    row.label.clone(),
                    Style::default()
                        .fg(Color::White)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(format!(
                    "  {} {} {} in {} out {}",
                    row.kind.as_deref().unwrap_or("-"),
                    row.visibility.as_deref().unwrap_or("-"),
                    row.module_path.as_deref().unwrap_or("-"),
                    row.incoming_references,
                    row.outgoing_references
                )),
            ]))
        })
        .collect();
    render_list(
        frame,
        area,
        pane.title.to_string(),
        items,
        selected,
        focused,
    );
}

fn render_search(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let title = if app.input_mode == InputMode::Search {
        format!("/ {}", app.search_query)
    } else if app.search_query.is_empty() {
        "Symbols".to_string()
    } else {
        format!("Symbols matching {}", app.search_query)
    };
    let items: Vec<ListItem<'_>> = app
        .search_results
        .iter()
        .map(|hit| {
            let kind = hit.kind.as_deref().unwrap_or("SYMBOL");
            let module = hit.module_path.as_deref().unwrap_or("");
            ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{:<10}", compact_kind(kind)),
                    Style::default().fg(Color::Magenta),
                ),
                Span::styled(
                    hit.simple_name.clone(),
                    Style::default()
                        .fg(Color::White)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(format!(
                    "  in {} out {} {}",
                    hit.incoming_references, hit.outgoing_references, module
                )),
            ]))
        })
        .collect();
    render_list(
        frame,
        area,
        title,
        items,
        app.selected_search,
        app.focus == DemoPane::Search,
    );
}

fn render_compare_footer(frame: &mut Frame<'_>, area: Rect, app: &CompareApp) {
    let text = format!(
        "focus {} | type query | Enter search/apply | Tab focus | arrows select/cycle | v full/difference | q quit | db {}",
        app.focus.title(),
        compact_path(&app.request.database.display().to_string())
    );
    frame.render_widget(
        Paragraph::new(text)
            .block(Block::default().borders(Borders::TOP))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_trail(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let lines = if app.trail.is_empty() {
        vec![Line::from("No previous symbols yet")]
    } else {
        app.trail
            .iter()
            .rev()
            .map(|symbol| {
                Line::from(vec![
                    Span::styled(
                        simple_symbol_name(symbol).to_string(),
                        Style::default().fg(Color::Yellow),
                    ),
                    Span::raw(format!("  {}", compact_namespace(symbol))),
                ])
            })
            .collect()
    };
    frame.render_widget(
        Paragraph::new(lines)
            .block(Block::default().title("Walk Stack").borders(Borders::ALL))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_symbol_and_relations(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(8),
            Constraint::Percentage(46),
            Constraint::Percentage(46),
        ])
        .split(area);
    render_current_symbol(frame, rows[0], app);
    render_relations(
        frame,
        rows[1],
        "Incoming: who breaks if this changes",
        &app.incoming,
        app.selected_incoming,
        app.focus == DemoPane::Incoming,
    );
    render_relations(
        frame,
        rows[2],
        "Outgoing: what this symbol touches",
        &app.outgoing,
        app.selected_outgoing,
        app.focus == DemoPane::Outgoing,
    );
}

fn render_current_symbol(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let lines = app
        .current
        .as_ref()
        .map(|symbol| {
            vec![
                Line::from(vec![
                    Span::styled(
                        symbol.simple_name.clone(),
                        Style::default()
                            .fg(Color::Yellow)
                            .add_modifier(Modifier::BOLD),
                    ),
                    Span::raw(format!("  {}", symbol.kind.as_deref().unwrap_or("SYMBOL"))),
                ]),
                Line::from(symbol.fq_name.clone()),
                Line::from(format!(
                    "refs: {} incoming / {} outgoing",
                    symbol.incoming_references, symbol.outgoing_references
                )),
                Line::from(format!(
                    "module: {}  visibility: {}",
                    symbol.module_path.as_deref().unwrap_or("-"),
                    symbol.visibility.as_deref().unwrap_or("-")
                )),
                Line::from(format!("edges: {}", edge_summary(&symbol.by_edge_kind))),
            ]
        })
        .unwrap_or_else(|| vec![Line::from("No symbol selected")]);
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .title("Current Symbol")
                    .borders(Borders::ALL),
            )
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_relations(
    frame: &mut Frame<'_>,
    area: Rect,
    title: &str,
    relations: &[SymbolRelation],
    selected: usize,
    focused: bool,
) {
    let items: Vec<ListItem<'_>> = relations
        .iter()
        .map(|relation| {
            let walk_marker = if relation.walkable { ">" } else { "-" };
            ListItem::new(Line::from(vec![
                Span::styled(
                    format!("{walk_marker} {:<8}", compact_kind(&relation.edge_kind)),
                    Style::default().fg(Color::Green),
                ),
                Span::styled(
                    relation.simple_name.clone(),
                    Style::default()
                        .fg(Color::White)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(format!(
                    "  {} refs  {}",
                    relation.references,
                    relation
                        .path
                        .as_deref()
                        .map(simple_file_name)
                        .unwrap_or("-")
                )),
            ]))
        })
        .collect();
    render_list(frame, area, title.to_string(), items, selected, focused);
}

fn render_preview(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    render_source_preview(frame, area, &app.preview);
}

fn render_source_preview(frame: &mut Frame<'_>, area: Rect, preview: &SourcePreview) {
    let mut lines = Vec::new();
    if let Some(path) = &preview.path {
        lines.push(Line::from(vec![
            Span::styled(compact_path(path), Style::default().fg(Color::Yellow)),
            Span::raw(
                preview
                    .focused_line
                    .map(|line| format!(":{line}"))
                    .unwrap_or_default(),
            ),
        ]));
        lines.push(Line::from(""));
    }
    if let Some(message) = &preview.message {
        lines.extend(message.lines().map(|line| Line::from(line.to_string())));
    } else {
        for line in &preview.lines {
            let number_style = if line.highlighted {
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD)
            } else {
                Style::default().fg(Color::DarkGray)
            };
            let text_style = if line.highlighted {
                Style::default().fg(Color::Black).bg(Color::Yellow)
            } else {
                Style::default()
            };
            lines.push(Line::from(vec![
                Span::styled(format!("{:>5} | ", line.number), number_style),
                Span::styled(line.text.clone(), text_style),
            ]));
        }
    }
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .title(preview.title.clone())
                    .borders(Borders::ALL),
            )
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn render_footer(frame: &mut Frame<'_>, area: Rect, app: &DemoApp) {
    let mode = match app.input_mode {
        InputMode::Navigate => "navigate",
        InputMode::Search => "search",
    };
    let text = format!(
        "mode {mode} | / search | Tab pane | Enter walk/open | b back | r reload | q quit | db {}",
        compact_path(&app.request.database.display().to_string())
    );
    frame.render_widget(
        Paragraph::new(text)
            .block(Block::default().borders(Borders::TOP))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_list(
    frame: &mut Frame<'_>,
    area: Rect,
    title: String,
    items: Vec<ListItem<'_>>,
    selected: usize,
    focused: bool,
) {
    let border_style = if focused {
        Style::default().fg(Color::Cyan)
    } else {
        Style::default().fg(Color::DarkGray)
    };
    let mut state = ListState::default();
    if !items.is_empty() {
        state.select(Some(selected.min(items.len().saturating_sub(1))));
    }
    let list = List::new(items)
        .block(
            Block::default()
                .title(title)
                .borders(Borders::ALL)
                .border_style(border_style),
        )
        .highlight_style(
            Style::default()
                .fg(Color::Black)
                .bg(Color::Cyan)
                .add_modifier(Modifier::BOLD),
        )
        .highlight_symbol("> ");
    frame.render_stateful_widget(list, area, &mut state);
}

fn print_json_snapshot(snapshot: DemoSnapshot) -> Result<i32> {
    let response = DemoResponse {
        ok: true,
        snapshot,
        schema_version: SCHEMA_VERSION,
    };
    serde_json::to_writer_pretty(io::stdout(), &serde_json::to_value(response)?)?;
    println!();
    Ok(0)
}

fn print_compare_json_snapshot(snapshot: CompareSnapshot) -> Result<i32> {
    let response = CompareDemoResponse {
        ok: true,
        snapshot,
        schema_version: SCHEMA_VERSION,
    };
    serde_json::to_writer_pretty(io::stdout(), &serde_json::to_value(response)?)?;
    println!();
    Ok(0)
}

fn compare_row_from_detail(detail: SymbolDetail, badge: CompareBadge) -> CompareRow {
    let relation_kinds = detail.by_edge_kind.keys().cloned().collect();
    let path = detail.path.clone();
    let label = detail.simple_name.clone();
    let mut row = CompareRow {
        id: format!("symbol:{}", detail.fq_name),
        label,
        fq_name: Some(detail.fq_name),
        kind: detail.kind,
        visibility: detail.visibility,
        path,
        module_path: detail.module_path,
        source_set: detail.source_set,
        relation_kinds,
        incoming_references: detail.incoming_references,
        outgoing_references: detail.outgoing_references,
        group_path: Vec::new(),
        depth: 0,
        badge,
    };
    assign_compare_module_path(&mut row);
    row
}

fn apply_compare_filters(rows: &[CompareRow], filters: &CompareFilters) -> Vec<CompareRow> {
    rows.iter()
        .filter(|row| {
            filter_matches(&row.kind, &filters.kind)
                && filter_matches(&row.visibility, &filters.visibility)
                && filter_matches(&row.source_set, &filters.source_set)
                && filter_matches(&row.module_path, &filters.module)
                && filters
                    .relation
                    .as_ref()
                    .is_none_or(|relation| row.relation_kinds.iter().any(|kind| kind == relation))
        })
        .cloned()
        .collect()
}

fn filter_matches(value: &Option<String>, selected: &Option<String>) -> bool {
    selected
        .as_ref()
        .is_none_or(|selected| value.as_ref() == Some(selected))
}

fn build_compare_diff_buckets(
    lexical_rows: &[CompareRow],
    semantic_rows: &[CompareRow],
    semantic_filtered: &[CompareRow],
) -> CompareDiffBuckets {
    let lexical_keys: BTreeSet<_> = lexical_rows.iter().map(compare_row_key).collect();
    let semantic_keys: BTreeSet<_> = semantic_rows.iter().map(compare_row_key).collect();
    let filtered_keys: BTreeSet<_> = semantic_filtered.iter().map(compare_row_key).collect();

    let mut lexical_only: Vec<_> = lexical_rows
        .iter()
        .filter(|row| !semantic_keys.contains(&compare_row_key(row)))
        .cloned()
        .map(|mut row| {
            row.badge = CompareBadge::LexicalOnly;
            row
        })
        .collect();
    let mut semantic_only: Vec<_> = semantic_rows
        .iter()
        .filter(|row| !lexical_keys.contains(&compare_row_key(row)))
        .cloned()
        .map(|mut row| {
            row.badge = CompareBadge::SemanticOnly;
            row
        })
        .collect();
    let mut filtered_out: Vec<_> = semantic_rows
        .iter()
        .filter(|row| {
            let key = compare_row_key(row);
            lexical_keys.contains(&key) && !filtered_keys.contains(&key)
        })
        .cloned()
        .map(|mut row| {
            row.badge = CompareBadge::FilteredOut;
            row
        })
        .collect();
    sort_compare_rows(&mut lexical_only, CompareSort::Module);
    sort_compare_rows(&mut semantic_only, CompareSort::Module);
    sort_compare_rows(&mut filtered_out, CompareSort::Module);

    CompareDiffBuckets {
        lexical_only,
        semantic_only,
        filtered_out,
        common_count: lexical_keys.intersection(&semantic_keys).count(),
    }
}

fn selected_compare_row<'a>(
    requested_symbol: Option<&str>,
    left_rows: &'a [CompareRow],
    right_rows: &'a [CompareRow],
    selected_lexical: usize,
    selected_semantic: usize,
    active_pane: ComparePane,
) -> Option<(ComparePane, usize, &'a CompareRow)> {
    let requested = requested_symbol.and_then(|symbol| {
        right_rows
            .iter()
            .enumerate()
            .find(|(_, row)| row.fq_name.as_deref() == Some(symbol))
            .map(|(index, row)| (ComparePane::Semantic, index, row))
    });
    let lexical = left_rows
        .get(selected_lexical)
        .map(|row| (ComparePane::Lexical, selected_lexical, row));
    let semantic = right_rows
        .get(selected_semantic)
        .map(|row| (ComparePane::Semantic, selected_semantic, row));

    requested.or(match active_pane {
        ComparePane::Lexical => lexical.or(semantic),
        ComparePane::Semantic => semantic.or(lexical),
    })
}

fn apply_compare_badges(rows: &mut [CompareRow], other_rows: &[CompareRow], left_side: bool) {
    let other_keys: BTreeSet<_> = other_rows.iter().map(compare_row_key).collect();
    for row in rows {
        row.badge = if other_keys.contains(&compare_row_key(row)) {
            CompareBadge::Common
        } else if left_side {
            CompareBadge::LexicalOnly
        } else {
            CompareBadge::SemanticOnly
        };
    }
}

fn sort_compare_rows(rows: &mut [CompareRow], sort: CompareSort) {
    for row in rows.iter_mut() {
        assign_compare_module_path(row);
    }
    rows.sort_by(|left, right| match sort {
        CompareSort::Module => compare_module_tuple(left).cmp(&compare_module_tuple(right)),
        CompareSort::Visibility => compare_optional(&left.visibility, &right.visibility)
            .then_with(|| compare_module_tuple(left).cmp(&compare_module_tuple(right))),
        CompareSort::Kind => compare_optional(&left.kind, &right.kind)
            .then_with(|| compare_module_tuple(left).cmp(&compare_module_tuple(right))),
        CompareSort::Alphabetical => left
            .label
            .cmp(&right.label)
            .then_with(|| compare_row_key(left).cmp(&compare_row_key(right))),
    });
}

fn compare_optional(left: &Option<String>, right: &Option<String>) -> std::cmp::Ordering {
    left.as_deref()
        .unwrap_or("")
        .cmp(right.as_deref().unwrap_or(""))
}

fn compare_module_tuple(row: &CompareRow) -> (String, String, String, String) {
    (
        row.module_path.clone().unwrap_or_default(),
        row.source_set.clone().unwrap_or_default(),
        row.path
            .as_deref()
            .map(simple_file_name)
            .unwrap_or("")
            .to_string(),
        row.label.clone(),
    )
}

fn assign_compare_module_path(row: &mut CompareRow) {
    row.group_path = vec![
        row.module_path
            .clone()
            .unwrap_or_else(|| "workspace".to_string()),
        row.source_set.clone().unwrap_or_else(|| "main".to_string()),
        row.path
            .as_deref()
            .map(simple_file_name)
            .unwrap_or(&row.label)
            .to_string(),
    ];
    row.depth = row.group_path.len();
}

fn compare_row_key(row: &CompareRow) -> String {
    row.fq_name
        .clone()
        .unwrap_or_else(|| format!("lexical:{}", row.id))
}

fn compare_filter_snapshot(filters: &CompareFilters, rows: &[CompareRow]) -> CompareFilterSnapshot {
    CompareFilterSnapshot {
        chips: vec![
            compare_filter_chip(
                "kind",
                "Kind",
                &filters.kind,
                rows.iter().filter_map(|row| row.kind.clone()),
                "magenta",
            ),
            compare_filter_chip(
                "visibility",
                "Visibility",
                &filters.visibility,
                rows.iter().filter_map(|row| row.visibility.clone()),
                "yellow",
            ),
            compare_filter_chip(
                "sourceSet",
                "Source set",
                &filters.source_set,
                rows.iter().filter_map(|row| row.source_set.clone()),
                "cyan",
            ),
            compare_filter_chip(
                "module",
                "Module",
                &filters.module,
                rows.iter().filter_map(|row| row.module_path.clone()),
                "green",
            ),
            compare_filter_chip(
                "relation",
                "Relation",
                &filters.relation,
                rows.iter().flat_map(|row| row.relation_kinds.clone()),
                "blue",
            ),
        ],
    }
}

fn compare_filter_chip<I>(
    key: &'static str,
    label: &'static str,
    selected: &Option<String>,
    values: I,
    color: &'static str,
) -> CompareFilterChip
where
    I: Iterator<Item = String>,
{
    let mut unique = BTreeSet::new();
    unique.extend(values);
    let mut options = vec!["any".to_string()];
    options.extend(unique);
    CompareFilterChip {
        key,
        label,
        selected: selected.clone().unwrap_or_else(|| "any".to_string()),
        options,
        color,
    }
}

fn collect_relations<I>(rows: rusqlite::Result<I>) -> Result<Vec<SymbolRelation>>
where
    I: Iterator<Item = rusqlite::Result<SymbolRelation>>,
{
    let mut values = Vec::new();
    for row in rows.map_err(sql_error)? {
        values.push(row.map_err(sql_error)?);
    }
    Ok(values)
}

fn string_column<I>(rows: rusqlite::Result<I>) -> Result<Vec<String>>
where
    I: Iterator<Item = rusqlite::Result<String>>,
{
    let mut values = Vec::new();
    for row in rows.map_err(sql_error)? {
        values.push(row.map_err(sql_error)?);
    }
    Ok(values)
}

fn move_index(current: usize, len: usize, delta: isize) -> usize {
    if len == 0 {
        return 0;
    }
    if delta < 0 {
        current.saturating_sub(delta.unsigned_abs()).min(len - 1)
    } else {
        current.saturating_add(delta as usize).min(len - 1)
    }
}

fn line_number_for_offset(content: &str, offset: usize) -> usize {
    let capped = offset.min(content.len());
    content.as_bytes()[..capped]
        .iter()
        .filter(|byte| **byte == b'\n')
        .count()
        + 1
}

fn edge_summary(edges: &BTreeMap<String, i64>) -> String {
    if edges.is_empty() {
        return "-".to_string();
    }
    edges
        .iter()
        .map(|(kind, count)| format!("{}={count}", compact_kind(kind)))
        .collect::<Vec<_>>()
        .join(" ")
}

fn compact_kind(kind: &str) -> String {
    match kind {
        "TYPE_REF" => "TYPE".to_string(),
        "FUNCTION" => "FUNC".to_string(),
        "PROPERTY" => "PROP".to_string(),
        "INTERFACE" => "IFACE".to_string(),
        "ENUM_CLASS" => "ENUM".to_string(),
        other => truncate_chars(other, 8),
    }
}

fn compare_chip_color(color: &str) -> Color {
    match color {
        "magenta" => Color::Magenta,
        "yellow" => Color::Yellow,
        "cyan" => Color::Cyan,
        "green" => Color::Green,
        "blue" => Color::Blue,
        _ => Color::White,
    }
}

fn compare_badge_label(badge: &CompareBadge) -> &'static str {
    match badge {
        CompareBadge::Common => "=",
        CompareBadge::LexicalOnly => "lexical",
        CompareBadge::SemanticOnly => "semantic",
        CompareBadge::FilteredOut => "filtered",
    }
}

fn compare_badge_style(badge: &CompareBadge) -> Style {
    match badge {
        CompareBadge::Common => Style::default().fg(Color::DarkGray),
        CompareBadge::LexicalOnly => Style::default().fg(Color::Magenta),
        CompareBadge::SemanticOnly => Style::default().fg(Color::Green),
        CompareBadge::FilteredOut => Style::default().fg(Color::Yellow),
    }
}

fn simple_symbol_name(fq_name: &str) -> &str {
    fq_name.rsplit('.').next().unwrap_or(fq_name)
}

fn compact_namespace(fq_name: &str) -> String {
    fq_name
        .rsplit_once('.')
        .map(|(namespace, _)| truncate_chars(namespace, 42))
        .unwrap_or_default()
}

fn simple_file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

fn compact_path(path: &str) -> String {
    let parts: Vec<_> = path.split('/').filter(|part| !part.is_empty()).collect();
    if parts.len() <= 4 {
        return path.to_string();
    }
    format!(
        ".../{}/{}/{}/{}",
        parts[parts.len() - 4],
        parts[parts.len() - 3],
        parts[parts.len() - 2],
        parts[parts.len() - 1]
    )
}

fn truncate_chars(value: &str, max: usize) -> String {
    if value.chars().count() <= max {
        return value.to_string();
    }
    let mut result: String = value.chars().take(max.saturating_sub(3)).collect();
    result.push_str("...");
    result
}

fn sql_error(error: rusqlite::Error) -> CliError {
    CliError::new("SQLITE_ERROR", error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compare_filters_are_single_select_and_filter_semantic_rows() {
        let rows = vec![
            sample_compare_row(
                Some("app.PublicThing"),
                "PublicThing",
                "CLASS",
                "PUBLIC",
                ":app",
                "main",
                ["CALL"],
            ),
            sample_compare_row(
                Some("lib.PrivateHelper"),
                "PrivateHelper",
                "FUNCTION",
                "PRIVATE",
                ":lib",
                "test",
                ["TYPE_REF"],
            ),
        ];
        let filters = CompareFilters {
            kind: Some("FUNCTION".to_string()),
            visibility: Some("PRIVATE".to_string()),
            source_set: Some("test".to_string()),
            module: Some(":lib".to_string()),
            relation: Some("TYPE_REF".to_string()),
        };

        let filtered = apply_compare_filters(&rows, &filters);

        assert_eq!(filtered.len(), 1);
        assert_eq!(filtered[0].fq_name.as_deref(), Some("lib.PrivateHelper"));
    }

    #[test]
    fn compare_diff_buckets_separate_lexical_noise_semantic_only_and_filtered_rows() {
        let lexical = vec![
            sample_compare_row(
                Some("lib.Foo"),
                "Foo",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
            sample_lexical_only_row("FooNotes"),
        ];
        let semantic = vec![
            sample_compare_row(
                Some("lib.Foo"),
                "Foo",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
            sample_compare_row(
                Some("lib.FooWidget"),
                "FooWidget",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
        ];
        let filtered = vec![semantic[0].clone()];

        let buckets = build_compare_diff_buckets(&lexical, &semantic, &filtered);

        assert_eq!(buckets.common_count, 1);
        assert_eq!(buckets.lexical_only[0].label, "FooNotes");
        assert_eq!(
            buckets.semantic_only[0].fq_name.as_deref(),
            Some("lib.FooWidget")
        );
        assert!(
            buckets.filtered_out.is_empty(),
            "semantic-only rows should not also be counted as filtered-out rows"
        );
    }

    #[test]
    fn compare_diff_buckets_keep_common_filtered_rows_separate() {
        let lexical = vec![sample_compare_row(
            Some("lib.Foo"),
            "Foo",
            "CLASS",
            "PUBLIC",
            ":lib",
            "main",
            ["CALL"],
        )];
        let semantic = vec![lexical[0].clone()];
        let filtered = Vec::new();

        let buckets = build_compare_diff_buckets(&lexical, &semantic, &filtered);

        assert_eq!(buckets.common_count, 1);
        assert!(buckets.lexical_only.is_empty());
        assert!(buckets.semantic_only.is_empty());
        assert_eq!(buckets.filtered_out[0].fq_name.as_deref(), Some("lib.Foo"));
    }

    #[test]
    fn compare_selection_prefers_the_active_lexical_pane() {
        let lexical = vec![sample_lexical_only_row("FooNotes")];
        let semantic = vec![sample_compare_row(
            Some("lib.Foo"),
            "Foo",
            "CLASS",
            "PUBLIC",
            ":lib",
            "main",
            ["CALL"],
        )];

        let selected = selected_compare_row(None, &lexical, &semantic, 0, 0, ComparePane::Lexical)
            .expect("selected row");

        assert_eq!(selected.0, ComparePane::Lexical);
        assert_eq!(selected.2.label, "FooNotes");
    }

    #[test]
    fn compare_module_sort_renders_tree_shaped_group_paths() {
        let mut rows = vec![
            sample_compare_row(
                Some("lib.Zed"),
                "Zed",
                "FUNCTION",
                "INTERNAL",
                ":lib",
                "test",
                ["TYPE_REF"],
            ),
            sample_compare_row(
                Some("app.Alpha"),
                "Alpha",
                "CLASS",
                "PUBLIC",
                ":app",
                "main",
                ["CALL"],
            ),
        ];

        sort_compare_rows(&mut rows, CompareSort::Module);

        assert_eq!(rows[0].fq_name.as_deref(), Some("app.Alpha"));
        assert_eq!(
            rows[0].group_path,
            vec![
                ":app".to_string(),
                "main".to_string(),
                "Alpha.kt".to_string()
            ]
        );
        assert_eq!(rows[1].depth, 3);
    }

    #[test]
    fn compare_view_mode_toggle_switches_between_full_and_difference() {
        assert_eq!(CompareViewMode::Full.toggle(), CompareViewMode::Difference);
        assert_eq!(CompareViewMode::Difference.toggle(), CompareViewMode::Full);
    }

    fn sample_compare_row<const N: usize>(
        fq_name: Option<&str>,
        label: &str,
        kind: &str,
        visibility: &str,
        module_path: &str,
        source_set: &str,
        relation_kinds: [&str; N],
    ) -> CompareRow {
        let path = format!(
            "/workspace/{}/{}.kt",
            module_path.trim_start_matches(':'),
            label
        );
        let mut row = CompareRow {
            id: fq_name
                .map(|name| format!("symbol:{name}"))
                .unwrap_or_else(|| format!("lexical:{label}")),
            label: label.to_string(),
            fq_name: fq_name.map(str::to_string),
            kind: Some(kind.to_string()),
            visibility: Some(visibility.to_string()),
            path: Some(path),
            module_path: Some(module_path.to_string()),
            source_set: Some(source_set.to_string()),
            relation_kinds: relation_kinds
                .iter()
                .map(|value| value.to_string())
                .collect(),
            incoming_references: 1,
            outgoing_references: 2,
            group_path: Vec::new(),
            depth: 0,
            badge: CompareBadge::Common,
        };
        assign_compare_module_path(&mut row);
        row
    }

    fn sample_lexical_only_row(label: &str) -> CompareRow {
        let mut row = CompareRow {
            id: format!("lexical:/workspace/lib/{label}.md:{label}"),
            label: label.to_string(),
            fq_name: None,
            kind: None,
            visibility: None,
            path: Some(format!("/workspace/lib/{label}.md")),
            module_path: Some(":lib".to_string()),
            source_set: Some("main".to_string()),
            relation_kinds: Vec::new(),
            incoming_references: 0,
            outgoing_references: 0,
            group_path: Vec::new(),
            depth: 0,
            badge: CompareBadge::LexicalOnly,
        };
        assign_compare_module_path(&mut row);
        row
    }
}
