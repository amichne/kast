fn assert_exact_path_evidence(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
) -> serde_json::Value {
    let (_, exact) = rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "exact",
            "method": "repository/query",
            "params": {
                "question": "Resolve SemanticGraphSha256.parse exactly.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(exact["result"]["status"], "ANSWERED", "{exact:#}");
    assert_eq!(exact["result"]["nodes"][0]["name"], "parse");
    assert_eq!(
        exact["result"]["nodes"][0]["ownerName"],
        "SemanticGraphSha256"
    );
    assert_eq!(
        exact["result"]["nodes"][0]["parameterTypes"],
        serde_json::json!(["kotlin.String"])
    );

    let (_, path) = rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "path",
            "method": "repository/query",
            "params": {
                "question": "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
                "intent": "path",
                "scope": {
                    "language": "kotlin",
                    "relations": ["CALLS"],
                    "direction": "OUTGOING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 1}
            }
        }),
    );
    assert_eq!(path["result"]["status"], "ANSWERED", "{path:#}");
    assert!(path["result"]["paths"].as_array().is_some_and(|paths| {
        paths.iter().any(|path| {
            path["nodes"].as_array().is_some_and(|nodes| {
                nodes
                    .first()
                    .is_some_and(|node| node["name"] == "semanticGraphOperation")
                    && nodes.last().is_some_and(|node| node["name"] == "parse")
            })
        })
    }));
    assert!(path["result"]["edges"].as_array().is_some_and(|edges| {
        edges.iter().all(|edge| {
            edge["occurrences"]
                .as_array()
                .is_some_and(|values| !values.is_empty())
                || edge["derivation"].is_object()
        })
    }));
    assert!(path["result"]["edges"].as_array().is_some_and(|edges| {
        edges.iter().any(|edge| {
            edge["evidenceClass"] == "compiler"
                && edge["derivation"]["rule"] == "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER"
        })
    }));
    let derived_edge = path["result"]["edges"]
        .as_array()
        .and_then(|edges| {
            edges
                .iter()
                .find(|edge| edge["derivation"]["rule"] == "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER")
        })
        .expect("derived path edge");
    assert_eq!(derived_edge["occurrenceCount"], 3);
    assert_eq!(derived_edge["evidenceTruncated"], true);
    let continuation = derived_edge["evidenceContinuation"].clone();

    let source_path = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let source = std::fs::read(&source_path).expect("indexed Kotlin source");
    std::fs::write(&source_path, b"changed after evidence page")
        .expect("change coverage composition");
    let (changed_status, changed) = rpc(
        home,
        config_home,
        workspace,
        repository_path_page_request("changed-evidence", continuation.clone(), 1),
    );
    assert!(!changed_status.success(), "{changed:#}");
    assert_eq!(
        changed["code"], "STALE_REPOSITORY_CONTINUATION",
        "{changed:#}"
    );
    std::fs::write(&source_path, source).expect("restore indexed Kotlin source");

    let (mismatched_status, mismatched) = rpc(
        home,
        config_home,
        workspace,
        repository_path_page_request("mismatched-evidence", continuation.clone(), 10),
    );
    assert!(!mismatched_status.success(), "{mismatched:#}");
    assert_eq!(
        mismatched["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{mismatched:#}"
    );
    assert!(continuation.is_string());

    let (_, remaining_evidence) = rpc(
        home,
        config_home,
        workspace,
        repository_path_page_request("remaining-evidence", continuation.clone(), 1),
    );
    assert_eq!(
        remaining_evidence["result"]["edges"][0]["occurrences"]
            .as_array()
            .map(Vec::len),
        Some(1),
        "{remaining_evidence:#}"
    );
    assert_eq!(
        remaining_evidence["result"]["edges"][0]["evidenceTruncated"],
        true
    );
    let final_continuation =
        remaining_evidence["result"]["edges"][0]["evidenceContinuation"].clone();
    assert!(final_continuation.is_string());

    let (_, final_evidence) = rpc(
        home,
        config_home,
        workspace,
        repository_path_page_request("final-evidence", final_continuation, 1),
    );
    assert_eq!(
        final_evidence["result"]["edges"][0]["occurrences"]
            .as_array()
            .map(Vec::len),
        Some(1),
        "{final_evidence:#}"
    );
    assert_eq!(
        final_evidence["result"]["edges"][0]["evidenceTruncated"],
        false
    );
    assert!(final_evidence["result"]["continuation"].is_null());

    for (label, pointer, value) in [
        (
            "question",
            "/params/question",
            serde_json::json!(
                "Trace CALLS from semanticGraphOperation to SemanticGraphSha256.parse, please."
            ),
        ),
        (
            "intent",
            "/params/intent",
            serde_json::json!("incoming_impact"),
        ),
        ("module", "/params/scope/module", serde_json::json!("app")),
        (
            "source-set",
            "/params/scope/sourceSet",
            serde_json::json!("main"),
        ),
        (
            "direction",
            "/params/scope/direction",
            serde_json::json!("INCOMING"),
        ),
        (
            "relations",
            "/params/scope/relations",
            serde_json::json!(["REFERENCES"]),
        ),
        (
            "scope-depth",
            "/params/scope/maxDepth",
            serde_json::json!(5),
        ),
        ("depth", "/params/limits/depth", serde_json::json!(5)),
        ("results", "/params/limits/results", serde_json::json!(9)),
    ] {
        let mut request = repository_path_page_request(label, continuation.clone(), 1);
        *request.pointer_mut(pointer).expect("mismatch field") = value;
        if label == "intent" {
            request["params"]["scope"]["direction"] = serde_json::Value::Null;
        }
        let (status, response) = rpc(home, config_home, workspace, request);
        assert!(!status.success(), "{label}: {response:#}");
        assert_eq!(
            response["code"], "INVALID_REPOSITORY_CONTINUATION",
            "{label}: {response:#}"
        );
    }

    let mut forged = continuation
        .as_str()
        .expect("opaque repository continuation")
        .as_bytes()
        .to_vec();
    let final_byte = forged.last_mut().expect("continuation signature");
    *final_byte = if *final_byte == b'0' { b'1' } else { b'0' };
    let forged = String::from_utf8(forged).expect("ASCII continuation");
    let (forged_status, forged_response) = rpc(
        home,
        config_home,
        workspace,
        repository_path_page_request("forged", serde_json::json!(forged), 1),
    );
    assert!(!forged_status.success(), "{forged_response:#}");
    assert_eq!(
        forged_response["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{forged_response:#}"
    );
    continuation
}
