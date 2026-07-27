#[test]
fn workspace_file_result_views_are_family_specific_and_exclusive() {
    for (accepted, view, fields) in [
        (vec!["--verbose"], "verbose", serde_json::json!([])),
        (vec!["--explain"], "explain", serde_json::json!([])),
        (vec!["--count"], "count", serde_json::json!([])),
        (
            vec![
                "--fields",
                "path,module,source-set,kind,package,index,drift,dirty,evidence",
            ],
            "fields",
            serde_json::json!([
                "path",
                "module",
                "source-set",
                "kind",
                "package",
                "index",
                "drift",
                "dirty",
                "evidence"
            ]),
        ),
    ] {
        let stdout = assert_typed_boundary(&accepted);
        let query = &stdout["error"]["details"]["admittedQuery"];
        assert_eq!(query["view"], view, "{stdout:#}");
        assert_eq!(query["orderedFields"], fields, "{stdout:#}");
    }

    for rejected in [
        vec!["--verbose", "--explain"],
        vec!["--fields", "path", "--count"],
        vec!["--fields", "identity"],
    ] {
        assert_usage_error(&rejected);
    }
}
