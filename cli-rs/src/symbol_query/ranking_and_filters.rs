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
    fn criteria(&self) -> SymbolQueryFilterCriteria<'_> {
        SymbolQueryFilterCriteria {
            kinds: &self.kinds,
            visibility: &self.visibility,
            module_path: self.module_path.as_deref(),
            source_set: self.source_set.as_deref(),
            file_glob: self.file_glob.as_deref(),
            package_prefix: self.package_prefix.as_deref(),
            fq_name_prefix: self.fq_name_prefix.as_deref(),
            gradle_project: self.gradle_project.as_deref(),
            relative_path_prefix: self.relative_path_prefix.as_deref(),
            production_only: self.production_only,
            exclude_patterns: &self.exclude_patterns,
            usage_facets: &self.usage_facets,
        }
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

    fn result(&self, usage_facets: Vec<UsageFacet>) -> DeclarationResult {
        DeclarationResult {
            fq_id: self.fq_id,
            fq_name: self.fq_name.clone(),
            simple_name: self.simple_name.clone(),
            kind: self.kind.clone(),
            visibility: self.visibility.clone(),
            usage_facets,
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

    fn filter_input(&self) -> DeclarationFilterInput<'_> {
        DeclarationFilterInput {
            fq_name: &self.fq_name,
            kind: &self.kind,
            visibility: &self.visibility,
            absolute_path: &self.path,
            relative_path: &self.relative_path,
            filename: &self.filename,
            module_path: self.module_path.as_deref(),
            source_set: self.source_set.as_deref(),
            package_fq_name: self.package_fq_name.as_deref(),
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
    if let Some(gradle_project) = &filters.gradle_project {
        constraints.push(StructuralConstraint {
            field: "gradleProject",
            operator: "GRADLE_PREFIX",
            value: json!(gradle_project),
            source: "sqlite+derived",
        });
    }
    if let Some(relative_path_prefix) = &filters.relative_path_prefix {
        constraints.push(StructuralConstraint {
            field: "relativePathPrefix",
            operator: "PREFIX",
            value: json!(relative_path_prefix),
            source: "sqlite+derived",
        });
    }
    if filters.production_only {
        constraints.push(StructuralConstraint {
            field: "productionOnly",
            operator: "=",
            value: json!(true),
            source: "sqlite+derived",
        });
    }
    if !filters.exclude_patterns.is_empty() {
        constraints.push(StructuralConstraint {
            field: "excludePatterns",
            operator: "NOT_GLOB",
            value: json!(filters.exclude_patterns),
            source: "sqlite+derived",
        });
    }
    if !filters.usage_facets.is_empty() {
        constraints.push(StructuralConstraint {
            field: "usageFacets",
            operator: "ANY",
            value: json!(filters.usage_facets),
            source: "sqlite+derived",
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
    if let Some(gradle_project) = &filters.gradle_project {
        hard_filters.push(HardFilter {
            field: "gradleProject".to_string(),
            value: json!(gradle_project),
            source: "declarations.module_path",
            satisfied_symbolically: true,
        });
    }
    if let Some(relative_path_prefix) = &filters.relative_path_prefix {
        hard_filters.push(HardFilter {
            field: "relativePathPrefix".to_string(),
            value: json!(relative_path_prefix),
            source: "path_prefixes.dir_path + declarations.filename",
            satisfied_symbolically: true,
        });
    }
    if filters.production_only {
        hard_filters.push(HardFilter {
            field: "productionOnly".to_string(),
            value: json!(true),
            source: "declarations.source_set + declarations.module_path + relative_path",
            satisfied_symbolically: true,
        });
    }
    if !filters.exclude_patterns.is_empty() {
        hard_filters.push(HardFilter {
            field: "excludePatterns".to_string(),
            value: json!(filters.exclude_patterns),
            source: "declarations.module_path + relative_path",
            satisfied_symbolically: true,
        });
    }
    if !filters.usage_facets.is_empty() {
        hard_filters.push(HardFilter {
            field: "usageFacets".to_string(),
            value: json!(filters.usage_facets),
            source: "declarations + symbol_references + file_metadata + declaration_supertypes",
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
    lexical_tokens(query)
}

fn lexical_field_matches(
    terms: &[String],
    field: &'static str,
    evidence: &str,
) -> Vec<LexicalMatch> {
    let field_tokens = lexical_tokens(evidence);
    let lowered = evidence.to_ascii_lowercase();
    terms
        .iter()
        .filter_map(|term| {
            if field_tokens.iter().any(|token| token == term) {
                Some(LexicalMatch {
                    field,
                    term: term.clone(),
                    match_type: "TOKEN",
                    evidence: evidence.to_string(),
                })
            } else if lowered.contains(term) {
                Some(LexicalMatch {
                    field,
                    term: term.clone(),
                    match_type: "LIKE",
                    evidence: evidence.to_string(),
                })
            } else {
                None
            }
        })
        .collect()
}

fn lexical_tokens(value: &str) -> Vec<String> {
    let chars: Vec<char> = value.chars().collect();
    let mut tokens = Vec::new();
    let mut current = String::new();
    for (index, ch) in chars.iter().copied().enumerate() {
        if !ch.is_ascii_alphanumeric() {
            push_lexical_token(&mut tokens, &mut current);
            continue;
        }
        if let Some(previous) = current.chars().last()
            && is_camel_boundary(previous, ch, chars.get(index + 1).copied())
        {
            push_lexical_token(&mut tokens, &mut current);
        }
        current.push(ch);
    }
    push_lexical_token(&mut tokens, &mut current);
    tokens
}

fn is_camel_boundary(previous: char, current: char, next: Option<char>) -> bool {
    (previous.is_ascii_lowercase() && current.is_ascii_uppercase())
        || (previous.is_ascii_digit() && current.is_ascii_uppercase())
        || (previous.is_ascii_uppercase()
            && current.is_ascii_uppercase()
            && next.is_some_and(|ch| ch.is_ascii_lowercase()))
}

fn push_lexical_token(tokens: &mut Vec<String>, current: &mut String) {
    if current.is_empty() {
        return;
    }
    let token = current.to_ascii_lowercase();
    if !tokens.contains(&token) {
        tokens.push(token);
    }
    current.clear();
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
