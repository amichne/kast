    #[test]
    fn repository_projection_accepts_qualified_incomplete_answer() {
        let mut result = repository_result("resolve", "ANSWERED");
        result["coverage"]["complete"] = json!(false);
        result["coverage"]["eligibleForCompleteNegative"] = json!(false);
        result["coverage"]["total"] = json!(2);
        result["coverage"]["indexed"] = json!(1);
        result["coverage"]["excluded"] = json!(1);
        result["coverage"]["accounted"] = json!(2);
        result["qualification"] = json!("scope coverage is incomplete");
        result["selectedIdentity"] = json!("callable:sample.answer");
        result["nodes"] = json!([repository_node("callable:sample.answer", "answer")]);

        let projected = project_repository_envelope(
            result_envelope("repository/query".to_string(), result.clone()),
            AgentResultView::Compact,
        );

        let projected = projected
            .result
            .expect("qualified incomplete answer projection");
        assert_eq!(projected["status"], "ANSWERED");
        assert_eq!(projected["coverage"]["complete"], false);
        assert_eq!(
            projected["qualification"],
            "scope coverage is incomplete"
        );

        let count = project_repository_envelope(
            result_envelope("repository/query".to_string(), result),
            AgentResultView::Count,
        )
        .result
        .expect("qualified incomplete count projection");
        assert_eq!(count["status"], "ANSWERED");
        assert_eq!(count["qualification"], "scope coverage is incomplete");
    }

    #[test]
    fn repository_projection_rejects_complete_coverage_authority_contradictions() {
        let mut cases = Vec::new();
        for field in ["pending", "limited", "failed", "stale"] {
            let mut result = repository_result("resolve", "EMPTY");
            result["coverage"]["indexed"] = json!(0);
            result["coverage"][field] = json!(1);
            if field == "pending" {
                result["coverage"]["pendingUpdateCount"] = json!(1);
            }
            cases.push((format!("complete coverage with {field} work"), result));
        }

        let mut unproven = repository_result("resolve", "EMPTY");
        unproven["coverage"]["eligibilityProven"] = json!(false);
        cases.push(("complete coverage without eligibility proof".to_string(), unproven));

        let mut eligible_partial = repository_result("resolve", "ANSWERED");
        eligible_partial["coverage"]["complete"] = json!(false);
        eligible_partial["coverage"]["excluded"] = json!(1);
        eligible_partial["coverage"]["indexed"] = json!(0);
        eligible_partial["qualification"] = json!("scope coverage is incomplete");
        eligible_partial["selectedIdentity"] = json!("callable:sample.answer");
        eligible_partial["nodes"] = json!([repository_node("callable:sample.answer", "answer")]);
        cases.push((
            "complete-negative eligibility without complete coverage".to_string(),
            eligible_partial,
        ));

        let mut pending_count_mismatch = repository_result("resolve", "EMPTY");
        pending_count_mismatch["coverage"]["pendingUpdateCount"] = json!(1);
        cases.push((
            "complete coverage with mismatched pending count".to_string(),
            pending_count_mismatch,
        ));

        for (case, result) in cases {
            let projected = project_repository_envelope(
                result_envelope("repository/query".to_string(), result),
                AgentResultView::Compact,
            );
            assert!(!projected.ok, "{case} was accepted");
        }
    }
