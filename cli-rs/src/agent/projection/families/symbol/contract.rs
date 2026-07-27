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
        selector_handle: Option<AgentSelectorHandle>,
        #[serde(default)]
        relations: Vec<AgentSymbolRelationProjectionInput>,
    },
    IdentityAnchorUnavailable {
        source: String,
        query: String,
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
    containing_type: Option<String>,
    #[serde(default)]
    location: Option<AgentLocationInput>,
    #[serde(default)]
    file: Option<AgentIndexedFileInput>,
    #[serde(default)]
    declaration_offset: Option<u64>,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    declaration_file: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    declaration_start_offset: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    containing_type: Option<String>,
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
            (Some(fq_name), _) => {
                let declaration_file = input
                    .location
                    .as_ref()
                    .map(|location| location.file_path.clone())
                    .or_else(|| input.file.as_ref().map(|file| file.path.clone()));
                let declaration_start_offset = input
                    .location
                    .as_ref()
                    .and_then(|location| location.start_offset)
                    .or(input.declaration_offset);
                let location = input.location.or_else(|| {
                    declaration_file
                        .as_ref()
                        .map(|file_path| AgentLocationInput {
                            file_path: file_path.clone(),
                            start_offset: declaration_start_offset,
                            end_offset: None,
                            start_line: None,
                            start_column: None,
                            preview: None,
                        })
                });
                Ok(Self {
                    identity: AgentSymbolIdentityProjection {
                        fq_name,
                        kind: input.kind,
                        declaration_file,
                        declaration_start_offset,
                        containing_type: input.containing_type,
                    },
                    location,
                })
            }
            (None, Some(declaration)) => Ok(Self {
                identity: AgentSymbolIdentityProjection {
                    fq_name: declaration.fq_name,
                    kind: Some(declaration.kind),
                    declaration_file: Some(declaration.file.path.clone()),
                    declaration_start_offset: declaration.declaration_offset,
                    containing_type: input.containing_type,
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
    selector_handle: Option<AgentSelectorHandle>,
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
                selector_handle,
                relations,
            } => {
                let symbol = AgentSymbolEvidenceProjection::try_from(symbol)?;
                if symbol.identity.declaration_file.is_none()
                    || symbol.identity.declaration_start_offset.is_none()
                    || symbol.identity.kind.is_none()
                {
                    return Err("resolved symbol omitted its reusable declaration anchor".to_string());
                }
                Ok(Self {
                    mode,
                    outcome: "RESOLVED",
                    ambiguous: false,
                    source,
                    query: None,
                    identity: Some(symbol.identity),
                    selector_handle,
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
            AgentSymbolOutcomeProjectionInput::IdentityAnchorUnavailable { source, query } => {
                Ok(Self {
                    mode,
                    outcome: "IDENTITY_ANCHOR_UNAVAILABLE",
                    ambiguous: false,
                    source,
                    query: Some(query),
                    identity: None,
                    selector_handle: None,
                    location: None,
                    candidates: Vec::new(),
                    relationships: Vec::new(),
                })
            }
            AgentSymbolOutcomeProjectionInput::NotFound { source, query } => Ok(Self {
                mode,
                outcome: "NOT_FOUND",
                ambiguous: false,
                source,
                query: Some(query),
                identity: None,
                selector_handle: None,
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
                selector_handle: None,
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
                selector_handle: None,
                location: None,
                candidates: project_symbol_candidates(candidates)?,
                relationships: Vec::new(),
            }),
        }
    }
}
