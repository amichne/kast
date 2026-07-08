mod support;

use serde_json::Value;
use support::metrics::*;

#[test]
fn symbol_query_reports_token_evidence_for_camel_case_declarations() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let response = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        44,
        serde_json::json!({
            "query": "card payment",
            "modes": ["lexical"],
            "limit": 10
        }),
    );
    assert_symbol_query_response_matches_schema(&response);
    assert_eq!(
        response["result"]["results"][0]["declaration"]["fqName"],
        Value::String("lib.CardPaymentProcessor".to_string())
    );
    let lexical_matches = response["result"]["results"][0]["signals"]["lexical"]["matches"]
        .as_array()
        .expect("lexical matches");
    for term in ["card", "payment"] {
        assert!(
            lexical_matches.iter().any(|hit| {
                hit["field"] == Value::String("fq_names.fq_name".to_string())
                    && hit["term"] == Value::String(term.to_string())
                    && hit["matchType"] == Value::String("TOKEN".to_string())
                    && hit["evidence"] == Value::String("lib.CardPaymentProcessor".to_string())
            }),
            "symbol/query should report {term} as TOKEN evidence: {response}"
        );
    }
}

#[test]
fn symbol_query_applies_new_filters_and_reports_filter_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let filtered = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        45,
        serde_json::json!({
            "query": "processor",
            "modes": ["lexical"],
            "filters": {
                "gradleProject": ":lib",
                "relativePathPrefix": "lib/",
                "productionOnly": true,
                "excludePatterns": ["build-logic/**"]
            },
            "limit": 10
        }),
    );
    assert_eq!(
        result_fq_names(&filtered),
        vec!["lib.CardPaymentProcessor".to_string()]
    );
    assert_hard_filter_fields(
        &filtered,
        [
            "gradleProject",
            "relativePathPrefix",
            "productionOnly",
            "excludePatterns",
        ],
    );
    assert_structural_constraint_fields(
        &filtered,
        [
            "gradleProject",
            "relativePathPrefix",
            "productionOnly",
            "excludePatterns",
        ],
    );

    let gradle_prefix = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        46,
        serde_json::json!({
            "query": "bridge",
            "modes": ["lexical"],
            "filters": {
                "gradleProject": ":lib"
            },
            "limit": 10
        }),
    );
    assert_eq!(
        result_fq_names(&gradle_prefix),
        vec!["lib.payments.PaymentBridge".to_string()]
    );

    let relative_prefix = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        47,
        serde_json::json!({
            "query": "processor",
            "modes": ["lexical"],
            "filters": {
                "relativePathPrefix": "lib/test/"
            },
            "limit": 10
        }),
    );
    assert_eq!(
        result_fq_names(&relative_prefix),
        vec!["lib.CardPaymentProcessorTest".to_string()]
    );

    let excluded = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        48,
        serde_json::json!({
            "query": "processor",
            "modes": ["lexical"],
            "filters": {
                "productionOnly": true,
                "excludePatterns": ["lib/CardPaymentProcessor.kt"]
            },
            "limit": 10
        }),
    );
    assert_eq!(result_fq_names(&excluded), Vec::<String>::new());
}

#[test]
fn symbol_query_filters_test_fixtures_by_gradle_module_and_source_set() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let fixture = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        49,
        serde_json::json!({
            "query": "FakeAnalysisBackend",
            "modes": ["lexical", "structural"],
            "filters": {
                "gradleProject": ":analysis-api",
                "modulePath": ":analysis-api",
                "sourceSet": "testFixtures"
            },
            "limit": 10
        }),
    );

    assert_eq!(
        result_fq_names(&fixture),
        vec!["io.github.amichne.kast.testing.FakeAnalysisBackend".to_string()]
    );
    assert_hard_filter_fields(&fixture, ["gradleProject", "modulePath", "sourceSet"]);
}

#[test]
fn symbol_query_computes_usage_facets_and_filters_by_them() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let public_bridge = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        49,
        serde_json::json!({
            "query": "A",
            "modes": ["exact"],
            "filters": {
                "usageFacets": ["BRIDGE"]
            },
            "limit": 10
        }),
    );
    assert_eq!(result_fq_names(&public_bridge), vec!["app.A".to_string()]);
    assert_symbol_query_response_matches_schema(&public_bridge);
    assert_declaration_facets(&public_bridge, ["PUBLIC_API", "BRIDGE"]);
    assert_hard_filter_fields(&public_bridge, ["usageFacets"]);

    let internal = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        50,
        serde_json::json!({
            "query": "Bar",
            "modes": ["exact"],
            "limit": 10
        }),
    );
    assert_declaration_facets(&internal, ["INTERNAL_API"]);

    let module_private = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        51,
        serde_json::json!({
            "query": "Unused",
            "modes": ["exact"],
            "limit": 10
        }),
    );
    assert_declaration_facets(&module_private, ["MODULE_PRIVATE"]);

    let build_logic = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        52,
        serde_json::json!({
            "query": "build payment",
            "modes": ["lexical"],
            "filters": {
                "usageFacets": ["BUILD_LOGIC"]
            },
            "limit": 10
        }),
    );
    assert_eq!(
        result_fq_names(&build_logic),
        vec!["buildlogic.BuildPaymentProcessor".to_string()]
    );
    assert_declaration_facets(&build_logic, ["PUBLIC_API", "BUILD_LOGIC"]);
}

#[test]
fn symbol_query_failure_response_matches_shared_schema() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let response = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        53,
        serde_json::json!({
            "query": "",
            "modes": ["exact"],
            "limit": 10
        }),
    );

    assert_eq!(
        response["result"]["type"],
        Value::String("SYMBOL_QUERY_FAILURE".to_string())
    );
    assert_symbol_query_response_matches_schema(&response);
}
