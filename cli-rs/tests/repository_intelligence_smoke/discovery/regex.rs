mod regex_discovery {
    use super::*;

    fn query(question: &str, syntax: Option<&str>) -> serde_json::Value {
        let mut params = serde_json::json!({
            "question": question,
            "intent": "resolve",
            "scope": {"language": "kotlin"},
            "limits": {"depth": 1, "results": 10, "evidence": 2}
        });
        if let Some(syntax) = syntax {
            params["querySyntax"] = serde_json::json!(syntax);
        }
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "regex-discovery",
            "method": "repository/query",
            "params": params
        })
    }

    #[test]
    fn natural_language_is_the_default() {
        let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
        seed_repository_graph(&fixture);

        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            query(
                "Find the function that builds a semantic graph snapshot.",
                None,
            ),
        );

        assert!(status.success(), "{response:#}");
        assert_eq!(
            serde_json::json!([
                response["result"]["status"],
                response["result"]["selectedIdentity"],
                response["result"]["queryPlan"]["querySyntax"],
                response["result"]["queryPlan"]["discovery"]
            ]),
            serde_json::json!([
                "ANSWERED",
                "callable:buildSemanticGraphSnapshot",
                "NATURAL_LANGUAGE",
                "LEXICAL"
            ]),
            "{response:#}"
        );
    }

    #[test]
    fn repository_discovery_reports_syntax_specific_ordering() {
        let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
        seed_repository_graph(&fixture);

        let (_, natural_language) = rpc(
            &home,
            &config_home,
            &workspace,
            query(
                "Find the function that builds a semantic graph snapshot.",
                None,
            ),
        );
        let (_, regex) = rpc(
            &home,
            &config_home,
            &workspace,
            query("parse", Some("regex")),
        );

        assert_eq!(
            serde_json::json!({
                "naturalLanguage": natural_language["result"]["ordering"],
                "regex": regex["result"]["ordering"]
            }),
            serde_json::json!({
                "naturalLanguage": "matchScore descending, canonicalKey ascending",
                "regex": "canonicalKey ascending"
            })
        );
    }

    #[test]
    fn unique_regex_resolves_through_the_agent_cli() {
        let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
        seed_repository_graph(&fixture);
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "repository",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
                "--question",
                "^buildSemanticGraphSnapshot$",
                "--query-syntax",
                "regex",
                "--intent",
                "resolve",
            ])
            .output()
            .expect("agent regex discovery");
        let response: serde_json::Value =
            serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
                panic!(
                    "agent regex JSON: {error}; stdout={} stderr={}",
                    String::from_utf8_lossy(&output.stdout),
                    String::from_utf8_lossy(&output.stderr)
                )
            });

        assert!(output.status.success(), "{response:#}");
        assert_eq!(
            serde_json::json!([
                response["result"]["status"],
                response["result"]["identities"][0]["canonicalKey"],
                response["result"]["querySyntax"]
            ]),
            serde_json::json!([
                "ANSWERED",
                "callable:buildSemanticGraphSnapshot",
                "REGEX"
            ]),
            "{response:#}"
        );
    }

    #[test]
    fn invalid_regex_is_rejected_before_repository_execution() {
        let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
        fixture
            .connection()
            .execute("DROP TABLE semantic_files", [])
            .expect("remove repository execution authority");

        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            query("(", Some("regex")),
        );

        assert!(!status.success(), "{response:#}");
        assert_eq!(
            serde_json::json!([
                response["code"],
                response["details"]["field"],
                response["details"]["remedy"]
            ]),
            serde_json::json!([
                "INVALID_REPOSITORY_REGEX",
                "question",
                "Use a valid Rust regex in question, set querySyntax=natural_language, or pass --query-syntax natural-language."
            ]),
            "{response:#}"
        );
    }

    #[test]
    fn unknown_query_syntax_is_rejected_before_repository_execution() {
        let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
        fixture
            .connection()
            .execute("DROP TABLE semantic_files", [])
            .expect("remove repository execution authority");

        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            query("buildSemanticGraphSnapshot", Some("glob")),
        );

        assert!(!status.success(), "{response:#}");
        assert_eq!(response["code"], "INVALID_REPOSITORY_QUERY", "{response:#}");
        assert!(
            response["message"]
                .as_str()
                .is_some_and(|message| message.contains("natural_language")
                    && message.contains("regex")),
            "{response:#}"
        );
    }

    #[test]
    fn ambiguous_regex_does_not_guess() {
        let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
        seed_repository_graph(&fixture);

        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            query("^parse$", Some("regex")),
        );
        let identities = response["result"]["candidates"]
            .as_array()
            .expect("regex candidates")
            .iter()
            .map(|candidate| {
                candidate["canonicalKey"]
                    .as_str()
                    .expect("candidate identity")
            })
            .collect::<std::collections::BTreeSet<_>>();
        assert!(
            response["result"]["candidates"]
                .as_array()
                .is_some_and(|candidates| candidates.iter().all(
                    |candidate| candidate["matchReasons"][0]["field"] == "name"
                )),
            "{response:#}"
        );

        assert!(status.success(), "{response:#}");
        assert_eq!(
            serde_json::json!({
                "status": response["result"]["status"],
                "selectedIdentity": response["result"]["selectedIdentity"],
                "querySyntax": response["result"]["queryPlan"]["querySyntax"],
                "discovery": response["result"]["queryPlan"]["discovery"],
                "identities": identities
            }),
            serde_json::json!({
                "status": "AMBIGUOUS",
                "selectedIdentity": null,
                "querySyntax": "REGEX",
                "discovery": "REGEX",
                "identities": [
                    "callable:SemanticGraphSha256.parse",
                    "callable:other.parse"
                ]
            }),
            "{response:#}"
        );
    }
}
