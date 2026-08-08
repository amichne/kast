#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSelectableSymbolProjectionInput {
    symbol: Value,
    selector_handle: AgentSelectorHandle,
}

fn project_symbol_candidates(
    candidates: Vec<Value>,
) -> std::result::Result<Vec<AgentSymbolCandidateProjection>, String> {
    candidates
        .into_iter()
        .map(AgentSymbolEvidenceProjection::try_from)
        .map(|result| {
            result.map(|candidate| AgentSymbolCandidateProjection {
                identity: candidate.identity,
                selector_handle: None,
                location: candidate.location,
            })
        })
        .collect()
}

fn project_selectable_symbol_candidates(
    candidates: Vec<AgentSelectableSymbolProjectionInput>,
) -> std::result::Result<Vec<AgentSymbolCandidateProjection>, String> {
    candidates
        .into_iter()
        .map(|candidate| {
            AgentSymbolEvidenceProjection::try_from(candidate.symbol).map(|symbol| {
                AgentSymbolCandidateProjection {
                    identity: symbol.identity,
                    selector_handle: Some(candidate.selector_handle),
                    location: symbol.location,
                }
            })
        })
        .collect()
}

fn project_ambiguous_symbol_candidates(
    source: &str,
    candidates: Vec<Value>,
) -> std::result::Result<Vec<AgentSymbolCandidateProjection>, String> {
    match source {
        "compiler" => candidates
            .into_iter()
            .map(|candidate| {
                serde_json::from_value(candidate).map_err(|error| {
                    format!("compiler ambiguity candidate was not selectable: {error}")
                })
            })
            .collect::<std::result::Result<Vec<_>, _>>()
            .and_then(project_selectable_symbol_candidates),
        "indexed-exact" => project_symbol_candidates(candidates),
        _ => Err("ambiguity source did not define a candidate contract".to_string()),
    }
}

impl AgentRelationshipProjection {
    fn try_from_input(
        value: AgentSymbolRelationProjectionInput,
        limit: usize,
    ) -> std::result::Result<Self, String> {
        match value.result {
            AgentRelationshipResultInput::References {
                references,
                cardinality,
                page,
            } => {
                if cardinality.known_minimum() < references.len() {
                    return Err("references cardinality was smaller than its result page".to_string());
                }
                let available_count = references.len();
                let items = references
                    .into_iter()
                    .take(limit)
                    .map(|location| AgentRelationshipItemProjection {
                        symbol: None,
                        location: Some(location.compact_relationship()),
                    })
                    .collect::<Vec<_>>();
                let returned_count = items.len();
                let page_truncated = page.as_ref().is_some_and(|page| page.truncated);
                Ok(Self {
                    relation: value.relation,
                    cardinality,
                    returned_count,
                    truncated: page_truncated
                        || available_count > returned_count
                        || cardinality.known_minimum() > returned_count,
                    next_page_token: page.and_then(|page| page.next_page_token),
                    items,
                })
            }
            AgentRelationshipResultInput::Callers { root, stats } => {
                let mut items = Vec::new();
                let root = *root;
                collect_call_relationships(root.children, limit, &mut items);
                let returned_count = items.len();
                if stats.total_edges < returned_count {
                    return Err(
                        "callers totalEdges was smaller than its projected relationships"
                            .to_string(),
                    );
                }
                let enumeration_incomplete = stats.truncated_nodes > 0
                    || stats.timeout_reached
                    || stats.max_total_calls_reached
                    || stats.max_children_per_node_reached;
                let cardinality = if enumeration_incomplete {
                    AgentResultCardinality::KnownMinimum {
                        known_minimum_count: stats.total_edges,
                    }
                } else {
                    AgentResultCardinality::Exact {
                        total_count: stats.total_edges,
                    }
                };
                Ok(Self {
                    relation: value.relation,
                    cardinality,
                    returned_count,
                    truncated: enumeration_incomplete || stats.total_edges > returned_count,
                    next_page_token: None,
                    items,
                })
            }
        }
    }
}

fn collect_call_relationships(
    nodes: Vec<AgentCallNodeInput>,
    limit: usize,
    items: &mut Vec<AgentRelationshipItemProjection>,
) {
    for node in nodes {
        if items.len() == limit {
            return;
        }
        let AgentCallNodeInput {
            symbol,
            call_site,
            children,
        } = node;
        items.push(AgentRelationshipItemProjection {
            symbol: Some(symbol.fq_name),
            location: call_site
                .or(symbol.location)
                .map(AgentLocationInput::compact_relationship),
        });
        collect_call_relationships(children, limit, items);
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolCompactResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    mode: AgentSymbolMode,
    confidence_mode: &'static str,
    outcome: &'static str,
    ambiguous: bool,
    source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    query: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    identity: Option<AgentSymbolIdentityProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    selector_handle: Option<AgentSelectorHandle>,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    candidates: Vec<AgentSymbolCandidateProjection>,
    relationships: Vec<AgentRelationshipProjection>,
    schema_version: u32,
}

impl From<AgentSymbolProjection> for AgentSymbolCompactResult {
    fn from(value: AgentSymbolProjection) -> Self {
        Self {
            result_type: "KAST_AGENT_SYMBOL_RESULT",
            ok: true,
            mode: value.mode,
            confidence_mode: symbol_confidence_mode(value.mode),
            outcome: value.outcome,
            ambiguous: value.ambiguous,
            source: value.source,
            query: value.query,
            identity: value.identity,
            selector_handle: value.selector_handle,
            location: value.location,
            candidates: value.candidates,
            relationships: value.relationships,
            schema_version: SCHEMA_VERSION,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolSelectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    mode: Option<AgentSymbolMode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    confidence_mode: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    outcome: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ambiguous: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    identity: Option<AgentSymbolIdentityProjection>,
    #[serde(skip_serializing_if = "Option::is_none")]
    selector_handle: Option<AgentSelectorHandle>,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    relationships: Option<Vec<AgentRelationshipProjection>>,
    schema_version: u32,
}

impl AgentSymbolSelectedResult {
    fn from_projection(value: AgentSymbolProjection, fields: &[AgentSymbolField]) -> Self {
        let selected = |field| fields.contains(&field);
        Self {
            result_type: "KAST_AGENT_SYMBOL_SELECTION",
            ok: true,
            mode: selected(AgentSymbolField::Mode).then_some(value.mode),
            confidence_mode: selected(AgentSymbolField::Mode)
                .then_some(symbol_confidence_mode(value.mode)),
            outcome: selected(AgentSymbolField::Outcome).then_some(value.outcome),
            ambiguous: selected(AgentSymbolField::Ambiguity).then_some(value.ambiguous),
            source: selected(AgentSymbolField::Source).then_some(value.source),
            identity: selected(AgentSymbolField::Identity)
                .then_some(value.identity)
                .flatten(),
            selector_handle: selected(AgentSymbolField::SelectorHandle)
                .then_some(value.selector_handle)
                .flatten(),
            location: selected(AgentSymbolField::Location)
                .then_some(value.location)
                .flatten(),
            relationships: selected(AgentSymbolField::Relationships)
                .then_some(value.relationships),
            schema_version: SCHEMA_VERSION,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolCountResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    result_count: usize,
    candidate_count: usize,
    relationship_cardinality: AgentAggregateCardinalityProjection,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentAggregateCardinalityProjection {
    known_minimum_count: usize,
    exact: bool,
}

impl AgentSymbolCountResult {
    fn try_from_projection(value: AgentSymbolProjection) -> std::result::Result<Self, String> {
        let known_minimum_count = value.relationships.iter().try_fold(
            0usize,
            |count, relationship| {
                count
                    .checked_add(relationship.cardinality.known_minimum())
                    .ok_or_else(|| "aggregate relationship cardinality overflowed usize".to_string())
            },
        )?;
        Ok(Self {
            result_type: "KAST_AGENT_SYMBOL_COUNT",
            ok: true,
            result_count: usize::from(value.identity.is_some()),
            candidate_count: value.candidates.len(),
            relationship_cardinality: AgentAggregateCardinalityProjection {
                known_minimum_count,
                exact: value
                    .relationships
                    .iter()
                    .all(|relationship| relationship.cardinality.is_exact()),
            },
            schema_version: SCHEMA_VERSION,
        })
    }
}

fn symbol_confidence_mode(mode: AgentSymbolMode) -> &'static str {
    match mode {
        AgentSymbolMode::Exact => "exact",
        AgentSymbolMode::Discovery => "ranked",
    }
}

fn project_symbol_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentSymbolField>,
    relation_limit: usize,
) -> AgentEnvelope {
    if view.detailed() {
        return envelope;
    }
    if !envelope.ok {
        return compact_error_envelope(envelope);
    }
    let Some(result) = envelope.result.clone() else {
        return invalid_projection_envelope(
            envelope.method,
            "symbol result projection requires a result",
        );
    };
    let input = match serde_json::from_value::<AgentSymbolLookupProjectionInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("symbol result violated the projection contract: {error}"),
            );
        }
    };
    let projection = match AgentSymbolProjection::try_from_input(input, relation_limit) {
        Ok(projection) => projection,
        Err(error) => {
            return invalid_projection_envelope(
                envelope.method,
                format!("symbol result violated the projection contract: {error}"),
            );
        }
    };
    let method = envelope.method;
    match view {
        AgentResultView::Compact => result_envelope(method, AgentSymbolCompactResult::from(projection)),
        AgentResultView::Fields(fields) => result_envelope(
            method,
            AgentSymbolSelectedResult::from_projection(projection, &fields),
        ),
        AgentResultView::Count => match AgentSymbolCountResult::try_from_projection(projection) {
            Ok(result) => result_envelope(method, result),
            Err(error) => invalid_projection_envelope(
                method,
                format!("symbol result violated the projection contract: {error}"),
            ),
        },
        AgentResultView::Verbose | AgentResultView::Explain => {
            unreachable!("detailed symbol views returned before projection")
        }
    }
}
