#[allow(clippy::too_many_arguments)]
fn project_typed_call_relationship_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: Option<AgentExpectedRelationshipSelector>,
    relation: &'static str,
    record_relation: &'static str,
    result_limit: usize,
    max_depth: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    project_typed_relationship_envelope::<AgentTypedCallRecordInput, AgentCallDegradedReason>(
        method,
        envelope,
        expected,
        "KAST_AGENT_CALL_RELATIONSHIP_RESULT",
        relation,
        result_limit,
        view,
        |kind| kind == "FUNCTION",
        |record| {
            record.relation == record_relation
                && record.related_symbol.is_valid()
                && valid_relationship_location(&record.call_site)
                && (1..=max_depth).contains(&record.depth)
                && record.containing_symbol.is_valid()
        },
    )
}

fn project_typed_implementations_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: Option<AgentExpectedRelationshipSelector>,
    result_limit: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    project_typed_relationship_envelope::<
        AgentTypedImplementationRecordInput,
        AgentImplementationsDegradedReason,
    >(
        method,
        envelope,
        expected,
        "KAST_AGENT_IMPLEMENTATIONS_RESULT",
        "implementations",
        result_limit,
        view,
        |kind| matches!(kind, "CLASS" | "INTERFACE"),
        |record| {
            record.relation == "IMPLEMENTATION"
                && record.implementation.is_valid()
                && valid_relationship_location(&record.declaration_location)
                && record.implementation.declaration_file
                    == record.declaration_location.file_path
                && Some(record.implementation.declaration_start_offset)
                    == record.declaration_location.start_offset
        },
    )
}

#[allow(clippy::too_many_arguments)]
fn project_typed_hierarchy_envelope(
    method: String,
    envelope: AgentEnvelope,
    expected: Option<AgentExpectedRelationshipSelector>,
    direction: &str,
    result_limit: usize,
    max_depth: usize,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    project_typed_relationship_envelope::<
        AgentTypedHierarchyRecordInput,
        AgentHierarchyDegradedReason,
    >(
        method,
        envelope,
        expected,
        "KAST_AGENT_HIERARCHY_RESULT",
        "hierarchy",
        result_limit,
        view,
        |kind| matches!(kind, "CLASS" | "INTERFACE" | "OBJECT"),
        |record| {
            let relation_matches = match direction {
                "SUPERTYPES" => record.relation == "SUPERTYPE",
                "SUBTYPES" => record.relation == "SUBTYPE",
                "BOTH" => matches!(record.relation.as_str(), "SUPERTYPE" | "SUBTYPE"),
                _ => false,
            };
            relation_matches
                && record.related_symbol.is_valid()
                && valid_relationship_location(&record.declaration_location)
                && record.related_symbol.declaration_file
                    == record.declaration_location.file_path
                && Some(record.related_symbol.declaration_start_offset)
                    == record.declaration_location.start_offset
                && (1..=max_depth).contains(&record.depth)
        },
    )
}

#[allow(clippy::too_many_arguments)]
fn project_typed_relationship_envelope<Record, Reason>(
    method: String,
    envelope: AgentEnvelope,
    expected: Option<AgentExpectedRelationshipSelector>,
    result_type: &'static str,
    relation: &'static str,
    result_limit: usize,
    view: AgentResultView<AgentRelationField>,
    admitted_kind: impl Fn(&str) -> bool + Copy,
    record_is_valid: impl Fn(&Record) -> bool,
) -> AgentEnvelope
where
    Record: for<'de> Deserialize<'de> + Serialize,
    Reason: for<'de> Deserialize<'de> + Serialize,
{
    if !envelope.ok {
        return compact_relationship_error(method, envelope);
    }
    let Some(result) = envelope.result else {
        return invalid_projection_envelope(method, "Relationship endpoint returned no result.");
    };
    let input = match serde_json::from_value::<AgentTypedTraversalResponseInput<Record, Reason>>(
        result,
    ) {
        Ok(input) => input,
        Err(error) => {
            return invalid_projection_envelope(
                method,
                format!("Relationship endpoint violated its closed response contract: {error}"),
            );
        }
    };
    match input {
        AgentTypedTraversalResponseInput::Available {
            mut subject,
            records,
            page,
        } => {
            if !subject.is_valid()
                || !expected
                    .as_ref()
                    .is_none_or(|expected| expected.matches(&mut subject))
                || !admitted_kind(&subject.kind)
                || !page.is_valid(records.len(), result_limit)
                || records.iter().any(|record| !record_is_valid(record))
            {
                return invalid_projection_envelope(
                    method,
                    "Relationship endpoint contained invalid or unbounded available evidence.",
                );
            }
            project_typed_available_relationship(
                method,
                result_type,
                subject,
                relation,
                records,
                page,
                view,
            )
        }
        other => project_typed_expected_relationship_outcome(
            method,
            result_type,
            expected,
            other,
            admitted_kind,
        ),
    }
}

fn project_typed_available_relationship<Record: Serialize>(
    method: String,
    result_type: &'static str,
    subject: AgentRelationIdentityProjection,
    relation: &'static str,
    records: Vec<Record>,
    page: AgentTypedTraversalPageInput,
    view: AgentResultView<AgentRelationField>,
) -> AgentEnvelope {
    let AgentTypedTraversalPageInput {
        evidence,
        returned_count,
        visited_candidate_count,
        truncated,
        next_page_token,
    } = page;
    let cardinality = evidence.cardinality();
    let coverage = evidence.coverage().clone();
    let limitations = evidence.coverage().limitations().to_vec();
    let page = AgentTypedTraversalPageProjection {
        cardinality,
        returned_count,
        visited_candidate_count,
        truncated,
        next_page_token,
    };
    let selected = |field| match &view {
        AgentResultView::Fields(fields) => fields.contains(&field),
        AgentResultView::Count => false,
        AgentResultView::Compact | AgentResultView::Verbose | AgentResultView::Explain => true,
    };
    let mut result = serde_json::Map::from_iter([
        ("type".to_string(), Value::String(result_type.to_string())),
        ("ok".to_string(), Value::Bool(true)),
        ("outcome".to_string(), Value::String("AVAILABLE".to_string())),
        ("schemaVersion".to_string(), Value::from(SCHEMA_VERSION)),
    ]);
    if selected(AgentRelationField::Subject) {
        result.insert(
            "subject".to_string(),
            serde_json::to_value(subject).unwrap_or(Value::Null),
        );
    }
    if selected(AgentRelationField::Relation) || matches!(view, AgentResultView::Count) {
        result.insert("relation".to_string(), Value::String(relation.to_string()));
    }
    if selected(AgentRelationField::Records) {
        result.insert(
            "records".to_string(),
            serde_json::to_value(records).unwrap_or(Value::Null),
        );
    }
    if selected(AgentRelationField::Page) || matches!(view, AgentResultView::Count) {
        result.insert(
            "page".to_string(),
            serde_json::to_value(page).unwrap_or(Value::Null),
        );
    }
    result.insert(
        "coverage".to_string(),
        serde_json::to_value(coverage).unwrap_or(Value::Null),
    );
    result.insert(
        "limitations".to_string(),
        serde_json::to_value(limitations).unwrap_or(Value::Null),
    );
    projected_agent_envelope(method, true, Value::Object(result), None)
}
