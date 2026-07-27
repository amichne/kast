fn repository_continuation_context(
    workspace_root: &WorkspaceRoot,
    snapshot: &CoverageSnapshot,
    params: &ValidatedRepositoryQueryParams,
) -> Result<RepositoryContinuationContext> {
    let workspace_root = workspace_root.as_path().to_str().ok_or_else(|| {
        CliError::new(
            "REPOSITORY_WORKSPACE_UNAVAILABLE",
            "repository workspace root is not valid UTF-8",
        )
    })?;
    let normalized_query = serde_json::to_vec(&(
        "repository/query",
        workspace_root,
        params.question.as_str(),
        params.question.syntax_canonical(),
        params.intent,
        params.canonical_key.as_deref(),
        &params.scope,
        &params.limits,
    ))?;
    let normalized_traversal_query = serde_json::to_vec(&(
        "repository/traversal",
        workspace_root,
        normalize_repository_question(params.question.as_str()),
        params.question.syntax_canonical(),
        params.intent,
        params.canonical_key.as_deref(),
        &params.scope,
        &snapshot.resolved_scope,
        &params.limits,
    ))?;
    Ok(RepositoryContinuationContext {
        workspace_root: workspace_root.to_string(),
        graph_generation: snapshot.generation,
        query_sha256: hex::encode(Sha256::digest(normalized_query)),
        traversal_query_sha256: hex::encode(Sha256::digest(normalized_traversal_query)),
        coverage_sha256: coverage_composition_sha256(snapshot)?,
    })
}

fn normalize_repository_question(question: &str) -> String {
    question
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn consume_repository_continuation(
    token: &RepositoryEvidenceContinuation,
    expected: &RepositoryContinuationContext,
) -> Result<RepositoryEvidenceResume> {
    let claims = runtime::verify_install_scoped_token::<RepositoryEvidenceContinuationClaims>(
        REPOSITORY_CONTINUATION_VERSION,
        &token.0,
    )
    .map_err(|_| {
        CliError::new(
            "REPOSITORY_CONTINUATION_UNAVAILABLE",
            "Repository continuation authentication is unavailable; start a new unpaged query.",
        )
    })?
    .ok_or_else(|| {
        invalid_repository_continuation(
            "Repository evidence continuation is malformed or failed authentication; start a new unpaged query.",
        )
    })?;
    if claims.schema_version != REPOSITORY_CONTINUATION_SCHEMA_VERSION {
        return Err(invalid_repository_continuation(
            "Repository evidence continuation schema is unsupported.",
        ));
    }
    if claims.workspace_root != expected.workspace_root
        || claims.query_sha256 != expected.query_sha256
    {
        return Err(invalid_repository_continuation(
            "Repository evidence continuation does not match this query; start a new unpaged query.",
        ));
    }
    if claims.graph_generation != expected.graph_generation
        || claims.coverage_sha256 != expected.coverage_sha256
    {
        return Err(CliError::new(
            "STALE_REPOSITORY_CONTINUATION",
            "Repository graph or coverage evidence changed; start a new unpaged query.",
        ));
    }
    if claims.resume.after_occurrence_id < 0 {
        return Err(invalid_repository_continuation(
            "Repository evidence continuation has an invalid occurrence identity.",
        ));
    }
    Ok(claims.resume)
}

fn consume_repository_traversal_continuation(
    token: &RepositoryTraversalContinuation,
    expected: &RepositoryContinuationContext,
) -> Result<RepositoryTraversalContinuationState> {
    let claims = runtime::verify_install_scoped_token::<RepositoryTraversalContinuationClaims>(
        REPOSITORY_TRAVERSAL_CONTINUATION_VERSION,
        &token.0,
    )
    .map_err(|_| {
        CliError::new(
            "REPOSITORY_CONTINUATION_UNAVAILABLE",
            "Repository traversal continuation authentication is unavailable; start a new unpaged query.",
        )
    })?
    .ok_or_else(|| {
        invalid_repository_continuation(
            "Repository traversal continuation is malformed or failed authentication; start a new unpaged query.",
        )
    })?;
    if claims.schema_version != REPOSITORY_TRAVERSAL_CONTINUATION_SCHEMA_VERSION {
        return Err(invalid_repository_continuation(
            "Repository traversal continuation schema is unsupported.",
        ));
    }
    if claims.query_sha256 != expected.traversal_query_sha256 {
        return Err(invalid_repository_continuation(
            "Repository traversal continuation does not match this query; start a new unpaged query.",
        ));
    }
    if claims.graph_generation != expected.graph_generation
        || claims.coverage_sha256 != expected.coverage_sha256
    {
        return Err(CliError::new(
            "STALE_REPOSITORY_CONTINUATION",
            "Repository graph or coverage evidence changed; start a new unpaged query.",
        ));
    }
    Ok(RepositoryTraversalContinuationState {
        canonical_start_key: claims.canonical_start_key,
        resume: claims.resume,
    })
}

fn issue_repository_continuation(
    context: &RepositoryContinuationContext,
    resume: RepositoryEvidenceResume,
) -> Result<RepositoryEvidenceContinuation> {
    runtime::sign_install_scoped_token(
        REPOSITORY_CONTINUATION_VERSION,
        &RepositoryEvidenceContinuationClaims {
            schema_version: REPOSITORY_CONTINUATION_SCHEMA_VERSION,
            workspace_root: context.workspace_root.clone(),
            graph_generation: context.graph_generation,
            coverage_sha256: context.coverage_sha256.clone(),
            query_sha256: context.query_sha256.clone(),
            resume,
        },
    )
    .map(RepositoryEvidenceContinuation)
    .map_err(|_| {
        CliError::new(
            "REPOSITORY_CONTINUATION_UNAVAILABLE",
            "Repository continuation signing is unavailable; retry the initial query.",
        )
    })
}

fn issue_repository_traversal_continuation(
    context: &RepositoryContinuationContext,
    canonical_start_key: &str,
    resume: RepositoryTraversalResume,
) -> Result<RepositoryTraversalContinuation> {
    runtime::sign_install_scoped_token(
        REPOSITORY_TRAVERSAL_CONTINUATION_VERSION,
        &RepositoryTraversalContinuationClaims {
            schema_version: REPOSITORY_TRAVERSAL_CONTINUATION_SCHEMA_VERSION,
            graph_generation: context.graph_generation,
            coverage_sha256: context.coverage_sha256.clone(),
            query_sha256: context.traversal_query_sha256.clone(),
            canonical_start_key: canonical_start_key.to_string(),
            resume,
        },
    )
    .map(RepositoryTraversalContinuation)
    .map_err(|_| {
        CliError::new(
            "REPOSITORY_CONTINUATION_UNAVAILABLE",
            "Repository traversal continuation signing is unavailable; retry the initial query.",
        )
    })
}

fn invalid_repository_continuation(message: &str) -> CliError {
    CliError::new("INVALID_REPOSITORY_CONTINUATION", message)
}

fn invalid_repository_query(message: &str) -> CliError {
    CliError::new("INVALID_REPOSITORY_QUERY", message)
}

fn repository_workspace_root(
    routed_root: Option<PathBuf>,
    request_root: Option<&str>,
) -> Result<WorkspaceRoot> {
    let routed_root = config::resolve_workspace_root(routed_root).map_err(|error| {
        CliError::new(
            "REPOSITORY_WORKSPACE_UNAVAILABLE",
            format!("cannot resolve the routed repository workspace root: {error}"),
        )
    })?;
    let routed_root = WorkspaceRoot::try_from(routed_root.as_path())
        .map_err(|error| CliError::new("REPOSITORY_WORKSPACE_UNAVAILABLE", error.to_string()))?;
    if let Some(request_root) = request_root {
        let request_root_value = request_root;
        let request_root = config::normalize(PathBuf::from(request_root_value));
        let request_root =
            WorkspaceRoot::try_from(request_root.as_path()).map_err(|error| {
                CliError::new(
                    "REPOSITORY_WORKSPACE_ROOT_MISMATCH",
                    format!(
                        "repository/query workspaceRoot {request_root_value:?} cannot match the CLI-routed workspace {}: {error}; remove workspaceRoot or make it match --workspace-root",
                        routed_root.as_path().display()
                    ),
                )
            })?;
        if request_root != routed_root {
            return Err(CliError::new(
                "REPOSITORY_WORKSPACE_ROOT_MISMATCH",
                format!(
                    "repository/query workspaceRoot resolves to {}, but the CLI route selected {}; remove workspaceRoot or make it match --workspace-root",
                    request_root.as_path().display(),
                    routed_root.as_path().display()
                ),
            ));
        }
    }
    Ok(routed_root)
}
