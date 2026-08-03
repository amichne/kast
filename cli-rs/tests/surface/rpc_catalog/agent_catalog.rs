#[test]
fn command_catalog_schema_rejects_unrecognized_command_properties() {
    let catalog_schema = schema_value("protocol/source/commands.schema.json");
    let validator = jsonschema::validator_for(&catalog_schema).expect("schema compiles");
    let mut invalid_catalog = catalog();
    invalid_catalog["commands"]["symbol/query"]["unrecognized"] = Value::Bool(true);

    assert!(
        validator.validate(&invalid_catalog).is_err(),
        "declaring failureReasons must not weaken command additional-property checks"
    );
}

#[test]
fn command_catalog_schema_accepts_only_closed_nested_variant_fields() {
    let catalog_schema = schema_value("protocol/source/commands.schema.json");
    let validator = jsonschema::validator_for(&catalog_schema).expect("schema compiles");
    let valid_catalog = catalog();

    assert_valid(&catalog_schema, &valid_catalog);

    let mut unknown_property = valid_catalog.clone();
    mutation_postcondition_authority(&mut unknown_property)["unrecognized"] = Value::Bool(true);
    assert!(validator.validate(&unknown_property).is_err());

    let mut missing_variants = valid_catalog.clone();
    mutation_postcondition_authority(&mut missing_variants)
        .as_object_mut()
        .expect("authority field")
        .remove("variants");
    assert!(validator.validate(&missing_variants).is_err());

    let mut wrong_type = valid_catalog;
    mutation_postcondition_authority(&mut wrong_type)["type"] =
        Value::String("string".to_string());
    assert!(validator.validate(&wrong_type).is_err());
}

fn mutation_postcondition_authority(catalog: &mut Value) -> &mut Value {
    &mut catalog["commands"]["raw/verify-mutation-postcondition"]["request"]["fields"]
        ["authority"]
}

#[test]
fn symbol_query_catalog_documents_relevance_filters() {
    let catalog = catalog();
    let filters = &catalog["commands"]["symbol/query"]["request"]["fields"]["filters"]["fields"];
    for (field, expected_note) in [
        ("gradleProject", "Gradle project path"),
        ("relativePathPrefix", "workspace-root-relative"),
        ("productionOnly", "sourceSet=main"),
        ("excludePatterns", "module path or relative path"),
        ("usageFacets", "computed declaration facets"),
    ] {
        assert!(
            filters.get(field).is_some(),
            "symbol/query filters should document {field}"
        );
        let note = filters[field]["description"]
            .as_str()
            .unwrap_or_else(|| panic!("symbol/query filter {field} should include a description"));
        assert!(
            note.contains(expected_note),
            "symbol/query filter {field} description should mention {expected_note}: {note}"
        );
    }

    let usage_facets = filters["usageFacets"]["items"]["enum"]
        .as_array()
        .expect("usage facet enum");
    for facet in [
        "PUBLIC_API",
        "INTERNAL_API",
        "MODULE_PRIVATE",
        "BRIDGE",
        "BUILD_LOGIC",
    ] {
        assert!(
            usage_facets.iter().any(|value| value == facet),
            "usageFacets should include {facet}"
        );
    }

    let maximal: Value = serde_json::from_str(include_str!(
        "../../../protocol/source/requests/symbol/query/maximal.json"
    ))
    .expect("symbol/query maximal request");
    let maximal_filters = &maximal["params"]["filters"];
    for field in [
        "gradleProject",
        "relativePathPrefix",
        "productionOnly",
        "excludePatterns",
        "usageFacets",
    ] {
        assert!(
            maximal_filters.get(field).is_some(),
            "symbol/query maximal request should include {field}"
        );
    }
}

#[test]
fn symbol_query_catalog_samples_validate_against_shared_schema() {
    let request_schema = schema_value(
        "../analysis-api/src/main/resources/contracts/symbol-query/symbol-query-request.schema.json",
    );
    let generated_request_schema =
        schema_value("protocol/source/requests/symbol/query/request.schema.json");
    let canonical_minimal: Value = serde_json::from_str(include_str!(
        "../../../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-minimal.json"
    ))
    .expect("canonical minimal request");
    let canonical_maximal: Value = serde_json::from_str(include_str!(
        "../../../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-maximal.json"
    ))
    .expect("canonical maximal request");
    let catalog_minimal: Value = serde_json::from_str(include_str!(
        "../../../protocol/source/requests/symbol/query/minimal.json"
    ))
    .expect("catalog minimal request");
    let catalog_maximal: Value = serde_json::from_str(include_str!(
        "../../../protocol/source/requests/symbol/query/maximal.json"
    ))
    .expect("catalog maximal request");

    assert_valid(&request_schema, &canonical_minimal);
    assert_valid(&request_schema, &canonical_maximal);
    assert_valid(&request_schema, &catalog_minimal);
    assert_valid(&request_schema, &catalog_maximal);
    assert_valid(&generated_request_schema, &canonical_minimal);
    assert_valid(&generated_request_schema, &canonical_maximal);
    assert_valid(&generated_request_schema, &catalog_minimal);
    assert_valid(&generated_request_schema, &catalog_maximal);
    assert_eq!(catalog_minimal, canonical_minimal);
    assert_eq!(catalog_maximal, canonical_maximal);
}

#[test]
fn workspace_files_catalog_declares_generation_bound_server_paging() {
    let catalog = catalog();
    let workspace_files = &catalog["commands"]["raw/workspace-files"];
    let fields = &workspace_files["request"]["fields"];
    let description = workspace_files["tool"]["description"]
        .as_str()
        .expect("workspace files tool description");

    assert_eq!(
        fields["kindDomain"]["enum"],
        serde_json::json!(["SOURCE_ONLY", "SCRIPT_ONLY", "MIXED"])
    );
    assert!(fields.get("snapshotToken").is_some());
    assert!(fields.get("pageToken").is_some());
    assert!(!description.contains("Secondary"), "{description}");
    assert!(
        !description.contains("Prefer symbol/query"),
        "{description}"
    );
    assert!(description.contains("generation-bound"), "{description}");

    let maximal: Value = serde_json::from_str(include_str!(
        "../../../protocol/source/requests/raw/workspace-files/maximal.json"
    ))
    .expect("workspace-files maximal request");
    assert_eq!(maximal["params"]["includeFiles"], Value::Bool(true));
    assert_eq!(
        maximal["params"]["moduleName"],
        Value::String(":analysis-api".to_string())
    );
    assert_eq!(
        maximal["params"]["maxFilesPerModule"],
        Value::Number(25.into())
    );
    assert!(maximal["params"]["snapshotToken"].is_string());
    assert!(maximal["params"]["pageToken"].is_string());

    let metadata_response: Value = serde_json::from_str(include_str!(
        "../../../protocol/examples/workspaceFiles-response.json"
    ))
    .expect("workspace-files metadata response example");
    let metadata_module = &metadata_response["result"]["modules"][0];
    assert!(metadata_response["result"]["snapshotToken"].is_string());
    assert_eq!(metadata_module["returnedFileCount"], 0);
    assert!(metadata_module.get("nextPageToken").is_none());

    let page_response: Value = serde_json::from_str(include_str!(
        "../../../protocol/examples/workspaceFilesPage-response.json"
    ))
    .expect("workspace-files page response example");
    let module = &page_response["result"]["modules"][0];
    assert_eq!(
        page_response["result"]["snapshotToken"],
        metadata_response["result"]["snapshotToken"]
    );
    assert_eq!(module["returnedFileCount"], 1);
    assert!(module["nextPageToken"].is_string());
}

#[test]
fn workspace_files_continuation_catalog_declares_issue_and_consume_variants() {
    let catalog = catalog();
    let continuation = &catalog["commands"]["raw/workspace-files-continuation"];

    assert_eq!(continuation["variantDiscriminator"], "action");
    assert_eq!(
        continuation["request"]["fields"]["action"]["enum"],
        serde_json::json!(["ISSUE", "CONSUME"])
    );
    assert!(
        continuation["variants"]["ISSUE"]["fields"]
            .get("state")
            .is_some()
    );
    assert!(
        continuation["variants"]["CONSUME"]["fields"]
            .get("pageToken")
            .is_some()
    );

    for (variant, expected_action) in [("ISSUE", "ISSUE"), ("CONSUME", "CONSUME")] {
        let sample = schema_value(&format!(
            "protocol/source/requests/raw/workspace-files-continuation/{variant}/minimal.json"
        ));
        assert_eq!(sample["params"]["action"], expected_action);
        assert!(sample["params"].get("type").is_none());
    }
}

#[test]
fn workspace_file_catalog_samples_use_typed_token_and_digest_wire_values() {
    let raw_maximal = schema_value("protocol/source/requests/raw/workspace-files/maximal.json");
    assert_canonical_uuid(
        &raw_maximal["params"]["snapshotToken"],
        "raw snapshot token",
    );
    assert_canonical_uuid(&raw_maximal["params"]["pageToken"], "raw page token");

    for variant in ["minimal", "maximal"] {
        let issue = schema_value(&format!(
            "protocol/source/requests/raw/workspace-files-continuation/ISSUE/{variant}.json"
        ));
        assert_lowercase_sha256(
            &issue["params"]["state"]["compositionStampDigest"],
            "continuation composition stamp",
        );

        let consume = schema_value(&format!(
            "protocol/source/requests/raw/workspace-files-continuation/CONSUME/{variant}.json"
        ));
        assert_canonical_uuid(&consume["params"]["pageToken"], "public continuation token");
    }
}

#[test]
fn api_specification_documents_variant_specific_required_fields() {
    let specification = include_str!("../../../protocol/api-specification.md");
    let continuation = specification
        .split_once(
            "<summary><code>raw/workspace-files-continuation</code> - Issue or consume server-held public workspace-file continuation state</summary>",
        )
        .expect("workspace-files continuation details")
        .1
        .split_once("</details>")
        .expect("workspace-files continuation details end")
        .0;

    assert!(
        continuation.contains("| `ISSUE` | `identity`<br>`state` | none |"),
        "ISSUE requirements must be rendered: {continuation}"
    );
    assert!(
        continuation.contains("| `CONSUME` | `identity`<br>`pageToken` | none |"),
        "CONSUME requirements must be rendered: {continuation}"
    );
}

fn assert_canonical_uuid(value: &Value, context: &str) {
    let value = value
        .as_str()
        .unwrap_or_else(|| panic!("{context} must be a string: {value}"));
    let parsed = uuid::Uuid::parse_str(value)
        .unwrap_or_else(|error| panic!("{context} must be a UUID: {value}: {error}"));
    assert_eq!(parsed.to_string(), value, "{context} must be canonical");
}

fn assert_lowercase_sha256(value: &Value, context: &str) {
    let value = value
        .as_str()
        .unwrap_or_else(|| panic!("{context} must be a string: {value}"));
    assert_eq!(value.len(), 64, "{context} must contain 64 hex digits");
    assert!(
        value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
        "{context} must be lowercase hexadecimal: {value}"
    );
}

#[test]
fn command_catalog_owns_copilot_tool_surface() {
    let catalog = catalog();
    let commands = catalog["commands"].as_object().expect("commands object");
    let tool_names: BTreeSet<_> = commands
        .values()
        .filter_map(|command| command.get("tool"))
        .map(|tool| tool["name"].as_str().expect("tool name"))
        .collect();
    let expected = BTreeSet::from([
        "kast_callers",
        "kast_diagnostics",
        "kast_file_outline",
        "kast_metrics",
        "kast_references",
        "kast_rename",
        "kast_resolve",
        "kast_scaffold",
        "kast_symbol_discover",
        "kast_symbol_query",
        "kast_workspace_files",
        "kast_workspace_search",
        "kast_workspace_symbol",
        "kast_write_and_validate",
    ]);
    assert_eq!(tool_names, expected);
}

#[test]
fn agent_tool_surface_exposes_navigation_without_internal_transport_leaks() {
    let catalog = catalog();
    let commands = catalog["commands"].as_object().expect("commands object");
    for (method, expected_name) in [
        ("symbol/query", "kast_symbol_query"),
        ("symbol/callers", "kast_callers"),
        ("database/metrics", "kast_metrics"),
    ] {
        let tool = commands[method]
            .get("tool")
            .unwrap_or_else(|| panic!("{method} should be exposed as an agent tool"));
        assert_eq!(tool["name"], expected_name);
        let description = tool["description"]
            .as_str()
            .unwrap_or_else(|| panic!("{method} tool description"));
        assert!(
            !description.contains("Rust-owned")
                && !description.contains("daemon passthrough")
                && !description.contains("JVM")
                && !description.contains("/rpc/")
                && !description.contains("capabilities.experimental.kastMethods"),
            "{method} tool description should not expose implementation routing details: {description}"
        );
    }

    let query_description = commands["symbol/query"]["tool"]["description"]
        .as_str()
        .expect("symbol/query tool description");
    assert!(
        query_description.contains("unknown symbols") && query_description.contains(".kt/.kts"),
        "symbol/query should be disclosed as the first navigation step for Kotlin files: {query_description}"
    );
    let metrics_description = commands["database/metrics"]["tool"]["description"]
        .as_str()
        .expect("database/metrics tool description");
    assert!(
        metrics_description.contains("database-backed")
            && metrics_description.contains("impact questions"),
        "database/metrics should disclose source-index database access: {metrics_description}"
    );
}
