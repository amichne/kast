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
const SPATIAL_VIEWPORT_WIDTH: u16 = 120;
const SPATIAL_VIEWPORT_HEIGHT: u16 = 40;
const SPATIAL_MAX_VISIBLE_NODES: usize = 500;
const SPATIAL_MAX_RENDERED_LABELS: usize = 48;
const SPATIAL_MAX_OVERLAY_EDGES: usize = 200;
const SPATIAL_AUTO_COLLAPSE_DEPTH: usize = 6;
const SPATIAL_AUTO_COLLAPSE_SUBTREE_SIZE: usize = 100;
const SPATIAL_CONTEXT_CHILD_LIMIT: usize = 12;
const SPATIAL_CONTEXT_RELATION_LIMIT: usize = 18;
const SPATIAL_MAX_STRUCTURAL_NODES_PER_FILE: usize = 12;

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
struct SpatialDemoResponse {
    ok: bool,
    snapshot: SpatialAstSnapshot,
    schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialAstSnapshot {
    mode: &'static str,
    workspace_root: String,
    database: String,
    query: String,
    current: Option<SymbolDetail>,
    tree: SpatialTree,
    camera: SpatialCamera,
    selection: SpatialSelection,
    visible_nodes: Vec<RenderedSpatialNode>,
    visible_edges: Vec<RenderedSpatialEdge>,
    overlays: Vec<SpatialOverlay>,
    incoming: Vec<SymbolRelation>,
    outgoing: Vec<SymbolRelation>,
    preview: SourcePreview,
    trail: Vec<String>,
    index: DemoIndex,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialTree {
    root_id: String,
    nodes: Vec<SpatialTreeNode>,
    edges: Vec<SpatialTreeEdge>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialTreeNode {
    id: String,
    parent_id: Option<String>,
    label: String,
    kind: String,
    identity: SpatialNodeIdentity,
    file_path: Option<String>,
    span: Option<SourceSpan>,
    declaration_offset: Option<i64>,
    symbol_fq_name: Option<String>,
    child_count: usize,
    collapsed: bool,
    metrics: SpatialNodeMetrics,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
enum SpatialNodeIdentity {
    CompilerSymbol,
    SourceIndexDeclaration,
    FileOutlineNode,
    LiteralAstNode,
    SyntheticAggregate,
    StructuralOnly,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialNodeMetrics {
    incoming_references: usize,
    outgoing_references: usize,
    diagnostic_count: usize,
    subtree_size: usize,
    depth: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialTreeEdge {
    id: String,
    source_id: String,
    target_id: String,
    kind: SpatialRenderedEdgeKind,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SourceSpan {
    start_offset: i64,
    end_offset: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialSelection {
    node_id: String,
    symbol_fq_name: Option<String>,
    path: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialCamera {
    position: SpatialVec3,
    yaw: f32,
    pitch: f32,
    zoom: f32,
    projection: ProjectionMode,
    target_node_id: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialVec3 {
    x: f32,
    y: f32,
    z: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum ProjectionMode {
    OrthographicTopDown,
    OrthographicOblique,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RenderedSpatialNode {
    node_id: String,
    label: String,
    kind: String,
    identity: SpatialNodeIdentity,
    x: f32,
    y: f32,
    z: f32,
    screen_x: f32,
    screen_y: f32,
    radius: f32,
    label_lod: LabelLevel,
    selected: bool,
    collapsed: bool,
    child_count: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RenderedSpatialEdge {
    source_id: String,
    target_id: String,
    kind: SpatialRenderedEdgeKind,
    overlay: bool,
    from_x: f32,
    from_y: f32,
    to_x: f32,
    to_y: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum SpatialRenderedEdgeKind {
    Containment,
    Reference,
    CallFlow,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
enum LabelLevel {
    Hidden,
    GlyphOnly,
    KindOnly,
    ShortLabel,
    FullLabel,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SpatialOverlay {
    mode: SpatialOverlayMode,
    enabled: bool,
    edge_count: usize,
    capped: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
enum SpatialOverlayMode {
    StructureOnly,
    References,
    CallFlow,
}

#[derive(Debug, Clone)]
struct SpatialSnapshotOptions {
    selected_node_id: Option<String>,
    collapsed_node_ids: BTreeSet<String>,
    overlay_mode: SpatialOverlayMode,
    camera: SpatialCamera,
}

#[derive(Debug, Clone)]
struct IndexedFile {
    path: String,
    module_path: Option<String>,
    source_set: Option<String>,
}

#[derive(Debug, Clone)]
struct IndexedDeclaration {
    fq_name: String,
    simple_name: String,
    kind: String,
    path: String,
    declaration_offset: Option<i64>,
    module_path: Option<String>,
    source_set: Option<String>,
    incoming_references: usize,
    outgoing_references: usize,
}

#[derive(Debug, Clone)]
struct IndexedStructuralNode {
    label: String,
    kind: String,
    path: String,
    offset: i64,
}

#[derive(Debug, Clone)]
struct SpatialPlacement {
    node_id: String,
    x: f32,
    y: f32,
    z: f32,
    radius: f32,
    label_lod: LabelLevel,
}

struct SpatialProjectionRequest<'a> {
    tree: &'a SpatialTree,
    selected_node_id: &'a str,
    camera: &'a SpatialCamera,
    overlay_mode: SpatialOverlayMode,
    incoming: &'a [SymbolRelation],
    outgoing: &'a [SymbolRelation],
    viewport_width: u16,
    viewport_height: u16,
}

#[derive(Debug, Clone)]
struct SpatialCell {
    glyph: char,
    fg: Color,
    bg: Color,
    depth: f32,
    node_id: Option<String>,
}

#[derive(Debug, Clone)]
struct TerminalFrameBuffer {
    width: u16,
    height: u16,
    cells: Vec<SpatialCell>,
}

impl Default for SpatialSnapshotOptions {
    fn default() -> Self {
        Self {
            selected_node_id: None,
            collapsed_node_ids: BTreeSet::new(),
            overlay_mode: SpatialOverlayMode::StructureOnly,
            camera: SpatialCamera::default(),
        }
    }
}

impl Default for SpatialCamera {
    fn default() -> Self {
        Self {
            position: SpatialVec3 {
                x: 0.0,
                y: 0.0,
                z: 0.0,
            },
            yaw: 0.0,
            pitch: 0.0,
            zoom: 1.0,
            projection: ProjectionMode::OrthographicOblique,
            target_node_id: None,
        }
    }
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SpatialFocus {
    Search,
    Canvas,
    Incoming,
    Outgoing,
    Details,
}

struct SpatialDemoApp {
    db: DemoDatabase,
    request: DemoRequest,
    options: SpatialSnapshotOptions,
    snapshot: SpatialAstSnapshot,
    anchor_symbol: Option<String>,
    search_query: String,
    search_results: Vec<SymbolHit>,
    selected_search: usize,
    selected_incoming: usize,
    selected_outgoing: usize,
    focus: SpatialFocus,
    message: String,
    trail: Vec<String>,
    should_quit: bool,
}

pub fn run(args: DemoArgs) -> Result<i32> {
    match args.view {
        DemoView::Symbol => run_symbol_demo(args),
        DemoView::Spatial => run_spatial_demo(args),
    }
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

fn run_spatial_demo(args: DemoArgs) -> Result<i32> {
    let request = DemoRequest::from_args(args)?;
    let mut db = DemoDatabase::open(request.clone())?;
    let options = SpatialSnapshotOptions::default();
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
    let snapshot = db.spatial_snapshot(
        request.symbol.as_deref(),
        &initial_query,
        Vec::new(),
        &options,
    )?;

    if request.json || !io::stdout().is_terminal() {
        return print_spatial_json_snapshot(snapshot);
    }

    run_spatial_tui(SpatialDemoApp::from_snapshot(
        db, request, snapshot, options,
    )?)
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

    fn spatial_snapshot(
        &mut self,
        requested_symbol: Option<&str>,
        query: &str,
        trail: Vec<String>,
        options: &SpatialSnapshotOptions,
    ) -> Result<SpatialAstSnapshot> {
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
        let mut tree = self.build_spatial_tree(current.as_ref())?;
        let selected_node_id = options
            .selected_node_id
            .as_ref()
            .filter(|node_id| spatial_node_exists(&tree, node_id))
            .cloned()
            .or_else(|| {
                current
                    .as_ref()
                    .map(|symbol| spatial_symbol_node_id(&symbol.fq_name))
                    .filter(|node_id| spatial_node_exists(&tree, node_id))
            })
            .unwrap_or_else(|| tree.root_id.clone());
        let selected_path_ids = spatial_path_ids(&tree, &selected_node_id);
        apply_spatial_collapse(&mut tree, &options.collapsed_node_ids, &selected_path_ids);
        let selected_node = spatial_node(&tree, &selected_node_id);
        let preview = selected_node
            .map(|node| {
                SourcePreview::from_location(
                    node.file_path.as_deref(),
                    node.declaration_offset,
                    format!("Spatial node: {}", node.label),
                )
            })
            .unwrap_or_else(|| SourcePreview::message("No spatial node selected"));
        let mut camera = options.camera.clone();
        if camera.target_node_id.is_none() {
            camera.target_node_id = Some(selected_node_id.clone());
        }
        let selection = SpatialSelection {
            node_id: selected_node_id.clone(),
            symbol_fq_name: selected_node.and_then(|node| node.symbol_fq_name.clone()),
            path: selected_path_ids
                .iter()
                .filter_map(|node_id| spatial_node(&tree, node_id))
                .map(|node| node.label.clone())
                .collect(),
        };
        let (visible_nodes, visible_edges) = project_spatial_tree(SpatialProjectionRequest {
            tree: &tree,
            selected_node_id: &selected_node_id,
            camera: &camera,
            overlay_mode: options.overlay_mode,
            incoming: &incoming,
            outgoing: &outgoing,
            viewport_width: SPATIAL_VIEWPORT_WIDTH,
            viewport_height: SPATIAL_VIEWPORT_HEIGHT,
        });
        Ok(SpatialAstSnapshot {
            mode: "spatialAst",
            workspace_root: self.request.workspace_root.display().to_string(),
            database: self.request.database.display().to_string(),
            query: query.to_string(),
            current,
            tree,
            camera,
            selection,
            visible_nodes,
            visible_edges,
            overlays: spatial_overlays(options.overlay_mode, &incoming, &outgoing),
            incoming,
            outgoing,
            preview,
            trail,
            index: self.index()?,
        })
    }

    fn build_spatial_tree(&self, current: Option<&SymbolDetail>) -> Result<SpatialTree> {
        let current_fq_name = current.map(|symbol| symbol.fq_name.as_str());
        let files = self.indexed_files()?;
        let declarations = self.indexed_declarations()?;
        let mut nodes = Vec::new();
        let mut edges = Vec::new();
        let root_id = "workspace".to_string();
        nodes.push(spatial_tree_node(
            root_id.clone(),
            None,
            self.request
                .workspace_root
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("workspace")
                .to_string(),
            "WORKSPACE".to_string(),
            SpatialNodeIdentity::SyntheticAggregate,
            None,
            None,
            None,
            None,
            0,
            0,
        ));

        let mut module_nodes = BTreeSet::new();
        let mut file_nodes = BTreeSet::new();
        for file in files {
            let module_id = spatial_module_node_id(&file.module_path, &file.source_set);
            if module_nodes.insert(module_id.clone()) {
                let label = spatial_module_label(&file.module_path, &file.source_set);
                edges.push(spatial_tree_edge(&root_id, &module_id));
                nodes.push(spatial_tree_node(
                    module_id.clone(),
                    Some(root_id.clone()),
                    label,
                    "MODULE".to_string(),
                    SpatialNodeIdentity::SyntheticAggregate,
                    None,
                    None,
                    None,
                    None,
                    0,
                    0,
                ));
            }

            let file_id = spatial_file_node_id(&file.path);
            if file_nodes.insert(file_id.clone()) {
                edges.push(spatial_tree_edge(&module_id, &file_id));
                nodes.push(spatial_tree_node(
                    file_id.clone(),
                    Some(module_id),
                    simple_file_name(&file.path).to_string(),
                    "FILE".to_string(),
                    SpatialNodeIdentity::FileOutlineNode,
                    Some(file.path.clone()),
                    None,
                    None,
                    None,
                    0,
                    0,
                ));
                for structural in source_structural_nodes(&file.path) {
                    let structural_id = spatial_structural_node_id(
                        &structural.path,
                        &structural.kind,
                        structural.offset,
                    );
                    edges.push(spatial_tree_edge(&file_id, &structural_id));
                    nodes.push(spatial_tree_node(
                        structural_id,
                        Some(file_id.clone()),
                        structural.label,
                        structural.kind,
                        SpatialNodeIdentity::StructuralOnly,
                        Some(structural.path),
                        Some(SourceSpan {
                            start_offset: structural.offset,
                            end_offset: None,
                        }),
                        Some(structural.offset),
                        None,
                        0,
                        0,
                    ));
                }
            }
        }

        for declaration in declarations {
            let module_id =
                spatial_module_node_id(&declaration.module_path, &declaration.source_set);
            if module_nodes.insert(module_id.clone()) {
                let label = spatial_module_label(&declaration.module_path, &declaration.source_set);
                edges.push(spatial_tree_edge(&root_id, &module_id));
                nodes.push(spatial_tree_node(
                    module_id.clone(),
                    Some(root_id.clone()),
                    label,
                    "MODULE".to_string(),
                    SpatialNodeIdentity::SyntheticAggregate,
                    None,
                    None,
                    None,
                    None,
                    0,
                    0,
                ));
            }

            let file_id = spatial_file_node_id(&declaration.path);
            if file_nodes.insert(file_id.clone()) {
                edges.push(spatial_tree_edge(&module_id, &file_id));
                nodes.push(spatial_tree_node(
                    file_id.clone(),
                    Some(module_id.clone()),
                    simple_file_name(&declaration.path).to_string(),
                    "FILE".to_string(),
                    SpatialNodeIdentity::FileOutlineNode,
                    Some(declaration.path.clone()),
                    None,
                    None,
                    None,
                    0,
                    0,
                ));
            }

            let node_id = spatial_symbol_node_id(&declaration.fq_name);
            let identity = if current_fq_name == Some(declaration.fq_name.as_str()) {
                SpatialNodeIdentity::CompilerSymbol
            } else {
                SpatialNodeIdentity::SourceIndexDeclaration
            };
            edges.push(spatial_tree_edge(&file_id, &node_id));
            nodes.push(spatial_tree_node(
                node_id,
                Some(file_id),
                declaration.simple_name,
                declaration.kind,
                identity,
                Some(declaration.path),
                declaration.declaration_offset.map(|offset| SourceSpan {
                    start_offset: offset,
                    end_offset: None,
                }),
                declaration.declaration_offset,
                Some(declaration.fq_name),
                declaration.incoming_references,
                declaration.outgoing_references,
            ));
        }

        let mut tree = SpatialTree {
            root_id,
            nodes,
            edges,
        };
        compute_spatial_metrics(&mut tree);
        Ok(tree)
    }

    fn indexed_files(&self) -> Result<Vec<IndexedFile>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT prefixes.dir_path,
                       manifest.filename,
                       metadata.module_path,
                       metadata.source_set
                FROM file_manifest manifest
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
                LEFT JOIN file_metadata metadata
                  ON metadata.prefix_id = manifest.prefix_id
                 AND metadata.filename = manifest.filename
                ORDER BY COALESCE(metadata.module_path, ''),
                         COALESCE(metadata.source_set, ''),
                         COALESCE(prefixes.dir_path, ''),
                         manifest.filename
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                let dir = row.get::<_, Option<String>>(0)?.unwrap_or_default();
                let filename: String = row.get(1)?;
                Ok(IndexedFile {
                    path: self.compose_path(dir, filename),
                    module_path: row.get(2)?,
                    source_set: row.get(3)?,
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            values.push(row.map_err(sql_error)?);
        }
        Ok(values)
    }

    fn indexed_declarations(&self) -> Result<Vec<IndexedDeclaration>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name,
                       declarations.kind,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.declaration_offset,
                       declarations.module_path,
                       declarations.source_set
                FROM declarations
                JOIN fq_names names ON names.fq_id = declarations.fq_id
                LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                ORDER BY COALESCE(declarations.module_path, ''),
                         COALESCE(declarations.source_set, ''),
                         COALESCE(prefixes.dir_path, ''),
                         declarations.filename,
                         COALESCE(declarations.declaration_offset, 0),
                         names.fq_name
                "#,
            )
            .map_err(sql_error)?;
        let rows = stmt
            .query_map([], |row| {
                let fq_name: String = row.get(0)?;
                let dir = row.get::<_, Option<String>>(2)?.unwrap_or_default();
                let filename: String = row.get(3)?;
                let incoming_references = self
                    .reference_count_for_target(&fq_name)
                    .unwrap_or(0)
                    .max(0) as usize;
                let outgoing_references = self
                    .reference_count_for_source(&fq_name)
                    .unwrap_or(0)
                    .max(0) as usize;
                Ok(IndexedDeclaration {
                    simple_name: simple_symbol_name(&fq_name).to_string(),
                    kind: row.get(1)?,
                    path: self.compose_path(dir, filename),
                    declaration_offset: row.get(4)?,
                    module_path: row.get(5)?,
                    source_set: row.get(6)?,
                    incoming_references,
                    outgoing_references,
                    fq_name,
                })
            })
            .map_err(sql_error)?;
        let mut values = Vec::new();
        for row in rows {
            values.push(row.map_err(sql_error)?);
        }
        Ok(values)
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

impl SpatialDemoApp {
    fn from_snapshot(
        db: DemoDatabase,
        request: DemoRequest,
        snapshot: SpatialAstSnapshot,
        options: SpatialSnapshotOptions,
    ) -> Result<Self> {
        let search_query = snapshot.query.clone();
        let search_results = db.search(&search_query, request.limit)?;
        let anchor_symbol = snapshot
            .current
            .as_ref()
            .map(|symbol| symbol.fq_name.clone());
        Ok(Self {
            db,
            request,
            options,
            snapshot,
            anchor_symbol,
            search_query,
            search_results,
            selected_search: 0,
            selected_incoming: 0,
            selected_outgoing: 0,
            focus: SpatialFocus::Canvas,
            message: "Search, walk refs, move the structure, collapse nodes, and inspect source."
                .to_string(),
            trail: Vec::new(),
            should_quit: false,
        })
    }

    fn on_key(&mut self, key: KeyEvent) -> Result<()> {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            self.should_quit = true;
            return Ok(());
        }
        match self.focus {
            SpatialFocus::Search => self.on_search_key(key),
            _ => self.on_navigation_key(key),
        }
    }

    fn on_search_key(&mut self, key: KeyEvent) -> Result<()> {
        match key.code {
            KeyCode::Esc => {
                self.focus = SpatialFocus::Canvas;
                self.message = "Search cancelled".to_string();
            }
            KeyCode::Enter => {
                let symbol = self
                    .search_results
                    .get(self.selected_search)
                    .map(|hit| hit.fq_name.clone());
                if let Some(symbol) = symbol {
                    self.open_symbol(&symbol, true)?;
                } else {
                    self.message = "No search hit selected".to_string();
                }
                self.focus = SpatialFocus::Canvas;
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
            }
            KeyCode::Down => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), 1);
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
                self.focus = SpatialFocus::Search;
                self.message = "Type to search; Enter anchors the spatial tree".to_string();
            }
            KeyCode::Tab => self.focus = self.focus.next(),
            KeyCode::BackTab => self.focus = self.focus.previous(),
            KeyCode::Up | KeyCode::Char('k') => self.move_selection(-1)?,
            KeyCode::Down | KeyCode::Char('j') => self.move_selection(1)?,
            KeyCode::Left | KeyCode::Char('h') => self.select_parent()?,
            KeyCode::Right | KeyCode::Char('l') => self.select_first_child()?,
            KeyCode::Enter if self.focus == SpatialFocus::Search => {
                let symbol = self
                    .search_results
                    .get(self.selected_search)
                    .map(|hit| hit.fq_name.clone());
                if let Some(symbol) = symbol {
                    self.open_symbol(&symbol, true)?;
                }
            }
            KeyCode::Enter => self.activate_selection()?,
            KeyCode::Char(' ') => self.toggle_collapse()?,
            KeyCode::Char('o') | KeyCode::Char('O') => self.cycle_overlay()?,
            KeyCode::Char('p') | KeyCode::Char('P') => self.cycle_projection()?,
            KeyCode::Char('f') | KeyCode::Char('F') | KeyCode::Char('c') | KeyCode::Char('C') => {
                self.fit_selected()?
            }
            KeyCode::Char('w') | KeyCode::Char('W') => self.move_camera(0.0, -1.0, 0.0)?,
            KeyCode::Char('s') | KeyCode::Char('S') => self.move_camera(0.0, 1.0, 0.0)?,
            KeyCode::Char('a') | KeyCode::Char('A') => self.move_camera(-1.0, 0.0, 0.0)?,
            KeyCode::Char('d') | KeyCode::Char('D') => self.move_camera(1.0, 0.0, 0.0)?,
            KeyCode::Char('e') | KeyCode::Char('E') => self.move_camera(0.0, 0.0, 0.1)?,
            KeyCode::Char('Q') => self.move_camera(0.0, 0.0, -0.1)?,
            KeyCode::Char('b') | KeyCode::Backspace => self.back()?,
            KeyCode::Char('r') => self.refresh_snapshot()?,
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
        Ok(())
    }

    fn refresh_snapshot(&mut self) -> Result<()> {
        self.snapshot = self.db.spatial_snapshot(
            self.anchor_symbol.as_deref(),
            &self.search_query,
            self.trail.clone(),
            &self.options,
        )?;
        if self.options.selected_node_id.is_none() {
            self.options.selected_node_id = Some(self.snapshot.selection.node_id.clone());
        }
        self.selected_incoming = self
            .selected_incoming
            .min(self.snapshot.incoming.len().saturating_sub(1));
        self.selected_outgoing = self
            .selected_outgoing
            .min(self.snapshot.outgoing.len().saturating_sub(1));
        Ok(())
    }

    fn open_symbol(&mut self, fq_name: &str, push_current: bool) -> Result<()> {
        if push_current
            && let Some(current) = &self.anchor_symbol
            && current != fq_name
        {
            self.trail.push(current.clone());
            if self.trail.len() > 10 {
                self.trail.remove(0);
            }
        }
        self.anchor_symbol = Some(fq_name.to_string());
        self.options.selected_node_id = Some(spatial_symbol_node_id(fq_name));
        self.options.camera.target_node_id = self.options.selected_node_id.clone();
        self.refresh_snapshot()?;
        self.message = format!("Anchored spatial view on {fq_name}");
        Ok(())
    }

    fn back(&mut self) -> Result<()> {
        if let Some(symbol) = self.trail.pop() {
            self.anchor_symbol = Some(symbol.clone());
            self.options.selected_node_id = Some(spatial_symbol_node_id(&symbol));
            self.refresh_snapshot()?;
            self.message = format!("Back to {symbol}");
        } else {
            self.message = "No previous symbol in this walk".to_string();
        }
        Ok(())
    }

    fn move_selection(&mut self, delta: isize) -> Result<()> {
        match self.focus {
            SpatialFocus::Search => {
                self.selected_search =
                    move_index(self.selected_search, self.search_results.len(), delta);
                Ok(())
            }
            SpatialFocus::Canvas | SpatialFocus::Details => self.move_visible_selection(delta),
            SpatialFocus::Incoming => {
                self.selected_incoming =
                    move_index(self.selected_incoming, self.snapshot.incoming.len(), delta);
                Ok(())
            }
            SpatialFocus::Outgoing => {
                self.selected_outgoing =
                    move_index(self.selected_outgoing, self.snapshot.outgoing.len(), delta);
                Ok(())
            }
        }
    }

    fn move_visible_selection(&mut self, delta: isize) -> Result<()> {
        let visible: Vec<_> = self
            .snapshot
            .visible_nodes
            .iter()
            .map(|node| node.node_id.clone())
            .collect();
        if visible.is_empty() {
            return Ok(());
        }
        let current = visible
            .iter()
            .position(|node_id| node_id == &self.snapshot.selection.node_id)
            .unwrap_or(0);
        let next = move_index(current, visible.len(), delta);
        self.select_node(visible[next].clone())
    }

    fn activate_selection(&mut self) -> Result<()> {
        match self.focus {
            SpatialFocus::Search => {
                let symbol = self
                    .search_results
                    .get(self.selected_search)
                    .map(|hit| hit.fq_name.clone());
                if let Some(symbol) = symbol {
                    self.open_symbol(&symbol, true)?;
                } else {
                    self.message = "No search hit selected".to_string();
                }
            }
            SpatialFocus::Incoming => {
                let relation = self.snapshot.incoming.get(self.selected_incoming).cloned();
                self.open_relation(relation)?;
            }
            SpatialFocus::Outgoing => {
                let relation = self.snapshot.outgoing.get(self.selected_outgoing).cloned();
                self.open_relation(relation)?;
            }
            SpatialFocus::Canvas | SpatialFocus::Details => {
                self.fit_selected()?;
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
            self.message =
                "This relation is file-level only; no source symbol was indexed".to_string();
            Ok(())
        }
    }

    fn select_parent(&mut self) -> Result<()> {
        let path = spatial_path_ids(&self.snapshot.tree, &self.snapshot.selection.node_id);
        if path.len() < 2 {
            self.message = "Already at the workspace root".to_string();
            return Ok(());
        }
        self.select_node(path[path.len() - 2].clone())
    }

    fn select_first_child(&mut self) -> Result<()> {
        let children = spatial_children_map(&self.snapshot.tree);
        let child = children
            .get(&self.snapshot.selection.node_id)
            .and_then(|values| values.first())
            .cloned();
        if let Some(child) = child {
            self.select_node(child)
        } else {
            self.message = "Selected node has no children".to_string();
            Ok(())
        }
    }

    fn select_node(&mut self, node_id: String) -> Result<()> {
        self.options.selected_node_id = Some(node_id.clone());
        if let Some(symbol) =
            spatial_node(&self.snapshot.tree, &node_id).and_then(|node| node.symbol_fq_name.clone())
        {
            self.anchor_symbol = Some(symbol);
        }
        self.options.camera.target_node_id = Some(node_id);
        self.refresh_snapshot()
    }

    fn toggle_collapse(&mut self) -> Result<()> {
        let node_id = self.snapshot.selection.node_id.clone();
        let Some(node) = spatial_node(&self.snapshot.tree, &node_id) else {
            return Ok(());
        };
        if node.child_count == 0 {
            self.message = "Selected node has no subtree to collapse".to_string();
            return Ok(());
        }
        if self.options.collapsed_node_ids.remove(&node_id) {
            self.message = format!("Expanded {}", node.label);
        } else {
            self.options.collapsed_node_ids.insert(node_id.clone());
            self.message = format!("Collapsed {}", node.label);
        }
        self.refresh_snapshot()
    }

    fn cycle_overlay(&mut self) -> Result<()> {
        self.options.overlay_mode = match self.options.overlay_mode {
            SpatialOverlayMode::StructureOnly => SpatialOverlayMode::References,
            SpatialOverlayMode::References => SpatialOverlayMode::CallFlow,
            SpatialOverlayMode::CallFlow => SpatialOverlayMode::StructureOnly,
        };
        self.message = format!(
            "Overlay: {}",
            spatial_overlay_title(self.options.overlay_mode)
        );
        self.refresh_snapshot()
    }

    fn cycle_projection(&mut self) -> Result<()> {
        self.options.camera.projection = match self.options.camera.projection {
            ProjectionMode::OrthographicTopDown => ProjectionMode::OrthographicOblique,
            ProjectionMode::OrthographicOblique => ProjectionMode::OrthographicTopDown,
        };
        self.message = format!(
            "Projection: {}",
            spatial_projection_title(self.options.camera.projection)
        );
        self.refresh_snapshot()
    }

    fn fit_selected(&mut self) -> Result<()> {
        self.options.camera.position = SpatialVec3 {
            x: 0.0,
            y: 0.0,
            z: 0.0,
        };
        self.options.camera.zoom = 1.0;
        self.options.camera.target_node_id = Some(self.snapshot.selection.node_id.clone());
        self.message = "Centered selected node".to_string();
        self.refresh_snapshot()
    }

    fn move_camera(&mut self, dx: f32, dy: f32, zoom_delta: f32) -> Result<()> {
        self.options.camera.position.x += dx;
        self.options.camera.position.y += dy;
        self.options.camera.zoom = (self.options.camera.zoom + zoom_delta).clamp(0.4, 3.0);
        self.refresh_snapshot()
    }
}

impl SpatialFocus {
    fn next(self) -> Self {
        match self {
            Self::Search => Self::Canvas,
            Self::Canvas => Self::Incoming,
            Self::Incoming => Self::Outgoing,
            Self::Outgoing => Self::Details,
            Self::Details => Self::Search,
        }
    }

    fn previous(self) -> Self {
        match self {
            Self::Search => Self::Details,
            Self::Canvas => Self::Search,
            Self::Incoming => Self::Canvas,
            Self::Outgoing => Self::Incoming,
            Self::Details => Self::Outgoing,
        }
    }

    fn title(self) -> &'static str {
        match self {
            Self::Search => "search",
            Self::Canvas => "spatial",
            Self::Incoming => "callers",
            Self::Outgoing => "callees",
            Self::Details => "details",
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

fn run_spatial_tui(mut app: SpatialDemoApp) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let result = run_spatial_event_loop(&mut terminal, &mut app);
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

fn run_spatial_event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut SpatialDemoApp,
) -> Result<i32> {
    loop {
        terminal.draw(|frame| render_spatial_demo(frame, app))?;
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

fn render_spatial_demo(frame: &mut Frame<'_>, app: &SpatialDemoApp) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(5),
            Constraint::Min(10),
            Constraint::Length(2),
        ])
        .split(frame.area());
    render_spatial_header(frame, root[0], app);
    render_spatial_body(frame, root[1], app);
    render_spatial_footer(frame, root[2], app);
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

fn render_spatial_header(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    let current = app
        .snapshot
        .current
        .as_ref()
        .map(|symbol| symbol.fq_name.as_str())
        .unwrap_or("no symbol");
    let selected = spatial_node(&app.snapshot.tree, &app.snapshot.selection.node_id)
        .map(|node| node.label.as_str())
        .unwrap_or("none");
    let lines = vec![
        Line::from(vec![
            Span::styled(
                "Kast Spatial AST",
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
                "  selected {}  nodes {}/{}  overlay {}  projection {}",
                selected,
                app.snapshot.visible_nodes.len(),
                app.snapshot.tree.nodes.len(),
                spatial_overlay_title(app.options.overlay_mode),
                spatial_projection_title(app.options.camera.projection)
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

fn render_spatial_body(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    if area.width < 110 {
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(9),
                Constraint::Percentage(34),
                Constraint::Percentage(26),
                Constraint::Percentage(40),
            ])
            .split(area);
        render_spatial_search(frame, rows[0], app);
        render_spatial_canvas(frame, rows[1], app);
        render_spatial_relations(frame, rows[2], app);
        render_spatial_details(frame, rows[3], app);
        return;
    }

    let columns = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage(25),
            Constraint::Percentage(48),
            Constraint::Percentage(27),
        ])
        .split(area);
    let left = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Percentage(46),
            Constraint::Percentage(34),
            Constraint::Percentage(20),
        ])
        .split(columns[0]);
    render_spatial_search(frame, left[0], app);
    render_spatial_relations(frame, left[1], app);
    render_spatial_overlay_status(frame, left[2], app);
    render_spatial_canvas(frame, columns[1], app);
    render_spatial_details(frame, columns[2], app);
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

fn render_spatial_search(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    let title = if app.focus == SpatialFocus::Search {
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
            ListItem::new(Line::from(vec![
                Span::styled(
                    format!(
                        "{:<10}",
                        compact_kind(hit.kind.as_deref().unwrap_or("SYMBOL"))
                    ),
                    Style::default().fg(Color::Magenta),
                ),
                Span::styled(
                    hit.simple_name.clone(),
                    Style::default()
                        .fg(Color::White)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(format!(
                    "  in {} out {}",
                    hit.incoming_references, hit.outgoing_references
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
        app.focus == SpatialFocus::Search,
    );
}

fn render_spatial_overlay_status(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    let lines: Vec<_> = app
        .snapshot
        .overlays
        .iter()
        .map(|overlay| {
            let marker = if overlay.enabled { ">" } else { " " };
            let capped = if overlay.capped { "+" } else { "" };
            Line::from(vec![
                Span::styled(marker, Style::default().fg(Color::Cyan)),
                Span::raw(" "),
                Span::styled(
                    spatial_overlay_title(overlay.mode),
                    Style::default().fg(if overlay.enabled {
                        Color::Yellow
                    } else {
                        Color::White
                    }),
                ),
                Span::raw(format!("  {}{}", overlay.edge_count, capped)),
            ])
        })
        .collect();
    frame.render_widget(
        Paragraph::new(lines)
            .block(Block::default().title("Overlays").borders(Borders::ALL))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_spatial_relations(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
        .split(area);
    render_relations(
        frame,
        rows[0],
        "Callers / References In",
        &app.snapshot.incoming,
        app.selected_incoming,
        app.focus == SpatialFocus::Incoming,
    );
    render_relations(
        frame,
        rows[1],
        "Callees / References Out",
        &app.snapshot.outgoing,
        app.selected_outgoing,
        app.focus == SpatialFocus::Outgoing,
    );
}

fn render_spatial_canvas(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    let border_style = if app.focus == SpatialFocus::Canvas {
        Style::default().fg(Color::Cyan)
    } else {
        Style::default().fg(Color::DarkGray)
    };
    let block = Block::default()
        .title("Structural Map")
        .borders(Borders::ALL)
        .border_style(border_style);
    let inner = block.inner(area);
    frame.render_widget(block, area);
    if inner.width < 20 || inner.height < 8 {
        frame.render_widget(
            Paragraph::new("Terminal area is too small for the spatial canvas"),
            inner,
        );
        return;
    }
    let buffer = render_spatial_framebuffer(&app.snapshot, inner.width, inner.height);
    frame.render_widget(Paragraph::new(buffer.lines()), inner);
}

fn render_spatial_details(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    let rows = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(11), Constraint::Min(8)])
        .split(area);
    let selected = spatial_node(&app.snapshot.tree, &app.snapshot.selection.node_id);
    let lines = selected
        .map(|node| {
            vec![
                Line::from(vec![
                    Span::styled(
                        node.label.clone(),
                        Style::default()
                            .fg(Color::Yellow)
                            .add_modifier(Modifier::BOLD),
                    ),
                    Span::raw(format!("  {}", node.kind)),
                ]),
                Line::from(format!("id: {}", truncate_chars(&node.id, 72))),
                Line::from(format!("identity: {:?}", node.identity)),
                Line::from(format!(
                    "refs: {} incoming / {} outgoing",
                    node.metrics.incoming_references, node.metrics.outgoing_references
                )),
                Line::from(format!(
                    "depth: {}  subtree: {}  children: {}  collapsed: {}",
                    node.metrics.depth, node.metrics.subtree_size, node.child_count, node.collapsed
                )),
                Line::from(format!("path: {}", app.snapshot.selection.path.join(" / "))),
            ]
        })
        .unwrap_or_else(|| vec![Line::from("No node selected")]);
    let border_style = if app.focus == SpatialFocus::Details {
        Style::default().fg(Color::Cyan)
    } else {
        Style::default().fg(Color::DarkGray)
    };
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .title("Selected Node")
                    .borders(Borders::ALL)
                    .border_style(border_style),
            )
            .wrap(Wrap { trim: true }),
        rows[0],
    );
    render_source_preview(frame, rows[1], &app.snapshot.preview);
}

fn render_spatial_footer(frame: &mut Frame<'_>, area: Rect, app: &SpatialDemoApp) {
    let text = format!(
        "focus {} | arrows select | Enter open/focus | Space collapse | O overlay | P projection | F/C center | / search | b back | q quit | db {}",
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

fn print_spatial_json_snapshot(snapshot: SpatialAstSnapshot) -> Result<i32> {
    let response = SpatialDemoResponse {
        ok: true,
        snapshot,
        schema_version: SCHEMA_VERSION,
    };
    serde_json::to_writer_pretty(io::stdout(), &serde_json::to_value(response)?)?;
    println!();
    Ok(0)
}

#[allow(clippy::too_many_arguments)]
fn spatial_tree_node(
    id: String,
    parent_id: Option<String>,
    label: String,
    kind: String,
    identity: SpatialNodeIdentity,
    file_path: Option<String>,
    span: Option<SourceSpan>,
    declaration_offset: Option<i64>,
    symbol_fq_name: Option<String>,
    incoming_references: usize,
    outgoing_references: usize,
) -> SpatialTreeNode {
    SpatialTreeNode {
        id,
        parent_id,
        label,
        kind,
        identity,
        file_path,
        span,
        declaration_offset,
        symbol_fq_name,
        child_count: 0,
        collapsed: false,
        metrics: SpatialNodeMetrics {
            incoming_references,
            outgoing_references,
            diagnostic_count: 0,
            subtree_size: 1,
            depth: 0,
        },
    }
}

fn spatial_tree_edge(source_id: &str, target_id: &str) -> SpatialTreeEdge {
    SpatialTreeEdge {
        id: format!("edge:{source_id}->{target_id}"),
        source_id: source_id.to_string(),
        target_id: target_id.to_string(),
        kind: SpatialRenderedEdgeKind::Containment,
    }
}

fn spatial_symbol_node_id(fq_name: &str) -> String {
    format!("symbol:{fq_name}")
}

fn spatial_file_node_id(path: &str) -> String {
    format!("file:{path}")
}

fn spatial_structural_node_id(path: &str, kind: &str, offset: i64) -> String {
    format!("struct:{path}:{kind}:{offset}")
}

fn spatial_module_node_id(module_path: &Option<String>, source_set: &Option<String>) -> String {
    format!(
        "module:{}:{}",
        module_path.as_deref().unwrap_or("workspace"),
        source_set.as_deref().unwrap_or("main")
    )
}

fn spatial_module_label(module_path: &Option<String>, source_set: &Option<String>) -> String {
    format!(
        "{}/{}",
        module_path.as_deref().unwrap_or("workspace"),
        source_set.as_deref().unwrap_or("main")
    )
}

fn source_structural_nodes(path: &str) -> Vec<IndexedStructuralNode> {
    let Ok(content) = fs::read_to_string(path) else {
        return Vec::new();
    };
    let mut nodes = Vec::new();
    let mut offset = 0usize;
    let mut import_block: Option<(usize, usize)> = None;
    for raw_line in content.split_inclusive('\n') {
        let line = raw_line.trim_end_matches(['\r', '\n']);
        let trimmed = line.trim();
        if trimmed.starts_with("import ") {
            let (start, count) = import_block.unwrap_or((offset, 0));
            import_block = Some((start, count + 1));
            offset += raw_line.len();
            continue;
        }
        flush_import_block(path, &mut nodes, &mut import_block);
        if trimmed.starts_with("package ") {
            push_structural_node(path, &mut nodes, "PACKAGE_DIRECTIVE", trimmed, offset);
        } else if let Some((kind, label)) = structural_block_kind(trimmed) {
            push_structural_node(path, &mut nodes, kind, &label, offset);
        }
        offset += raw_line.len();
        if nodes.len() >= SPATIAL_MAX_STRUCTURAL_NODES_PER_FILE {
            break;
        }
    }
    flush_import_block(path, &mut nodes, &mut import_block);
    nodes.truncate(SPATIAL_MAX_STRUCTURAL_NODES_PER_FILE);
    nodes
}

fn flush_import_block(
    path: &str,
    nodes: &mut Vec<IndexedStructuralNode>,
    import_block: &mut Option<(usize, usize)>,
) {
    if let Some((offset, count)) = import_block.take() {
        push_structural_node(
            path,
            nodes,
            "IMPORT_LIST",
            &format!("imports ({count})"),
            offset,
        );
    }
}

fn push_structural_node(
    path: &str,
    nodes: &mut Vec<IndexedStructuralNode>,
    kind: &str,
    label: &str,
    offset: usize,
) {
    if nodes.len() >= SPATIAL_MAX_STRUCTURAL_NODES_PER_FILE {
        return;
    }
    nodes.push(IndexedStructuralNode {
        label: truncate_chars(label, 42),
        kind: kind.to_string(),
        path: path.to_string(),
        offset: offset.min(i64::MAX as usize) as i64,
    });
}

fn structural_block_kind(trimmed: &str) -> Option<(&'static str, String)> {
    let normalized = trimmed.trim_start_matches('}').trim_start();
    let token = normalized
        .split(|ch: char| !ch.is_alphanumeric() && ch != '_')
        .find(|part| !part.is_empty())?;
    let kind = match token {
        "if" => "IF_EXPRESSION",
        "else" => "ELSE_BRANCH",
        "when" => "WHEN_EXPRESSION",
        "for" => "FOR_LOOP",
        "while" => "WHILE_LOOP",
        "try" => "TRY_EXPRESSION",
        "catch" => "CATCH_CLAUSE",
        "finally" => "FINALLY_BLOCK",
        "init" => "INIT_BLOCK",
        _ => return None,
    };
    Some((kind, normalized.trim_end_matches('{').trim().to_string()))
}

fn spatial_node_exists(tree: &SpatialTree, node_id: &str) -> bool {
    tree.nodes.iter().any(|node| node.id == node_id)
}

fn spatial_node<'a>(tree: &'a SpatialTree, node_id: &str) -> Option<&'a SpatialTreeNode> {
    tree.nodes.iter().find(|node| node.id == node_id)
}

fn spatial_children_map(tree: &SpatialTree) -> BTreeMap<String, Vec<String>> {
    let mut children: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for edge in &tree.edges {
        if edge.kind == SpatialRenderedEdgeKind::Containment {
            children
                .entry(edge.source_id.clone())
                .or_default()
                .push(edge.target_id.clone());
        }
    }
    children
}

fn spatial_parent_map(tree: &SpatialTree) -> BTreeMap<String, String> {
    tree.nodes
        .iter()
        .filter_map(|node| {
            node.parent_id
                .as_ref()
                .map(|parent_id| (node.id.clone(), parent_id.clone()))
        })
        .collect()
}

fn spatial_path_ids(tree: &SpatialTree, node_id: &str) -> Vec<String> {
    let parents = spatial_parent_map(tree);
    let mut path = vec![node_id.to_string()];
    let mut current = node_id.to_string();
    while let Some(parent) = parents.get(&current) {
        path.push(parent.clone());
        current = parent.clone();
    }
    path.reverse();
    path
}

fn compute_spatial_metrics(tree: &mut SpatialTree) {
    let children = spatial_children_map(tree);
    let mut depths = BTreeMap::new();
    assign_spatial_depths(&tree.root_id, 0, &children, &mut depths);
    let mut subtree_sizes = BTreeMap::new();
    compute_spatial_subtree_size(&tree.root_id, &children, &mut subtree_sizes);
    for node in &mut tree.nodes {
        node.child_count = children.get(&node.id).map(Vec::len).unwrap_or(0);
        node.metrics.depth = depths.get(&node.id).copied().unwrap_or(0);
        node.metrics.subtree_size = subtree_sizes.get(&node.id).copied().unwrap_or(1);
    }
}

fn assign_spatial_depths(
    node_id: &str,
    depth: usize,
    children: &BTreeMap<String, Vec<String>>,
    depths: &mut BTreeMap<String, usize>,
) {
    depths.insert(node_id.to_string(), depth);
    if let Some(child_ids) = children.get(node_id) {
        for child_id in child_ids {
            assign_spatial_depths(child_id, depth + 1, children, depths);
        }
    }
}

fn compute_spatial_subtree_size(
    node_id: &str,
    children: &BTreeMap<String, Vec<String>>,
    subtree_sizes: &mut BTreeMap<String, usize>,
) -> usize {
    let size = children
        .get(node_id)
        .map(|child_ids| {
            1 + child_ids
                .iter()
                .map(|child_id| compute_spatial_subtree_size(child_id, children, subtree_sizes))
                .sum::<usize>()
        })
        .unwrap_or(1);
    subtree_sizes.insert(node_id.to_string(), size);
    size
}

fn apply_spatial_collapse(
    tree: &mut SpatialTree,
    collapsed_node_ids: &BTreeSet<String>,
    selected_path_ids: &[String],
) {
    let ancestor_ids: BTreeSet<_> = selected_path_ids
        .iter()
        .take(selected_path_ids.len().saturating_sub(1))
        .cloned()
        .collect();
    for node in &mut tree.nodes {
        let auto_collapse = node.metrics.depth >= SPATIAL_AUTO_COLLAPSE_DEPTH
            && node.metrics.subtree_size >= SPATIAL_AUTO_COLLAPSE_SUBTREE_SIZE;
        let wide_collapse = node.metrics.depth > 1 && node.child_count > 100;
        node.collapsed = (collapsed_node_ids.contains(&node.id) || auto_collapse || wide_collapse)
            && !ancestor_ids.contains(&node.id);
    }
}

fn project_spatial_tree(
    request: SpatialProjectionRequest<'_>,
) -> (Vec<RenderedSpatialNode>, Vec<RenderedSpatialEdge>) {
    let tree = request.tree;
    let selected_node_id = request.selected_node_id;
    let children = spatial_children_map(tree);
    let visible_ids = focused_spatial_node_ids(&request, &children);
    let visible_ids = cap_visible_spatial_nodes(visible_ids, tree, selected_node_id);
    let visible_id_set: BTreeSet<_> = visible_ids.iter().cloned().collect();
    let placements = spatial_layout(tree, &children, &visible_id_set, selected_node_id);
    let projected = projected_spatial_points(
        &placements,
        request.camera,
        request.viewport_width,
        request.viewport_height,
    );
    let mut visible_nodes = Vec::new();
    for node_id in &visible_ids {
        let Some(node) = spatial_node(tree, node_id) else {
            continue;
        };
        let Some(placement) = placements.get(node_id) else {
            continue;
        };
        let Some((screen_x, screen_y)) = projected.get(node_id).copied() else {
            continue;
        };
        let visible_child_count = children
            .get(node_id)
            .map(|child_ids| {
                child_ids
                    .iter()
                    .filter(|child_id| visible_id_set.contains(*child_id))
                    .count()
            })
            .unwrap_or(0);
        if screen_x < 0.0
            || screen_y < 0.0
            || screen_x >= request.viewport_width as f32
            || screen_y >= request.viewport_height as f32
        {
            continue;
        }
        visible_nodes.push(RenderedSpatialNode {
            node_id: node.id.clone(),
            label: rendered_spatial_label(node, placement.label_lod),
            kind: node.kind.clone(),
            identity: node.identity,
            x: placement.x,
            y: placement.y,
            z: placement.z,
            screen_x,
            screen_y,
            radius: placement.radius,
            label_lod: placement.label_lod,
            selected: node.id == selected_node_id,
            collapsed: node.collapsed || node.child_count > visible_child_count,
            child_count: node.child_count,
        });
    }

    let mut visible_edges = Vec::new();
    for edge in &tree.edges {
        if edge.kind != SpatialRenderedEdgeKind::Containment
            || !visible_id_set.contains(&edge.source_id)
            || !visible_id_set.contains(&edge.target_id)
        {
            continue;
        }
        if let Some(rendered) = rendered_spatial_edge(edge, false, &projected) {
            visible_edges.push(rendered);
        }
    }
    visible_edges.extend(project_overlay_edges(
        selected_node_id,
        request.overlay_mode,
        request.incoming,
        request.outgoing,
        &visible_id_set,
        &projected,
    ));
    (visible_nodes, visible_edges)
}

fn focused_spatial_node_ids(
    request: &SpatialProjectionRequest<'_>,
    children: &BTreeMap<String, Vec<String>>,
) -> Vec<String> {
    let tree = request.tree;
    let selected_node_id = request.selected_node_id;
    let mut retained = BTreeSet::new();
    retain_spatial_path(tree, &mut retained, selected_node_id);
    let selected_path = spatial_path_ids(tree, selected_node_id);

    if selected_node_id == tree.root_id {
        retain_ranked_spatial_children(
            tree,
            children,
            &mut retained,
            &tree.root_id,
            None,
            SPATIAL_CONTEXT_CHILD_LIMIT * 2,
        );
    }

    for window in selected_path.windows(2) {
        let parent_id = &window[0];
        let preferred_child_id = &window[1];
        retain_ranked_spatial_children(
            tree,
            children,
            &mut retained,
            parent_id,
            Some(preferred_child_id),
            SPATIAL_CONTEXT_CHILD_LIMIT,
        );
    }

    retain_ranked_spatial_children(
        tree,
        children,
        &mut retained,
        selected_node_id,
        None,
        SPATIAL_CONTEXT_CHILD_LIMIT,
    );

    retain_relation_context(tree, &mut retained, request.incoming, request.outgoing);

    tree.nodes
        .iter()
        .filter(|node| retained.contains(&node.id))
        .map(|node| node.id.clone())
        .collect()
}

fn retain_spatial_path(tree: &SpatialTree, retained: &mut BTreeSet<String>, node_id: &str) {
    for path_id in spatial_path_ids(tree, node_id) {
        retained.insert(path_id);
    }
}

fn retain_ranked_spatial_children(
    tree: &SpatialTree,
    children: &BTreeMap<String, Vec<String>>,
    retained: &mut BTreeSet<String>,
    parent_id: &str,
    preferred_child_id: Option<&String>,
    limit: usize,
) {
    let Some(child_ids) = children.get(parent_id) else {
        return;
    };
    let mut ranked = child_ids.clone();
    ranked.sort_by(|left, right| {
        let left_preferred = preferred_child_id == Some(left);
        let right_preferred = preferred_child_id == Some(right);
        right_preferred
            .cmp(&left_preferred)
            .then_with(|| spatial_node_score(tree, right).cmp(&spatial_node_score(tree, left)))
            .then_with(|| spatial_node_label(tree, left).cmp(&spatial_node_label(tree, right)))
            .then_with(|| left.cmp(right))
    });
    for child_id in ranked.into_iter().take(limit) {
        retained.insert(child_id);
    }
}

fn retain_relation_context(
    tree: &SpatialTree,
    retained: &mut BTreeSet<String>,
    incoming: &[SymbolRelation],
    outgoing: &[SymbolRelation],
) {
    let mut relations: Vec<_> = incoming.iter().chain(outgoing.iter()).collect();
    relations.sort_by(|left, right| {
        right
            .references
            .cmp(&left.references)
            .then_with(|| left.simple_name.cmp(&right.simple_name))
            .then_with(|| left.fq_name.cmp(&right.fq_name))
    });
    for relation in relations
        .into_iter()
        .filter_map(|relation| relation.fq_name.as_deref())
        .take(SPATIAL_CONTEXT_RELATION_LIMIT)
    {
        let node_id = spatial_symbol_node_id(relation);
        if spatial_node_exists(tree, &node_id) {
            retain_spatial_path(tree, retained, &node_id);
        }
    }
}

fn spatial_node_score(tree: &SpatialTree, node_id: &str) -> usize {
    spatial_node(tree, node_id)
        .map(|node| {
            node.metrics.incoming_references
                + node.metrics.outgoing_references
                + node.metrics.subtree_size.min(100)
        })
        .unwrap_or(0)
}

fn spatial_node_label(tree: &SpatialTree, node_id: &str) -> String {
    spatial_node(tree, node_id)
        .map(|node| node.label.clone())
        .unwrap_or_else(|| node_id.to_string())
}

fn cap_visible_spatial_nodes(
    visible_ids: Vec<String>,
    tree: &SpatialTree,
    selected_node_id: &str,
) -> Vec<String> {
    if visible_ids.len() <= SPATIAL_MAX_VISIBLE_NODES {
        return visible_ids;
    }

    let selected_path: BTreeSet<_> = spatial_path_ids(tree, selected_node_id)
        .into_iter()
        .collect();
    let visible_id_set: BTreeSet<_> = visible_ids.iter().cloned().collect();
    let mut retained: BTreeSet<String> = selected_path
        .intersection(&visible_id_set)
        .cloned()
        .collect();

    if retained.len() < SPATIAL_MAX_VISIBLE_NODES
        && let Some(selected_index) = visible_ids.iter().position(|id| id == selected_node_id)
    {
        let radius = SPATIAL_MAX_VISIBLE_NODES / 3;
        let start = selected_index.saturating_sub(radius);
        let end = selected_index
            .saturating_add(radius)
            .saturating_add(1)
            .min(visible_ids.len());
        for node_id in &visible_ids[start..end] {
            if retained.len() >= SPATIAL_MAX_VISIBLE_NODES {
                break;
            }
            retained.insert(node_id.clone());
        }
    }

    for node_id in &visible_ids {
        if retained.len() >= SPATIAL_MAX_VISIBLE_NODES {
            break;
        }
        retained.insert(node_id.clone());
    }

    visible_ids
        .into_iter()
        .filter(|node_id| retained.contains(node_id))
        .collect()
}

fn spatial_layout(
    tree: &SpatialTree,
    children: &BTreeMap<String, Vec<String>>,
    visible_id_set: &BTreeSet<String>,
    selected_node_id: &str,
) -> BTreeMap<String, SpatialPlacement> {
    let mut placements = BTreeMap::new();
    let mut next_x = 0.0;
    assign_spatial_layout(
        &tree.root_id,
        tree,
        children,
        visible_id_set,
        &mut placements,
        &mut next_x,
    );
    assign_spatial_lod(tree, children, selected_node_id, &mut placements);
    placements
}

fn assign_spatial_layout(
    node_id: &str,
    tree: &SpatialTree,
    children: &BTreeMap<String, Vec<String>>,
    visible_id_set: &BTreeSet<String>,
    placements: &mut BTreeMap<String, SpatialPlacement>,
    next_x: &mut f32,
) -> f32 {
    let Some(node) = spatial_node(tree, node_id) else {
        return *next_x;
    };
    let child_positions: Vec<f32> = if node.collapsed {
        Vec::new()
    } else {
        children
            .get(node_id)
            .into_iter()
            .flat_map(|child_ids| child_ids.iter())
            .filter(|child_id| visible_id_set.contains(*child_id))
            .map(|child_id| {
                assign_spatial_layout(child_id, tree, children, visible_id_set, placements, next_x)
            })
            .collect()
    };
    let x = if child_positions.is_empty() {
        let value = *next_x;
        *next_x += 1.0;
        value
    } else {
        let first = child_positions.first().copied().unwrap_or(*next_x);
        let last = child_positions.last().copied().unwrap_or(first);
        (first + last) / 2.0
    };
    placements.insert(
        node_id.to_string(),
        SpatialPlacement {
            node_id: node_id.to_string(),
            x,
            y: node.metrics.depth as f32,
            z: 0.0,
            radius: if node.collapsed { 1.3 } else { 1.0 },
            label_lod: LabelLevel::GlyphOnly,
        },
    );
    x
}

fn assign_spatial_lod(
    tree: &SpatialTree,
    children: &BTreeMap<String, Vec<String>>,
    selected_node_id: &str,
    placements: &mut BTreeMap<String, SpatialPlacement>,
) {
    let selected_path: BTreeSet<_> = spatial_path_ids(tree, selected_node_id)
        .into_iter()
        .collect();
    let selected_children: BTreeSet<_> = children
        .get(selected_node_id)
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .collect();
    let mut rendered_labels = 0;
    for node in &tree.nodes {
        let Some(placement) = placements.get_mut(&node.id) else {
            continue;
        };
        let desired = if node.id == selected_node_id {
            LabelLevel::FullLabel
        } else if selected_path.contains(&node.id)
            || selected_children.contains(&node.id)
            || node.metrics.depth <= 1
        {
            LabelLevel::ShortLabel
        } else {
            LabelLevel::GlyphOnly
        };
        placement.label_lod = match desired {
            LabelLevel::Hidden | LabelLevel::GlyphOnly => desired,
            _ if rendered_labels < SPATIAL_MAX_RENDERED_LABELS => {
                rendered_labels += 1;
                desired
            }
            _ => LabelLevel::GlyphOnly,
        };
    }
}

fn projected_spatial_points(
    placements: &BTreeMap<String, SpatialPlacement>,
    camera: &SpatialCamera,
    viewport_width: u16,
    viewport_height: u16,
) -> BTreeMap<String, (f32, f32)> {
    let raw_points: Vec<_> = placements
        .values()
        .map(|placement| {
            let oblique_x = match camera.projection {
                ProjectionMode::OrthographicTopDown => placement.x,
                ProjectionMode::OrthographicOblique => placement.x + placement.y * 0.45,
            };
            let oblique_y = match camera.projection {
                ProjectionMode::OrthographicTopDown => placement.y,
                ProjectionMode::OrthographicOblique => placement.y * 1.75,
            };
            (placement.node_id.clone(), oblique_x, oblique_y)
        })
        .collect();
    let min_x = raw_points
        .iter()
        .map(|(_, x, _)| *x)
        .fold(f32::INFINITY, f32::min);
    let max_x = raw_points
        .iter()
        .map(|(_, x, _)| *x)
        .fold(f32::NEG_INFINITY, f32::max);
    let min_y = raw_points
        .iter()
        .map(|(_, _, y)| *y)
        .fold(f32::INFINITY, f32::min);
    let max_y = raw_points
        .iter()
        .map(|(_, _, y)| *y)
        .fold(f32::NEG_INFINITY, f32::max);
    let range_x = (max_x - min_x).max(1.0);
    let range_y = (max_y - min_y).max(1.0);
    raw_points
        .into_iter()
        .map(|(node_id, x, y)| {
            let normalized_x =
                (((x - min_x) / range_x - 0.5) * camera.zoom) + 0.5 + camera.position.x * 0.03;
            let normalized_y =
                (((y - min_y) / range_y - 0.5) * camera.zoom) + 0.5 + camera.position.y * 0.03;
            (
                node_id,
                (
                    3.0 + normalized_x * (viewport_width.saturating_sub(6) as f32),
                    1.0 + normalized_y * (viewport_height.saturating_sub(3) as f32),
                ),
            )
        })
        .collect()
}

fn rendered_spatial_label(node: &SpatialTreeNode, label_lod: LabelLevel) -> String {
    match label_lod {
        LabelLevel::Hidden | LabelLevel::GlyphOnly => String::new(),
        LabelLevel::KindOnly => compact_kind(&node.kind),
        LabelLevel::ShortLabel => truncate_chars(&node.label, 18),
        LabelLevel::FullLabel => truncate_chars(&node.label, 42),
    }
}

fn rendered_spatial_edge(
    edge: &SpatialTreeEdge,
    overlay: bool,
    projected: &BTreeMap<String, (f32, f32)>,
) -> Option<RenderedSpatialEdge> {
    let (from_x, from_y) = projected.get(&edge.source_id).copied()?;
    let (to_x, to_y) = projected.get(&edge.target_id).copied()?;
    Some(RenderedSpatialEdge {
        source_id: edge.source_id.clone(),
        target_id: edge.target_id.clone(),
        kind: edge.kind,
        overlay,
        from_x,
        from_y,
        to_x,
        to_y,
    })
}

fn project_overlay_edges(
    selected_node_id: &str,
    overlay_mode: SpatialOverlayMode,
    incoming: &[SymbolRelation],
    outgoing: &[SymbolRelation],
    visible_id_set: &BTreeSet<String>,
    projected: &BTreeMap<String, (f32, f32)>,
) -> Vec<RenderedSpatialEdge> {
    if overlay_mode == SpatialOverlayMode::StructureOnly {
        return Vec::new();
    }
    let mut edges = Vec::new();
    for relation in incoming.iter().chain(outgoing.iter()) {
        if overlay_mode == SpatialOverlayMode::CallFlow && relation.edge_kind != "CALL" {
            continue;
        }
        let Some(fq_name) = &relation.fq_name else {
            continue;
        };
        let relation_node_id = spatial_symbol_node_id(fq_name);
        let (source_id, target_id) = if relation.direction == "incoming" {
            (relation_node_id, selected_node_id.to_string())
        } else {
            (selected_node_id.to_string(), relation_node_id)
        };
        if !visible_id_set.contains(&source_id) || !visible_id_set.contains(&target_id) {
            continue;
        }
        if let Some(edge) = rendered_spatial_edge(
            &SpatialTreeEdge {
                id: format!("overlay:{source_id}->{target_id}"),
                source_id,
                target_id,
                kind: if overlay_mode == SpatialOverlayMode::CallFlow {
                    SpatialRenderedEdgeKind::CallFlow
                } else {
                    SpatialRenderedEdgeKind::Reference
                },
            },
            true,
            projected,
        ) {
            edges.push(edge);
        }
        if edges.len() == SPATIAL_MAX_OVERLAY_EDGES {
            break;
        }
    }
    edges
}

fn spatial_overlays(
    active_mode: SpatialOverlayMode,
    incoming: &[SymbolRelation],
    outgoing: &[SymbolRelation],
) -> Vec<SpatialOverlay> {
    let reference_count = incoming.len() + outgoing.len();
    let call_count = incoming
        .iter()
        .chain(outgoing.iter())
        .filter(|relation| relation.edge_kind == "CALL")
        .count();
    [
        (SpatialOverlayMode::StructureOnly, 0),
        (SpatialOverlayMode::References, reference_count),
        (SpatialOverlayMode::CallFlow, call_count),
    ]
    .into_iter()
    .map(|(mode, edge_count)| SpatialOverlay {
        mode,
        enabled: mode == active_mode,
        edge_count: edge_count.min(SPATIAL_MAX_OVERLAY_EDGES),
        capped: edge_count > SPATIAL_MAX_OVERLAY_EDGES,
    })
    .collect()
}

impl TerminalFrameBuffer {
    fn new(width: u16, height: u16) -> Self {
        let cell = SpatialCell {
            glyph: ' ',
            fg: Color::Reset,
            bg: Color::Reset,
            depth: f32::INFINITY,
            node_id: None,
        };
        Self {
            width,
            height,
            cells: vec![cell; width as usize * height as usize],
        }
    }

    fn write_cell(
        &mut self,
        x: i16,
        y: i16,
        glyph: char,
        fg: Color,
        depth: f32,
        node_id: Option<String>,
    ) {
        if x < 0 || y < 0 || x >= self.width as i16 || y >= self.height as i16 {
            return;
        }
        let index = y as usize * self.width as usize + x as usize;
        if depth <= self.cells[index].depth {
            self.cells[index] = SpatialCell {
                glyph,
                fg,
                bg: Color::Reset,
                depth,
                node_id,
            };
        }
    }

    fn draw_line(&mut self, from: (i16, i16), to: (i16, i16), glyph: char, fg: Color, depth: f32) {
        let (from_x, from_y) = from;
        let (to_x, to_y) = to;
        let mut x = from_x;
        let mut y = from_y;
        let dx = (to_x - from_x).abs();
        let sx = if from_x < to_x { 1 } else { -1 };
        let dy = -(to_y - from_y).abs();
        let sy = if from_y < to_y { 1 } else { -1 };
        let mut error = dx + dy;
        loop {
            self.write_cell(x, y, glyph, fg, depth, None);
            if x == to_x && y == to_y {
                break;
            }
            let doubled = 2 * error;
            if doubled >= dy {
                error += dy;
                x += sx;
            }
            if doubled <= dx {
                error += dx;
                y += sy;
            }
        }
    }

    fn draw_label(&mut self, x: i16, y: i16, label: &str, fg: Color, depth: f32) {
        for (offset, glyph) in label.chars().enumerate() {
            self.write_cell(x + offset as i16, y, glyph, fg, depth, None);
        }
    }

    fn draw_label_if_clear(&mut self, x: i16, y: i16, label: &str, fg: Color, depth: f32) -> bool {
        if !self.can_draw_label(x, y, label) {
            return false;
        }
        self.draw_label(x, y, label, fg, depth);
        true
    }

    fn can_draw_label(&self, x: i16, y: i16, label: &str) -> bool {
        for (offset, _) in label.chars().enumerate() {
            let cell_x = x + offset as i16;
            if cell_x < 0 || y < 0 || cell_x >= self.width as i16 || y >= self.height as i16 {
                continue;
            }
            let index = y as usize * self.width as usize + cell_x as usize;
            if !self.cells[index].is_label_space() {
                return false;
            }
        }
        true
    }

    fn lines(&self) -> Vec<Line<'static>> {
        self.cells
            .chunks(self.width as usize)
            .map(|row| {
                let mut spans = Vec::new();
                let mut current = String::new();
                let mut style = Style::default();
                for cell in row {
                    let cell_style = Style::default().fg(cell.fg).bg(cell.bg);
                    let _node_id = &cell.node_id;
                    if current.is_empty() || cell_style == style {
                        current.push(cell.glyph);
                        style = cell_style;
                    } else {
                        spans.push(Span::styled(std::mem::take(&mut current), style));
                        current.push(cell.glyph);
                        style = cell_style;
                    }
                }
                if !current.is_empty() {
                    spans.push(Span::styled(current, style));
                }
                Line::from(spans)
            })
            .collect()
    }
}

impl SpatialCell {
    fn is_label_space(&self) -> bool {
        self.glyph == ' ' || (self.node_id.is_none() && self.glyph == '.')
    }
}

fn render_spatial_framebuffer(
    snapshot: &SpatialAstSnapshot,
    width: u16,
    height: u16,
) -> TerminalFrameBuffer {
    let mut buffer = TerminalFrameBuffer::new(width, height);
    for edge in &snapshot.visible_edges {
        let (from_x, from_y) = spatial_canvas_point(edge.from_x, edge.from_y, width, height);
        let (to_x, to_y) = spatial_canvas_point(edge.to_x, edge.to_y, width, height);
        let (glyph, color, depth) = if edge.overlay {
            match edge.kind {
                SpatialRenderedEdgeKind::CallFlow => ('*', Color::Yellow, 0.6),
                _ => ('.', Color::Magenta, 0.7),
            }
        } else {
            ('.', Color::DarkGray, 0.9)
        };
        buffer.draw_line((from_x, from_y), (to_x, to_y), glyph, color, depth);
    }
    for node in &snapshot.visible_nodes {
        let (x, y) = spatial_canvas_point(node.screen_x, node.screen_y, width, height);
        let glyph = spatial_node_glyph(node);
        let color = spatial_node_color(node);
        let depth = if node.selected { 0.0 } else { 0.3 };
        buffer.write_cell(x, y, glyph, color, depth, Some(node.node_id.clone()));
    }
    for node in &snapshot.visible_nodes {
        let (x, y) = spatial_canvas_point(node.screen_x, node.screen_y, width, height);
        let color = spatial_node_color(node);
        let depth = if node.selected { 0.0 } else { 0.3 };
        if !node.label.is_empty() {
            if node.selected {
                buffer.draw_label(x + 2, y, &node.label, color, depth);
            } else {
                buffer.draw_label_if_clear(x + 2, y, &node.label, color, depth);
            }
        }
    }
    buffer
}

fn spatial_canvas_point(x: f32, y: f32, width: u16, height: u16) -> (i16, i16) {
    let scaled_x = if SPATIAL_VIEWPORT_WIDTH <= 1 {
        0.0
    } else {
        x / (SPATIAL_VIEWPORT_WIDTH - 1) as f32 * width.saturating_sub(1) as f32
    };
    let scaled_y = if SPATIAL_VIEWPORT_HEIGHT <= 1 {
        0.0
    } else {
        y / (SPATIAL_VIEWPORT_HEIGHT - 1) as f32 * height.saturating_sub(1) as f32
    };
    (scaled_x.round() as i16, scaled_y.round() as i16)
}

fn spatial_node_glyph(node: &RenderedSpatialNode) -> char {
    if node.selected {
        '*'
    } else if node.collapsed {
        '+'
    } else {
        match node.identity {
            SpatialNodeIdentity::CompilerSymbol => '@',
            SpatialNodeIdentity::SourceIndexDeclaration => 'o',
            SpatialNodeIdentity::FileOutlineNode => '#',
            SpatialNodeIdentity::LiteralAstNode => ':',
            SpatialNodeIdentity::SyntheticAggregate => 'O',
            SpatialNodeIdentity::StructuralOnly => '.',
        }
    }
}

fn spatial_node_color(node: &RenderedSpatialNode) -> Color {
    if node.selected {
        return Color::Yellow;
    }
    match node.identity {
        SpatialNodeIdentity::CompilerSymbol => Color::Cyan,
        SpatialNodeIdentity::SourceIndexDeclaration => Color::Green,
        SpatialNodeIdentity::FileOutlineNode => Color::Blue,
        SpatialNodeIdentity::LiteralAstNode => Color::White,
        SpatialNodeIdentity::SyntheticAggregate => Color::DarkGray,
        SpatialNodeIdentity::StructuralOnly => Color::Gray,
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

fn spatial_overlay_title(mode: SpatialOverlayMode) -> &'static str {
    match mode {
        SpatialOverlayMode::StructureOnly => "structure",
        SpatialOverlayMode::References => "references",
        SpatialOverlayMode::CallFlow => "call-flow",
    }
}

fn spatial_projection_title(mode: ProjectionMode) -> &'static str {
    match mode {
        ProjectionMode::OrthographicTopDown => "top-down",
        ProjectionMode::OrthographicOblique => "oblique",
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
    fn spatial_projection_keeps_selected_node_visible() {
        let tree = sample_spatial_tree(false);
        let camera = SpatialCamera::default();
        let (nodes, edges) = project_spatial_tree(SpatialProjectionRequest {
            tree: &tree,
            selected_node_id: "symbol:app.A",
            camera: &camera,
            overlay_mode: SpatialOverlayMode::StructureOnly,
            incoming: &[],
            outgoing: &[],
            viewport_width: SPATIAL_VIEWPORT_WIDTH,
            viewport_height: SPATIAL_VIEWPORT_HEIGHT,
        });

        let selected = nodes
            .iter()
            .find(|node| node.node_id == "symbol:app.A")
            .expect("selected node");
        assert!(selected.selected);
        assert_eq!(selected.label, "A");
        assert!(
            selected.screen_x >= 0.0 && selected.screen_x < SPATIAL_VIEWPORT_WIDTH as f32,
            "selected x should be inside viewport"
        );
        assert!(
            selected.screen_y >= 0.0 && selected.screen_y < SPATIAL_VIEWPORT_HEIGHT as f32,
            "selected y should be inside viewport"
        );
        assert!(
            edges
                .iter()
                .any(|edge| edge.kind == SpatialRenderedEdgeKind::Containment),
            "containment edges should be rendered"
        );
    }

    #[test]
    fn spatial_framebuffer_renders_collapsed_nodes_and_is_deterministic() {
        let tree = sample_spatial_tree(true);
        let camera = SpatialCamera::default();
        let (visible_nodes, visible_edges) = project_spatial_tree(SpatialProjectionRequest {
            tree: &tree,
            selected_node_id: "workspace",
            camera: &camera,
            overlay_mode: SpatialOverlayMode::StructureOnly,
            incoming: &[],
            outgoing: &[],
            viewport_width: SPATIAL_VIEWPORT_WIDTH,
            viewport_height: SPATIAL_VIEWPORT_HEIGHT,
        });
        let snapshot = SpatialAstSnapshot {
            mode: "spatialAst",
            workspace_root: "/workspace".to_string(),
            database: "/workspace/.gradle/kast/cache/source-index.db".to_string(),
            query: String::new(),
            current: None,
            tree,
            camera,
            selection: SpatialSelection {
                node_id: "workspace".to_string(),
                symbol_fq_name: None,
                path: vec!["workspace".to_string()],
            },
            visible_nodes,
            visible_edges,
            overlays: Vec::new(),
            incoming: Vec::new(),
            outgoing: Vec::new(),
            preview: SourcePreview::message("No source preview"),
            trail: Vec::new(),
            index: DemoIndex {
                symbol_count: 1,
                file_count: 1,
                reference_count: 0,
                confidence: DemoConfidence {
                    level: "HIGH".to_string(),
                    index_completeness: 1.0,
                    semantic_basis: "K2_RESOLVED".to_string(),
                },
            },
        };

        let first = framebuffer_text(&render_spatial_framebuffer(&snapshot, 60, 18));
        let second = framebuffer_text(&render_spatial_framebuffer(&snapshot, 60, 18));
        assert_eq!(first, second);
        assert!(first.contains('*'), "selected node should be visible");
        assert!(first.contains('+'), "collapsed subtree should be visible");
        assert!(first.contains("A.kt"), "collapsed file label should render");
    }

    #[test]
    fn spatial_projection_keeps_selected_node_when_visible_tree_is_capped() {
        let mut tree = sample_large_spatial_tree(650);
        let selected_node_id = "symbol:demo.Symbol620";
        let camera = SpatialCamera::default();
        let incoming = vec![sample_relation("incoming", "demo.Symbol10", "CALL", 8)];
        let (visible_nodes, _) = project_spatial_tree(SpatialProjectionRequest {
            tree: &tree,
            selected_node_id,
            camera: &camera,
            overlay_mode: SpatialOverlayMode::StructureOnly,
            incoming: &incoming,
            outgoing: &[],
            viewport_width: SPATIAL_VIEWPORT_WIDTH,
            viewport_height: SPATIAL_VIEWPORT_HEIGHT,
        });

        assert!(
            visible_nodes.len() <= SPATIAL_MAX_VISIBLE_NODES,
            "projection should keep the visible-node cap"
        );
        assert!(
            visible_nodes.len() < 60,
            "large trees should render as a focused slice, not an unreadable wall"
        );
        assert!(
            visible_nodes
                .iter()
                .any(|node| node.node_id == selected_node_id && node.selected),
            "selected node should remain renderable after capping"
        );
        assert!(
            visible_nodes
                .iter()
                .any(|node| node.node_id == "symbol:demo.Symbol10"),
            "relation context should stay visible so overlays have endpoints"
        );

        let (_, reference_edges) = project_spatial_tree(SpatialProjectionRequest {
            tree: &tree,
            selected_node_id,
            camera: &camera,
            overlay_mode: SpatialOverlayMode::References,
            incoming: &incoming,
            outgoing: &[],
            viewport_width: SPATIAL_VIEWPORT_WIDTH,
            viewport_height: SPATIAL_VIEWPORT_HEIGHT,
        });
        assert!(
            reference_edges
                .iter()
                .any(|edge| edge.overlay && edge.kind == SpatialRenderedEdgeKind::Reference),
            "reference overlay should connect retained relation nodes"
        );

        let (_, call_edges) = project_spatial_tree(SpatialProjectionRequest {
            tree: &tree,
            selected_node_id,
            camera: &camera,
            overlay_mode: SpatialOverlayMode::CallFlow,
            incoming: &incoming,
            outgoing: &[],
            viewport_width: SPATIAL_VIEWPORT_WIDTH,
            viewport_height: SPATIAL_VIEWPORT_HEIGHT,
        });
        assert!(
            call_edges
                .iter()
                .any(|edge| edge.overlay && edge.kind == SpatialRenderedEdgeKind::CallFlow),
            "call-flow overlay should connect retained call relations"
        );

        tree.nodes
            .iter_mut()
            .find(|node| node.id == "module::demo:main")
            .expect("module node")
            .collapsed = true;
        let (collapsed_nodes, _) = project_spatial_tree(SpatialProjectionRequest {
            tree: &tree,
            selected_node_id: "module::demo:main",
            camera: &camera,
            overlay_mode: SpatialOverlayMode::StructureOnly,
            incoming: &[],
            outgoing: &[],
            viewport_width: SPATIAL_VIEWPORT_WIDTH,
            viewport_height: SPATIAL_VIEWPORT_HEIGHT,
        });
        assert!(
            collapsed_nodes
                .iter()
                .any(|node| node.node_id == "module::demo:main" && node.collapsed),
            "collapsed selected aggregate should remain visible"
        );
    }

    #[test]
    fn source_structural_nodes_distinguish_noncompiler_ast_shape() {
        let temp = tempfile::tempdir().expect("tempdir");
        let file = temp.path().join("Shape.kt");
        fs::write(
            &file,
            r#"package demo.shape

import demo.A
import demo.B

class Shape {
    fun draw(value: Int) {
        if (value > 0) {
            when (value) {
                1 -> demo.A()
                else -> demo.B()
            }
        }
    }
}
"#,
        )
        .expect("source");

        let nodes = source_structural_nodes(file.to_str().expect("file path"));
        let kinds: BTreeSet<_> = nodes.iter().map(|node| node.kind.as_str()).collect();

        assert!(kinds.contains("PACKAGE_DIRECTIVE"));
        assert!(kinds.contains("IMPORT_LIST"));
        assert!(kinds.contains("IF_EXPRESSION"));
        assert!(kinds.contains("WHEN_EXPRESSION"));
        assert!(
            nodes
                .iter()
                .all(|node| node.path == file.display().to_string() && node.offset >= 0)
        );
        assert!(nodes.len() <= SPATIAL_MAX_STRUCTURAL_NODES_PER_FILE);
    }

    fn sample_spatial_tree(collapse_file: bool) -> SpatialTree {
        let root_id = "workspace".to_string();
        let file_id = "file:/workspace/app/A.kt".to_string();
        let symbol_id = "symbol:app.A".to_string();
        let mut tree = SpatialTree {
            root_id: root_id.clone(),
            nodes: vec![
                spatial_tree_node(
                    root_id.clone(),
                    None,
                    "workspace".to_string(),
                    "WORKSPACE".to_string(),
                    SpatialNodeIdentity::SyntheticAggregate,
                    None,
                    None,
                    None,
                    None,
                    0,
                    0,
                ),
                spatial_tree_node(
                    file_id.clone(),
                    Some(root_id.clone()),
                    "A.kt".to_string(),
                    "FILE".to_string(),
                    SpatialNodeIdentity::FileOutlineNode,
                    Some("/workspace/app/A.kt".to_string()),
                    None,
                    None,
                    None,
                    0,
                    0,
                ),
                spatial_tree_node(
                    symbol_id.clone(),
                    Some(file_id.clone()),
                    "A".to_string(),
                    "CLASS".to_string(),
                    SpatialNodeIdentity::CompilerSymbol,
                    Some("/workspace/app/A.kt".to_string()),
                    Some(SourceSpan {
                        start_offset: 0,
                        end_offset: None,
                    }),
                    Some(0),
                    Some("app.A".to_string()),
                    1,
                    0,
                ),
            ],
            edges: vec![
                spatial_tree_edge(&root_id, &file_id),
                spatial_tree_edge(&file_id, &symbol_id),
            ],
        };
        compute_spatial_metrics(&mut tree);
        if collapse_file {
            tree.nodes
                .iter_mut()
                .find(|node| node.id == file_id)
                .expect("file node")
                .collapsed = true;
        }
        tree
    }

    fn sample_large_spatial_tree(symbol_count: usize) -> SpatialTree {
        let root_id = "workspace".to_string();
        let module_id = "module::demo:main".to_string();
        let mut tree = SpatialTree {
            root_id: root_id.clone(),
            nodes: vec![
                spatial_tree_node(
                    root_id.clone(),
                    None,
                    "workspace".to_string(),
                    "WORKSPACE".to_string(),
                    SpatialNodeIdentity::SyntheticAggregate,
                    None,
                    None,
                    None,
                    None,
                    0,
                    0,
                ),
                spatial_tree_node(
                    module_id.clone(),
                    Some(root_id.clone()),
                    ":demo/main".to_string(),
                    "MODULE".to_string(),
                    SpatialNodeIdentity::SyntheticAggregate,
                    None,
                    None,
                    None,
                    None,
                    0,
                    0,
                ),
            ],
            edges: vec![spatial_tree_edge(&root_id, &module_id)],
        };
        for index in 0..symbol_count {
            let file_id = format!("file:/workspace/demo/Symbol{index}.kt");
            let symbol_id = format!("symbol:demo.Symbol{index}");
            tree.nodes.push(spatial_tree_node(
                file_id.clone(),
                Some(module_id.clone()),
                format!("Symbol{index}.kt"),
                "FILE".to_string(),
                SpatialNodeIdentity::FileOutlineNode,
                Some(format!("/workspace/demo/Symbol{index}.kt")),
                None,
                None,
                None,
                0,
                0,
            ));
            tree.nodes.push(spatial_tree_node(
                symbol_id.clone(),
                Some(file_id.clone()),
                format!("Symbol{index}"),
                "CLASS".to_string(),
                SpatialNodeIdentity::SourceIndexDeclaration,
                Some(format!("/workspace/demo/Symbol{index}.kt")),
                Some(SourceSpan {
                    start_offset: 0,
                    end_offset: None,
                }),
                Some(0),
                Some(format!("demo.Symbol{index}")),
                0,
                0,
            ));
            tree.edges.push(spatial_tree_edge(&module_id, &file_id));
            tree.edges.push(spatial_tree_edge(&file_id, &symbol_id));
        }
        compute_spatial_metrics(&mut tree);
        tree
    }

    fn sample_relation(
        direction: &'static str,
        fq_name: &str,
        edge_kind: &str,
        references: i64,
    ) -> SymbolRelation {
        SymbolRelation {
            direction,
            fq_name: Some(fq_name.to_string()),
            label: simple_symbol_name(fq_name).to_string(),
            simple_name: simple_symbol_name(fq_name).to_string(),
            path: Some(format!(
                "/workspace/demo/{}.kt",
                simple_symbol_name(fq_name)
            )),
            offset: Some(0),
            edge_kind: edge_kind.to_string(),
            references,
            module_path: Some(":demo".to_string()),
            source_set: Some("main".to_string()),
            walkable: true,
        }
    }

    fn framebuffer_text(buffer: &TerminalFrameBuffer) -> String {
        buffer
            .cells
            .chunks(buffer.width as usize)
            .map(|row| row.iter().map(|cell| cell.glyph).collect::<String>())
            .collect::<Vec<_>>()
            .join("\n")
    }
}
