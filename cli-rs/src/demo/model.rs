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
