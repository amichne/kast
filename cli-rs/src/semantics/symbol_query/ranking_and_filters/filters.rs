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
    let normalized_query = normalized_kotlin_identity(trimmed);
    let normalized_fq_name = normalized_kotlin_identity(&declaration.fq_name);
    let normalized_simple_name = normalized_kotlin_identity(&declaration.simple_name);
    let mut matches = Vec::new();
    if !trimmed.is_empty() && normalized_fq_name == normalized_query {
        matches.push(SignalMatch {
            field: "fq_names.fq_name",
            match_type: "EQUALS",
            evidence: Some(declaration.fq_name.clone()),
        });
    }
    if !trimmed.is_empty() && normalized_simple_name == normalized_query {
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

fn normalized_kotlin_identity(value: &str) -> String {
    value
        .split('.')
        .map(|segment| {
            segment
                .strip_prefix('`')
                .and_then(|segment| segment.strip_suffix('`'))
                .unwrap_or(segment)
        })
        .collect::<Vec<_>>()
        .join(".")
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
    rank_components_from_evidence(
        !candidate.exact_matches.is_empty(),
        &candidate.lexical_matches,
        candidate.graph_paths.len(),
        candidate.discovered_by_graph,
    )
}

fn rank_components_from_evidence(
    exact: bool,
    lexical_matches: &[LexicalMatch],
    graph_paths: usize,
    discovered_by_graph: bool,
) -> RankComponents {
    RankComponents {
        exact: if exact { 1.0 } else { 0.0 },
        lexical: lexical_rank_score(lexical_matches),
        structural: 1.0,
        graph: if graph_paths == 0 {
            if discovered_by_graph { 0.25 } else { 0.0 }
        } else {
            (graph_paths.min(5) as f64) / 5.0
        },
        semantic: None,
    }
}

fn sort_score(components: &RankComponents) -> f64 {
    components.exact + components.lexical + components.structural * 0.2 + components.graph * 0.5
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
