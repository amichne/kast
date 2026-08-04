fn graph_coverage(
    workspace_root: &Path,
    published: &crate::published_workspace::PublishedWorkspaceDatabase,
    params: GraphCoverageParams,
) -> Result<Value> {
    let scope = RepositoryScope::from(params.scope);
    validate_scope(&scope)?;
    if !(1..=MAX_FILE_LIMIT).contains(&params.limit) {
        return Err(CliError::new(
            "INVALID_GRAPH_COVERAGE_REQUEST",
            format!("coverage limit must be from 1 through {MAX_FILE_LIMIT}"),
        ));
    }
    let query_sha256 = graph_coverage_query_sha256(workspace_root, &scope, params.limit)?;
    let claims = params
        .continuation
        .as_ref()
        .map(|token| consume_graph_coverage_continuation(token, &query_sha256))
        .transpose()?;
    let has_continuation = claims.is_some();
    let snapshot = read_coverage_from_published(workspace_root, scope, false, published).map_err(|error| {
        if has_continuation
            && matches!(
                error.code,
                "INVALID_REPOSITORY_SCOPE" | "AMBIGUOUS_REPOSITORY_SCOPE"
            )
        {
            stale_graph_coverage_continuation()
        } else {
            error
        }
    })?;
    published.revalidate()?;
    let coverage_sha256 = coverage_composition_sha256(&snapshot)?;
    let start = claims
        .map(|claims| graph_coverage_resume_offset(&claims, &snapshot, &coverage_sha256))
        .transpose()?
        .unwrap_or(0);
    let files = snapshot
        .files
        .iter()
        .skip(start)
        .take(params.limit)
        .cloned()
        .collect::<Vec<_>>();
    let next_offset = start + files.len();
    let truncated = next_offset < snapshot.files.len();
    let continuation = truncated
        .then(|| {
            issue_graph_coverage_continuation(
                &query_sha256,
                &coverage_sha256,
                snapshot.generation,
                next_offset,
            )
        })
        .transpose()?;
    serde_json::to_value(GraphCoverageResult {
        result_type: "KAST_GRAPH_COVERAGE_RESULT",
        generation: snapshot.generation,
        inventory_generation: snapshot.generation,
        graph_generation: snapshot.generation,
        scope: snapshot.scope.clone(),
        applied_filters: snapshot.scope,
        coverage: snapshot.coverage,
        files,
        ordering: GRAPH_COVERAGE_ORDERING,
        truncated,
        continuation,
        schema_version: SCHEMA_VERSION,
    })
    .map_err(Into::into)
}

fn graph_coverage_query_sha256(
    workspace_root: &Path,
    scope: &RepositoryScope,
    limit: usize,
) -> Result<String> {
    let workspace_root = std::fs::canonicalize(workspace_root).map_err(|error| {
        CliError::new(
            "REPOSITORY_WORKSPACE_UNAVAILABLE",
            format!("cannot canonicalize repository workspace root: {error}"),
        )
    })?;
    let workspace_root = workspace_root.to_str().ok_or_else(|| {
        CliError::new(
            "REPOSITORY_WORKSPACE_UNAVAILABLE",
            "repository workspace root is not valid UTF-8",
        )
    })?;
    let query = serde_json::to_vec(&(
        "graph/coverage",
        GRAPH_COVERAGE_CONTINUATION_SCHEMA_VERSION,
        workspace_root,
        scope,
        limit,
        GRAPH_COVERAGE_ORDERING,
    ))?;
    Ok(hex::encode(Sha256::digest(query)))
}

fn consume_graph_coverage_continuation(
    token: &GraphCoverageContinuation,
    expected_query_sha256: &str,
) -> Result<GraphCoverageContinuationClaims> {
    let claims = runtime::verify_install_scoped_token::<GraphCoverageContinuationClaims>(
        GRAPH_COVERAGE_CONTINUATION_VERSION,
        &token.0,
    )
    .map_err(|_| {
        CliError::new(
            "GRAPH_COVERAGE_CONTINUATION_UNAVAILABLE",
            "Graph coverage continuation authentication is unavailable; start a new unpaged request.",
        )
    })?
    .ok_or_else(|| {
        invalid_graph_coverage_continuation(
            "Graph coverage continuation is malformed or failed authentication; start a new unpaged request.",
        )
    })?;
    if claims.schema_version != GRAPH_COVERAGE_CONTINUATION_SCHEMA_VERSION {
        return Err(invalid_graph_coverage_continuation(
            "Graph coverage continuation schema is unsupported.",
        ));
    }
    if claims.query_sha256 != expected_query_sha256 {
        return Err(invalid_graph_coverage_continuation(
            "Graph coverage continuation does not match this request; start a new unpaged request.",
        ));
    }
    Ok(claims)
}

fn graph_coverage_resume_offset(
    claims: &GraphCoverageContinuationClaims,
    snapshot: &CoverageSnapshot,
    coverage_sha256: &str,
) -> Result<usize> {
    if claims.graph_generation != snapshot.generation || claims.coverage_sha256 != coverage_sha256 {
        return Err(stale_graph_coverage_continuation());
    }
    let next_offset = usize::try_from(claims.next_offset).map_err(|_| {
        invalid_graph_coverage_continuation(
            "Graph coverage continuation has an invalid file offset.",
        )
    })?;
    if next_offset == 0 || next_offset >= snapshot.files.len() {
        return Err(invalid_graph_coverage_continuation(
            "Graph coverage continuation has an invalid file offset.",
        ));
    }
    Ok(next_offset)
}

fn issue_graph_coverage_continuation(
    query_sha256: &str,
    coverage_sha256: &str,
    graph_generation: u64,
    next_offset: usize,
) -> Result<GraphCoverageContinuation> {
    let next_offset = u64::try_from(next_offset).map_err(|_| {
        invalid_graph_coverage_continuation(
            "Graph coverage continuation has an invalid file offset.",
        )
    })?;
    runtime::sign_install_scoped_token(
        GRAPH_COVERAGE_CONTINUATION_VERSION,
        &GraphCoverageContinuationClaims {
            schema_version: GRAPH_COVERAGE_CONTINUATION_SCHEMA_VERSION,
            graph_generation,
            query_sha256: query_sha256.to_owned(),
            coverage_sha256: coverage_sha256.to_owned(),
            next_offset,
        },
    )
    .map(GraphCoverageContinuation)
    .map_err(|_| {
        CliError::new(
            "GRAPH_COVERAGE_CONTINUATION_UNAVAILABLE",
            "Graph coverage continuation signing is unavailable; retry the initial request.",
        )
    })
}

fn invalid_graph_coverage_continuation(message: &str) -> CliError {
    CliError::new("INVALID_GRAPH_COVERAGE_CONTINUATION", message)
}

fn stale_graph_coverage_continuation() -> CliError {
    CliError::new(
        "STALE_GRAPH_COVERAGE_CONTINUATION",
        "Graph generation, resolved scope, or coverage composition changed; start a new unpaged request.",
    )
}

fn coverage_composition_sha256(snapshot: &CoverageSnapshot) -> Result<String> {
    let composition = serde_json::to_vec(&(
        &snapshot.scope,
        &snapshot.resolved_scope,
        &snapshot.coverage,
        &snapshot.files,
    ))?;
    Ok(hex::encode(Sha256::digest(composition)))
}
