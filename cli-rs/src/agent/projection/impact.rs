#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactMetricsProjectionInput {
    #[serde(rename = "type")]
    result_type: String,
    ok: bool,
    query: AgentImpactMetricsQueryInput,
    results: Vec<AgentImpactNodeProjection>,
    total_count: usize,
    returned_count: usize,
    truncated: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactMetricsQueryInput {
    workspace_root: String,
    metric: String,
    symbol: Option<String>,
    depth: usize,
    limit: usize,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactNodeProjection {
    source_path: String,
    depth: usize,
    via_target_fq_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    edge_kind: Option<String>,
    occurrence_count: i64,
    confidence: AgentImpactNodeConfidenceProjection,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactNodeConfidenceProjection {
    level: String,
    index_completeness: f64,
    semantic_basis: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactQueryProjection {
    workspace_root: String,
    symbol: String,
    depth: usize,
    limit: usize,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactCardinalityProjection {
    total_count: usize,
    returned_count: usize,
    truncated: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactConfidenceProjection {
    levels: BTreeMap<String, usize>,
    semantic_bases: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    minimum_index_completeness: Option<f64>,
}

#[derive(Debug)]
struct AgentImpactProjection {
    query: AgentImpactQueryProjection,
    cardinality: AgentImpactCardinalityProjection,
    nodes: Vec<AgentImpactNodeProjection>,
    confidence: AgentImpactConfidenceProjection,
}

impl TryFrom<AgentImpactMetricsProjectionInput> for AgentImpactProjection {
    type Error = String;

    fn try_from(
        input: AgentImpactMetricsProjectionInput,
    ) -> std::result::Result<Self, Self::Error> {
        if input.result_type != "METRICS_SUCCESS" || !input.ok {
            return Err("impact metrics result was not METRICS_SUCCESS".to_string());
        }
        if input.query.metric != "impact" {
            return Err(format!(
                "impact projection received metric {}",
                input.query.metric
            ));
        }
        let symbol = input
            .query
            .symbol
            .filter(|symbol| !symbol.trim().is_empty())
            .ok_or_else(|| "impact metrics query omitted its symbol".to_string())?;
        if input.returned_count != input.results.len() {
            return Err("impact returnedCount disagreed with its result nodes".to_string());
        }
        if input.total_count < input.returned_count {
            return Err("impact totalCount was smaller than returnedCount".to_string());
        }
        if input.truncated != (input.total_count > input.returned_count) {
            return Err("impact truncation disagreed with its cardinality".to_string());
        }
        let mut levels = BTreeMap::new();
        let mut semantic_bases = BTreeMap::new();
        let mut minimum_index_completeness: Option<f64> = None;
        for node in &input.results {
            *levels.entry(node.confidence.level.clone()).or_insert(0) += 1;
            semantic_bases.insert(node.confidence.semantic_basis.clone(), ());
            minimum_index_completeness = Some(
                minimum_index_completeness.map_or(node.confidence.index_completeness, |current| {
                    current.min(node.confidence.index_completeness)
                }),
            );
        }
        Ok(Self {
            query: AgentImpactQueryProjection {
                workspace_root: input.query.workspace_root,
                symbol,
                depth: input.query.depth,
                limit: input.query.limit,
            },
            cardinality: AgentImpactCardinalityProjection {
                total_count: input.total_count,
                returned_count: input.returned_count,
                truncated: input.truncated,
            },
            nodes: input.results,
            confidence: AgentImpactConfidenceProjection {
                levels,
                semantic_bases: semantic_bases.into_keys().collect(),
                minimum_index_completeness,
            },
        })
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    query: AgentImpactQueryProjection,
    total_count: usize,
    returned_count: usize,
    truncated: bool,
    nodes: Vec<AgentImpactNodeProjection>,
    confidence: AgentImpactConfidenceProjection,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactSelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    query: Option<AgentImpactQueryProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    summary: Option<AgentImpactCardinalityProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    nodes: Option<Vec<AgentImpactNodeProjection>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    confidence: Option<AgentImpactConfidenceProjection>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentImpactCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    total_count: usize,
    returned_count: usize,
    truncated: bool,
    schema_version: u32,
}

fn project_impact_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentImpactField>,
) -> AgentEnvelope {
    if view.detailed() {
        return envelope;
    }
    let Some(result) = envelope.result.clone() else {
        return compact_error_envelope(envelope);
    };
    let command = match AgentStepCommandProjectionInput::validated(result) {
        Ok(command) => command,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("impact result violated the projection contract: {error}"),
            );
        }
    };
    if !envelope.ok {
        return compact_command_error_envelope(envelope, &command);
    }
    let Some(impact_step) = command.step("database/metrics") else {
        return invalid_projection_envelope(envelope.method, "impact result omitted metrics");
    };
    if !impact_step.ok {
        return compact_command_error_envelope(envelope, &command);
    }
    let Some(result) = impact_step.result.clone() else {
        return invalid_projection_envelope(envelope.method, "impact metrics omitted its result");
    };
    let input = match serde_json::from_value::<AgentImpactMetricsProjectionInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("impact metrics violated the projection contract: {error}"),
            );
        }
    };
    let projection = match AgentImpactProjection::try_from(input) {
        Ok(projection) => projection,
        Err(error) => return invalid_projection_envelope(envelope.method, error),
    };
    let method = envelope.method;
    match view {
        AgentResultView::Compact => result_envelope(
            method,
            AgentImpactCompactResult {
                result_type: "KAST_AGENT_IMPACT_RESULT",
                ok: true,
                query: projection.query,
                total_count: projection.cardinality.total_count,
                returned_count: projection.cardinality.returned_count,
                truncated: projection.cardinality.truncated,
                nodes: projection.nodes,
                confidence: projection.confidence,
                schema_version: SCHEMA_VERSION,
            },
        ),
        AgentResultView::Fields(fields) => {
            let selected = |field| fields.contains(&field);
            result_envelope(
                method,
                AgentImpactSelectedResult {
                    result_type: "KAST_AGENT_IMPACT_SELECTION",
                    ok: true,
                    query: selected(AgentImpactField::Query).then_some(projection.query),
                    summary: selected(AgentImpactField::Summary)
                        .then_some(projection.cardinality),
                    nodes: selected(AgentImpactField::Nodes).then_some(projection.nodes),
                    confidence: selected(AgentImpactField::Confidence)
                        .then_some(projection.confidence),
                    schema_version: SCHEMA_VERSION,
                },
            )
        }
        AgentResultView::Count => result_envelope(
            method,
            AgentImpactCountResult {
                result_type: "KAST_AGENT_IMPACT_COUNT",
                ok: true,
                total_count: projection.cardinality.total_count,
                returned_count: projection.cardinality.returned_count,
                truncated: projection.cardinality.truncated,
                schema_version: SCHEMA_VERSION,
            },
        ),
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed impact views returned before projection")
        }
    }
}
