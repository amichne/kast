fn repository_query(
    workspace_root: &WorkspaceRoot,
    params: ValidatedRepositoryQueryParams,
) -> Result<Value> {
    let workspace_path = workspace_root.as_path();
    for _ in 0..2 {
        let snapshot = read_coverage(workspace_path, params.scope.clone())?;
        let mut connection = open_repository_connection(workspace_path)?;
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Deferred)
            .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        if repository_generation(&transaction)? != snapshot.generation {
            continue;
        }
        let response =
            repository_query_at_snapshot(workspace_root, &transaction, &params, snapshot)?;
        transaction
            .commit()
            .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        return Ok(response);
    }
    Err(CliError::new(
        "REPOSITORY_QUERY_UNSTABLE",
        "source-index generation moved twice between coverage admission and semantic execution",
    ))
}

fn repository_query_at_snapshot(
    workspace_root: &WorkspaceRoot,
    connection: &Connection,
    params: &ValidatedRepositoryQueryParams,
    snapshot: CoverageSnapshot,
) -> Result<Value> {
    let execution_scope = RepositoryExecutionScope::from_coverage(&snapshot);
    let continuation_context = repository_continuation_context(workspace_root, &snapshot, params)?;
    let traversal_resume = params
        .continuation
        .as_ref()
        .map(|token| consume_repository_traversal_continuation(token, &continuation_context))
        .transpose()?;
    let evidence_resume = params
        .evidence_continuation
        .as_ref()
        .map(|token| consume_repository_continuation(token, &continuation_context))
        .transpose()?;
    let result = match params.intent {
        RepositoryIntent::Resolve => resolve_repository_question(
            connection,
            &params.question,
            &execution_scope,
            params.limits.results,
            params.canonical_key.as_deref(),
        )?,
        RepositoryIntent::Path
        | RepositoryIntent::IncomingImpact
        | RepositoryIntent::OutgoingImpact => {
            let execution = RepositoryGraphExecution {
                request_scope: &params.scope,
                admitted: &execution_scope,
                limits: &params.limits,
            };
            graph_repository_question(
                connection,
                params.question.as_str(),
                params.intent,
                &execution,
                &continuation_context,
                traversal_resume.as_ref(),
                evidence_resume.as_ref(),
            )?
        }
        RepositoryIntent::Architecture => architecture_repository_question(
            connection,
            snapshot.generation,
            &params.scope,
            &execution_scope,
            &params.limits,
        )?,
        RepositoryIntent::ContextRelationship => context_repository_question(
            workspace_root,
            connection,
            params.question.as_str(),
            &params.scope,
            &execution_scope,
            &params.limits,
        )?,
    };
    let answered = result
        .get("answered")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let ambiguous = result
        .get("ambiguous")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let truncated = result
        .get("truncated")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let status = if ambiguous {
        "AMBIGUOUS"
    } else if answered {
        "ANSWERED"
    } else if snapshot.coverage.complete && !truncated {
        "EMPTY"
    } else {
        "QUALIFIED_EMPTY"
    };
    let qualification = match (snapshot.coverage.complete, truncated) {
        (true, false) => None,
        (false, false) => Some(
            "This result is limited to the indexed portion of this scope because coverage is incomplete.",
        ),
        (true, true) => Some(
            "This result is bounded and may omit matching evidence; consume any continuation or narrow the query.",
        ),
        (false, true) => Some(
            "This result is limited by incomplete coverage and bounded execution; complete indexing, consume any continuation, or narrow the query.",
        ),
    };
    let mut response = json!({
        "type": "KAST_REPOSITORY_QUERY_RESULT",
        "canonicalResultModel": true,
        "status": status,
        "question": params.question.as_str(),
        "intent": params.intent,
        "queryPlan": {
            "intent": params.intent.canonical(),
            "querySyntax": params.question.syntax_canonical(),
            "discovery": if params.canonical_key.is_some() {
                "EXACT_KEY"
            } else {
                params.question.discovery_canonical()
            },
            "candidateLookup": params.question.candidate_lookup(),
            "execution": "generation-pinned source-index",
            "projection": params.scope.projection,
            "metric": params.scope.metric,
            "contextSources": params.scope.sources
        },
        "workspaceIdentity": {
            "canonicalRoot": continuation_context.workspace_root
        },
        "generation": snapshot.generation,
        "inventoryGeneration": snapshot.generation,
        "graphGeneration": snapshot.generation,
        "scope": snapshot.scope,
        "coverage": snapshot.coverage,
        "appliedFilters": params.scope,
        "bounds": params.limits,
        "ordering": if params.intent == RepositoryIntent::Architecture {
            "metric descending, canonicalKey ascending"
        } else if params.intent == RepositoryIntent::ContextRelationship {
            "source priority, score descending, sourcePath ascending, targetKey ascending"
        } else if params.intent == RepositoryIntent::Resolve
            && params.canonical_key.is_none()
            && params.question.natural_language().is_some()
        {
            "matchScore descending, canonicalKey ascending"
        } else {
            "canonicalKey ascending"
        },
        "truncated": truncated,
        "continuation": result.get("continuation").cloned().unwrap_or(Value::Null),
        "qualification": qualification,
        "schemaVersion": SCHEMA_VERSION
    });
    let object = response
        .as_object_mut()
        .expect("repository response is an object");
    if let Some(result) = result.as_object() {
        for (key, value) in result {
            if !matches!(key.as_str(), "answered" | "ambiguous" | "truncated") {
                object.insert(key.clone(), value.clone());
            }
        }
    }
    Ok(response)
}

fn repository_generation(connection: &Connection) -> Result<u64> {
    let generation = connection
        .query_row("SELECT generation FROM schema_version", [], |row| {
            row.get::<_, i64>(0)
        })
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    u64::try_from(generation).map_err(|_| {
        CliError::new(
            "REPOSITORY_INDEX_UNAVAILABLE",
            "source-index generation is negative",
        )
    })
}
