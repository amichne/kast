#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolLookupProjectionInput {
    mode: AgentSymbolMode,
    outcome: AgentSymbolOutcomeProjectionInput,
}
#[derive(Debug, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentSymbolOutcomeProjectionInput {
    Resolved {
        source: String,
        symbol: Value,
        #[serde(default)]
        relations: Vec<AgentSymbolRelationProjectionInput>,
    },
    NotFound {
        source: String,
        query: String,
    },
    Ambiguous {
        source: String,
        query: String,
        candidates: Vec<Value>,
    },
    Discovered {
        source: String,
        query: String,
        candidates: Vec<Value>,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolRelationProjectionInput {
    relation: String,
    result: AgentRelationshipResultInput,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum AgentRelationshipResultInput {
    #[serde(rename = "REFERENCES_SUCCESS")]
    References {
        references: Vec<AgentLocationInput>,
        cardinality: AgentResultCardinality,
        #[serde(default)]
        page: Option<AgentRelationshipPageInput>,
    },
    #[serde(rename = "CALLERS_SUCCESS")]
    Callers {
        root: Box<AgentCallNodeInput>,
        stats: AgentCallStatsInput,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationshipPageInput {
    truncated: bool,
    #[serde(default)]
    next_page_token: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentCallNodeInput {
    symbol: AgentCallSymbolInput,
    #[serde(default)]
    call_site: Option<AgentLocationInput>,
    #[serde(default)]
    children: Vec<AgentCallNodeInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentCallSymbolInput {
    fq_name: String,
    #[serde(default)]
    location: Option<AgentLocationInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentCallStatsInput {
    total_edges: usize,
    #[serde(default)]
    truncated_nodes: usize,
    #[serde(default)]
    timeout_reached: bool,
    #[serde(default)]
    max_total_calls_reached: bool,
    #[serde(default)]
    max_children_per_node_reached: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolEvidenceInput {
    #[serde(default)]
    fq_name: Option<String>,
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    location: Option<AgentLocationInput>,
    #[serde(default)]
    declaration: Option<AgentIndexedDeclarationInput>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentIndexedDeclarationInput {
    fq_name: String,
    kind: String,
    file: AgentIndexedFileInput,
    #[serde(default)]
    declaration_offset: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct AgentIndexedFileInput {
    path: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentLocationInput {
    file_path: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    start_offset: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    end_offset: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    start_line: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    start_column: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    preview: Option<String>,
}

impl AgentLocationInput {
    fn compact_relationship(mut self) -> Self {
        self.preview = None;
        self
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolIdentityProjection {
    fq_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
}

#[derive(Debug, Clone)]
struct AgentSymbolEvidenceProjection {
    identity: AgentSymbolIdentityProjection,
    location: Option<AgentLocationInput>,
}

impl TryFrom<Value> for AgentSymbolEvidenceProjection {
    type Error = String;

    fn try_from(value: Value) -> std::result::Result<Self, Self::Error> {
        let input = serde_json::from_value::<AgentSymbolEvidenceInput>(value)
            .map_err(|error| error.to_string())?;
        match (input.fq_name, input.declaration) {
            (Some(fq_name), _) => Ok(Self {
                identity: AgentSymbolIdentityProjection {
                    fq_name,
                    kind: input.kind,
                },
                location: input.location,
            }),
            (None, Some(declaration)) => Ok(Self {
                identity: AgentSymbolIdentityProjection {
                    fq_name: declaration.fq_name,
                    kind: Some(declaration.kind),
                },
                location: Some(AgentLocationInput {
                    file_path: declaration.file.path,
                    start_offset: declaration.declaration_offset,
                    end_offset: None,
                    start_line: None,
                    start_column: None,
                    preview: None,
                }),
            }),
            (None, None) => Err("symbol evidence did not contain fqName or declaration".to_string()),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolCandidateProjection {
    identity: AgentSymbolIdentityProjection,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
}

impl From<AgentSymbolEvidenceProjection> for AgentSymbolCandidateProjection {
    fn from(value: AgentSymbolEvidenceProjection) -> Self {
        Self {
            identity: value.identity,
            location: value.location,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationshipItemProjection {
    #[serde(skip_serializing_if = "Option::is_none")]
    symbol: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    location: Option<AgentLocationInput>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRelationshipProjection {
    relation: String,
    cardinality: AgentResultCardinality,
    returned_count: usize,
    truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_page_token: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    items: Vec<AgentRelationshipItemProjection>,
}

#[derive(Debug, Clone)]
struct AgentSymbolProjection {
    mode: AgentSymbolMode,
    outcome: &'static str,
    ambiguous: bool,
    source: String,
    query: Option<String>,
    identity: Option<AgentSymbolIdentityProjection>,
    location: Option<AgentLocationInput>,
    candidates: Vec<AgentSymbolCandidateProjection>,
    relationships: Vec<AgentRelationshipProjection>,
}

impl AgentSymbolProjection {
    fn try_from_input(
        input: AgentSymbolLookupProjectionInput,
        relation_limit: usize,
    ) -> std::result::Result<Self, String> {
        let mode = input.mode;
        match input.outcome {
            AgentSymbolOutcomeProjectionInput::Resolved {
                source,
                symbol,
                relations,
            } => {
                let symbol = AgentSymbolEvidenceProjection::try_from(symbol)?;
                Ok(Self {
                    mode,
                    outcome: "RESOLVED",
                    ambiguous: false,
                    source,
                    query: None,
                    identity: Some(symbol.identity),
                    location: symbol.location,
                    candidates: Vec::new(),
                    relationships: relations
                        .into_iter()
                        .map(|relation| {
                            AgentRelationshipProjection::try_from_input(relation, relation_limit)
                        })
                        .collect::<std::result::Result<Vec<_>, _>>()?,
                })
            }
            AgentSymbolOutcomeProjectionInput::NotFound { source, query } => Ok(Self {
                mode,
                outcome: "NOT_FOUND",
                ambiguous: false,
                source,
                query: Some(query),
                identity: None,
                location: None,
                candidates: Vec::new(),
                relationships: Vec::new(),
            }),
            AgentSymbolOutcomeProjectionInput::Ambiguous {
                source,
                query,
                candidates,
            } => Ok(Self {
                mode,
                outcome: "AMBIGUOUS",
                ambiguous: true,
                source,
                query: Some(query),
                identity: None,
                location: None,
                candidates: project_symbol_candidates(candidates)?,
                relationships: Vec::new(),
            }),
            AgentSymbolOutcomeProjectionInput::Discovered {
                source,
                query,
                candidates,
            } => Ok(Self {
                mode,
                outcome: "DISCOVERED",
                ambiguous: false,
                source,
                query: Some(query),
                identity: None,
                location: None,
                candidates: project_symbol_candidates(candidates)?,
                relationships: Vec::new(),
            }),
        }
    }
}

fn project_symbol_candidates(
    candidates: Vec<Value>,
) -> std::result::Result<Vec<AgentSymbolCandidateProjection>, String> {
    candidates
        .into_iter()
        .map(AgentSymbolEvidenceProjection::try_from)
        .map(|result| result.map(AgentSymbolCandidateProjection::from))
        .collect()
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
