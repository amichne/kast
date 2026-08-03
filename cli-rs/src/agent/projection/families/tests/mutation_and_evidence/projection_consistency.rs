use super::*;

#[test]
fn answered_and_ambiguous_projections_require_intent_evidence() {
    for intent in [
        "resolve",
        "path",
        "incoming_impact",
        "outgoing_impact",
        "architecture",
        "context_relationship",
    ] {
        let projected = project_repository_envelope(
            result_envelope(
                "repository/query".to_string(),
                repository_result(intent, "ANSWERED"),
            ),
            AgentResultView::Compact,
        );

        assert!(
            !projected.ok,
            "ANSWERED {intent} accepted no answer evidence"
        );
    }

    let mut unselected = repository_result("resolve", "ANSWERED");
    unselected["candidates"] = json!([repository_candidate("callable:sample.answer", "answer")]);
    let projected = project_repository_envelope(
        result_envelope("repository/query".to_string(), unselected),
        AgentResultView::Compact,
    );
    assert!(
        !projected.ok,
        "ANSWERED resolve accepted candidates without a selected identity"
    );

    for intent in [
        "resolve",
        "path",
        "incoming_impact",
        "outgoing_impact",
        "architecture",
    ] {
        let projected = project_repository_envelope(
            result_envelope(
                "repository/query".to_string(),
                repository_result(intent, "AMBIGUOUS"),
            ),
            AgentResultView::Compact,
        );

        assert!(
            !projected.ok,
            "AMBIGUOUS {intent} accepted no disambiguation evidence"
        );
    }
}

#[test]
fn definitive_empty_projection_rejects_answer_evidence() {
    let node = repository_node("callable:sample.answer", "answer");
    let cases = [
        ("resolve", "nodes", json!([node.clone()])),
        (
            "resolve",
            "candidates",
            json!([repository_candidate("callable:sample.answer", "answer")]),
        ),
        ("path", "edges", json!([repository_relationship()])),
        (
            "path",
            "paths",
            json!([{
                "direction": "OUTGOING",
                "relationKinds": ["CALLS"],
                "nodes": [node.clone()]
            }]),
        ),
        (
            "incoming_impact",
            "edges",
            json!([repository_relationship()]),
        ),
        (
            "outgoing_impact",
            "edges",
            json!([repository_relationship()]),
        ),
        (
            "architecture",
            "findings",
            json!([repository_finding(node.clone())]),
        ),
        (
            "context_relationship",
            "contextRelations",
            json!([repository_context_relation()]),
        ),
    ];

    for status in ["EMPTY", "QUALIFIED_EMPTY"] {
        for (intent, field, evidence) in &cases {
            let mut result = repository_result(intent, status);
            result[*field] = evidence.clone();
            let projected = project_repository_envelope(
                result_envelope("repository/query".to_string(), result),
                AgentResultView::Compact,
            );

            assert!(
                !projected.ok,
                "{status} {intent} accepted affirmative {field} evidence"
            );
        }
    }
}

#[test]
fn repository_projection_rejects_status_qualification_contradictions() {
    let mut empty_without_negative_eligibility = repository_result("resolve", "EMPTY");
    empty_without_negative_eligibility["coverage"]["eligibleForCompleteNegative"] = json!(false);
    let mut empty_without_eligibility_proof = repository_result("resolve", "EMPTY");
    empty_without_eligibility_proof["coverage"]["eligibilityProven"] = json!(false);
    let mut truncated_empty = repository_result("resolve", "EMPTY");
    truncated_empty["truncated"] = json!(true);
    let mut qualified_empty_with_negative_eligibility =
        repository_result("resolve", "QUALIFIED_EMPTY");
    qualified_empty_with_negative_eligibility["coverage"]["eligibleForCompleteNegative"] =
        json!(true);
    let mut empty_with_qualification = repository_result("resolve", "EMPTY");
    empty_with_qualification["qualification"] = json!("not actually definitive");
    let mut qualified_empty_without_explanation = repository_result("resolve", "QUALIFIED_EMPTY");
    qualified_empty_without_explanation["qualification"] = json!("");

    for (case, result) in [
        (
            "EMPTY without complete-negative eligibility",
            empty_without_negative_eligibility,
        ),
        (
            "EMPTY without eligibility proof",
            empty_without_eligibility_proof,
        ),
        ("truncated EMPTY", truncated_empty),
        (
            "QUALIFIED_EMPTY with complete-negative eligibility",
            qualified_empty_with_negative_eligibility,
        ),
        ("EMPTY with a qualification", empty_with_qualification),
        (
            "QUALIFIED_EMPTY without an explanation",
            qualified_empty_without_explanation,
        ),
    ] {
        for view in [
            AgentResultView::Compact,
            AgentResultView::Fields(vec![AgentRepositoryField::Summary]),
            AgentResultView::Count,
            AgentResultView::Verbose,
            AgentResultView::Explain,
        ] {
            let projected = project_repository_envelope(
                result_envelope("repository/query".to_string(), result.clone()),
                view.clone(),
            );

            assert!(!projected.ok, "{case} accepted by {view:?}");
        }
    }

    for status in ["EMPTY", "QUALIFIED_EMPTY"] {
        let projected = project_repository_envelope(
            result_envelope(
                "repository/query".to_string(),
                repository_result("resolve", status),
            ),
            AgentResultView::Compact,
        );

        assert!(projected.ok, "valid {status} projection was rejected");
    }
}
