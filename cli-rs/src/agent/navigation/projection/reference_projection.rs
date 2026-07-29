fn project_references_envelope(
    envelope: AgentEnvelope,
    view: AgentResultView<AgentRelationField>,
    result_limit: usize,
) -> AgentEnvelope {
    if !envelope.ok {
        return compact_error_envelope(envelope);
    }
    let method = envelope.method.clone();
    let provenance = match reference_request_provenance(&envelope) {
        Ok(provenance) => provenance,
        Err(message) => return invalid_projection_envelope(method, message),
    };
    let Some(result) = envelope.result.clone() else {
        return invalid_projection_envelope(method, "References returned no result.");
    };
    let input = match serde_json::from_value::<AgentReferencesResponseInput>(result) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("References violated the closed response contract: {error}"),
            );
        }
    };
    let include_degraded_records = relationship_view_includes_records(&view);
    match input {
        AgentReferencesResponseInput::Available {
            subject,
            references,
            evidence,
            page,
        } => project_available_references(
            method,
            view,
            result_limit,
            subject,
            references,
            evidence,
            page,
            &provenance,
        ),
        other => project_expected_reference_outcome(
            method,
            other,
            &provenance,
            result_limit,
            include_degraded_records,
        ),
    }
}

#[derive(Debug, Clone)]
enum AgentReferenceRequestProvenance {
    Explicit(AgentExpectedRelationshipSelector),
    Handle,
}

impl AgentReferenceRequestProvenance {
    fn matches_subject(&self, subject: &mut AgentRelationIdentityProjection) -> bool {
        subject.is_valid()
            && match self {
                Self::Explicit(expected) => expected.matches(subject),
                Self::Handle => true,
            }
    }

    fn matches_selector(&self, selector: &AgentRelationSelectorProjection) -> bool {
        selector.is_valid()
            && match self {
                Self::Explicit(expected) => expected.matches_selector(selector),
                Self::Handle => true,
            }
    }

    fn matches_selector_and_subject(
        &self,
        selector: &AgentRelationSelectorProjection,
        subject: &mut AgentRelationIdentityProjection,
    ) -> bool {
        self.matches_selector(selector)
            && self.matches_subject(subject)
            && selector.matches_identity(subject)
    }

    fn is_handle(&self) -> bool {
        matches!(self, Self::Handle)
    }
}

fn reference_request_provenance(
    envelope: &AgentEnvelope,
) -> std::result::Result<AgentReferenceRequestProvenance, String> {
    let params = envelope
        .request
        .as_ref()
        .and_then(|request| request.get("params"))
        .and_then(Value::as_object)
        .ok_or_else(|| "References omitted normalized request provenance.".to_string())?;
    let selector = params.get("selector").filter(|value| !value.is_null());
    let selector_handle = params
        .get("selectorHandle")
        .filter(|value| !value.is_null());
    match (selector, selector_handle) {
        (Some(selector), None) => {
            let selector = serde_json::from_value::<AgentRelationSelectorProjection>(
                selector.clone(),
            )
            .map_err(|error| format!("References explicit selector provenance was invalid: {error}"))?;
            if !selector.is_valid() {
                return Err("References explicit selector provenance was invalid.".to_string());
            }
            Ok(AgentReferenceRequestProvenance::Explicit(
                AgentExpectedRelationshipSelector {
                    workspace_root: String::new(),
                    fq_name: selector.fq_name,
                    declaration_file: selector.declaration_file,
                    declaration_start_offset: selector.declaration_start_offset,
                    kind: selector.kind,
                    containing_type: selector.containing_type,
                },
            ))
        }
        (None, Some(Value::String(handle))) if !handle.trim().is_empty() => {
            Ok(AgentReferenceRequestProvenance::Handle)
        }
        _ => Err(
            "References request provenance did not contain exactly one explicit selector or selector handle."
                .to_string(),
        ),
    }
}

#[allow(clippy::too_many_arguments)]
fn project_available_references(
    method: String,
    view: AgentResultView<AgentRelationField>,
    result_limit: usize,
    mut subject: AgentRelationIdentityProjection,
    references: Vec<AgentReferenceOccurrenceInput>,
    evidence: AgentRelationshipResultEvidenceInput,
    page: Option<AgentReferencePageInput>,
    provenance: &AgentReferenceRequestProvenance,
) -> AgentEnvelope {
    if !evidence.is_valid_available()
        || !provenance.matches_subject(&mut subject)
        || references.len() > result_limit
        || references
            .iter()
            .any(|reference| !valid_reference_occurrence(reference))
    {
        return invalid_projection_envelope(
            method,
            "References contained invalid or unbounded evidence.",
        );
    }
    let returned_count = references.len();
    let truncated = page.as_ref().is_some_and(|page| page.truncated);
    let next_page_token = page.and_then(|page| page.next_page_token);
    let cardinality = evidence.cardinality();
    if evidence.is_valid_resumable() != truncated
        || cardinality.known_minimum() < returned_count
        || truncated != next_page_token.is_some()
    {
        return invalid_projection_envelope(
            method,
            "References contained inconsistent page evidence.",
        );
    }
    let records = project_reference_records(references);
    let selected = |field| match &view {
        AgentResultView::Fields(fields) => fields.contains(&field),
        AgentResultView::Count => false,
        AgentResultView::Compact | AgentResultView::Verbose | AgentResultView::Explain => true,
    };
    let page = AgentRelationPageProjection {
        cardinality,
        returned_count,
        truncated,
        next_page_token,
    };
    let coverage = evidence.coverage().clone();
    let limitations = evidence.coverage().limitations().to_vec();
    projected_agent_envelope(
        method,
        true,
        AgentReferencesAvailableProjection {
            result_type: "KAST_AGENT_REFERENCES_RESULT",
            ok: true,
            outcome: "AVAILABLE",
            subject: selected(AgentRelationField::Subject).then_some(subject),
            relation: (selected(AgentRelationField::Relation)
                || matches!(view, AgentResultView::Count))
            .then_some("references"),
            records: selected(AgentRelationField::Records).then_some(records),
            page: (selected(AgentRelationField::Page) || matches!(view, AgentResultView::Count))
                .then_some(page),
            coverage: Some(coverage),
            limitations: Some(limitations),
            schema_version: SCHEMA_VERSION,
        },
        None,
    )
}

fn project_reference_records(
    references: Vec<AgentReferenceOccurrenceInput>,
) -> Vec<AgentReferenceRecordProjection> {
    references
        .into_iter()
        .map(|reference| AgentReferenceRecordProjection {
            relation: "REFERENCE",
            location: reference.location.compact_relationship(),
            containing_symbol: reference.containing_symbol,
        })
        .collect()
}

fn relationship_view_includes_records(view: &AgentResultView<AgentRelationField>) -> bool {
    match view {
        AgentResultView::Fields(fields) => fields.contains(&AgentRelationField::Records),
        AgentResultView::Count => false,
        AgentResultView::Compact | AgentResultView::Verbose | AgentResultView::Explain => true,
    }
}
