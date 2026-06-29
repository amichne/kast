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
    gradle_project: Option<String>,
    relative_path_prefix: Option<String>,
    #[serde(default)]
    production_only: bool,
    #[serde(default)]
    exclude_patterns: Vec<String>,
    #[serde(default)]
    usage_facets: Vec<UsageFacet>,
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
    usage_facets: Vec<UsageFacet>,
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
    usage_facets: Vec<UsageFacet>,
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
