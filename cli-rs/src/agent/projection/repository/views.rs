fn project_repository_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentRepositoryField>,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_error_envelope(envelope);
    }
    let method = envelope.method;
    let Some(result) = envelope.result else {
        return invalid_projection_envelope(method, "repository query returned no result");
    };
    if view.detailed() {
        let validation = serde_json::from_value::<AgentRepositoryProjectionInput>(result.clone())
            .map_err(|error| error.to_string())
            .and_then(AgentRepositoryProjectionInput::validated);
        if let Err(error) = validation {
            return invalid_projection_envelope(
                method,
                format!("repository result violated the closed projection contract: {error}"),
            );
        }
        return result_envelope(method, result);
    }
    let input = match serde_json::from_value::<AgentRepositoryProjectionInput>(result)
        .map_err(|error| error.to_string())
        .and_then(AgentRepositoryProjectionInput::validated)
    {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("repository result violated the closed projection contract: {error}"),
            );
        }
    };
    let projection = input.into_projection();
    match view {
        AgentResultView::Compact => project_compact_repository(method, projection),
        AgentResultView::Fields(fields) => project_selected_repository(method, projection, &fields),
        AgentResultView::Count => project_repository_count(method, projection),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed repository views returned before projection")
        }
    }
}

fn project_compact_repository(
    method: String,
    projection: AgentRepositoryProjection,
) -> AgentEnvelope {
    let findings = projection
        .findings
        .into_iter()
        .map(AgentRepositoryCompactFinding::from)
        .collect::<Vec<_>>();
    let finding_evidence = (!findings.is_empty()).then_some(
        AgentRepositoryCompactFindingEvidence::OmittedInCompactView {
            help: "Rerun this command with --fields findings or --explain for full finding evidence.",
        },
    );
    result_envelope(
        method,
        AgentRepositoryCompactResult {
            result_type: "KAST_AGENT_REPOSITORY_RESULT",
            ok: true,
            question: projection.question,
            status: projection.status,
            intent: projection.intent,
            query_syntax: projection.query_syntax,
            workspace_root: projection.workspace_root,
            generation: projection.generation,
            coverage: projection.coverage,
            bounds: projection.bounds,
            cardinality: projection.cardinality,
            selected_identity: projection.selected_identity,
            identities: projection.identities,
            relationships: projection.relationships,
            paths: projection.paths,
            findings,
            finding_evidence,
            context: projection.context,
            truncated: projection.truncated,
            continuation: projection.continuation,
            continuations: projection.continuations,
            qualification: projection.qualification,
            schema_version: SCHEMA_VERSION,
        },
    )
}

fn project_selected_repository(
    method: String,
    projection: AgentRepositoryProjection,
    fields: &[AgentRepositoryField],
) -> AgentEnvelope {
    let selected = |field| fields.contains(&field);
    let summary = selected(AgentRepositoryField::Summary).then_some(AgentRepositorySummary {
        question: projection.question,
        status: projection.status,
        intent: projection.intent,
        query_syntax: projection.query_syntax,
        workspace_root: projection.workspace_root,
        generation: projection.generation,
        bounds: projection.bounds,
        cardinality: projection.cardinality,
        selected_identity: projection.selected_identity.clone(),
        truncated: projection.truncated,
        qualification: projection.qualification.clone(),
    });
    result_envelope(
        method,
        AgentRepositorySelectedResult {
            result_type: "KAST_AGENT_REPOSITORY_SELECTION",
            ok: true,
            status: projection.status,
            intent: projection.intent,
            truncated: projection.truncated,
            qualification: projection.qualification,
            selected_identity: projection.selected_identity,
            summary,
            coverage: selected(AgentRepositoryField::Coverage).then_some(projection.coverage),
            identities: selected(AgentRepositoryField::Identities).then_some(projection.identities),
            relationships: selected(AgentRepositoryField::Relationships)
                .then_some(projection.relationships),
            paths: selected(AgentRepositoryField::Paths).then_some(projection.paths),
            findings: selected(AgentRepositoryField::Findings).then_some(projection.findings),
            context: selected(AgentRepositoryField::Context).then_some(projection.context),
            continuation: selected(AgentRepositoryField::Continuation)
                .then_some(projection.continuation)
                .flatten(),
            continuations: selected(AgentRepositoryField::Continuation)
                .then_some(projection.continuations),
            schema_version: SCHEMA_VERSION,
        },
    )
}

fn project_repository_count(
    method: String,
    projection: AgentRepositoryProjection,
) -> AgentEnvelope {
    result_envelope(
        method,
        AgentRepositoryCountResult {
            result_type: "KAST_AGENT_REPOSITORY_COUNT",
            ok: true,
            status: projection.status,
            intent: projection.intent,
            query_syntax: projection.query_syntax,
            generation: projection.generation,
            coverage: projection.coverage,
            bounds: projection.bounds,
            cardinality: projection.cardinality,
            selected_identity: projection.selected_identity,
            truncated: projection.truncated,
            schema_version: SCHEMA_VERSION,
        },
    )
}
