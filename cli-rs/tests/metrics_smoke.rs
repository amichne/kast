use rusqlite::{Connection, params};
use serde_json::Value;
use std::process::Command;

fn kast(home: &std::path::Path, config_home: &std::path::Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    command
}

fn symbol_query_response_schema() -> Value {
    serde_json::from_str(include_str!(
        "../../analysis-api/src/main/resources/contracts/symbol-query/symbol-query-response.schema.json"
    ))
    .expect("symbol/query response schema")
}

fn assert_symbol_query_response_matches_schema(response: &Value) {
    let schema = symbol_query_response_schema();
    let validator = jsonschema::validator_for(&schema).expect("schema compiles");
    if let Err(error) = validator.validate(response) {
        panic!("symbol/query response does not match schema: {error}\nresponse: {response}");
    }
}

#[test]
fn reads_metrics_directly_from_source_index_db() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let fan_in = kast(&home, &config_home)
        .args([
            "metrics",
            "fan-in",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--limit",
            "1",
        ])
        .output()
        .expect("metrics fan-in");
    assert!(
        fan_in.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&fan_in.stderr)
    );
    let fan_in_stdout = String::from_utf8_lossy(&fan_in.stdout);
    assert!(fan_in_stdout.starts_with("Kast metrics fan-in\n==================="));
    assert!(!fan_in_stdout.contains("# Kast metrics fan-in"));
    assert!(fan_in_stdout.contains("targetFqName=lib.Foo"));
    assert!(fan_in_stdout.contains("occurrenceCount=3"));
    assert!(serde_json::from_slice::<Value>(&fan_in.stdout).is_err());

    let fan_in_json = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "metrics",
            "fan-in",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--limit",
            "1",
        ])
        .output()
        .expect("metrics fan-in json");
    assert!(
        fan_in_json.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&fan_in_json.stderr)
    );
    let fan_in_json_stdout = String::from_utf8_lossy(&fan_in_json.stdout);
    assert!(fan_in_json_stdout.contains("\"targetFqName\": \"lib.Foo\""));
    assert!(fan_in_json_stdout.contains("\"occurrenceCount\": 3"));

    let search = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "metrics",
            "search",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "Foo",
        ])
        .output()
        .expect("metrics search");
    assert!(
        search.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&search.stderr)
    );
    assert!(String::from_utf8_lossy(&search.stdout).contains("\"lib.Foo\""));

    let short_search = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "metrics",
            "search",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "Fo",
        ])
        .output()
        .expect("metrics short search");
    assert!(
        short_search.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&short_search.stderr)
    );
    assert!(String::from_utf8_lossy(&short_search.stdout).contains("\"lib.FooWidget\""));

    let metrics_help = kast(&home, &config_home)
        .args(["metrics", "--help"])
        .output()
        .expect("metrics help");
    assert!(
        metrics_help.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&metrics_help.stderr)
    );
    let demo = kast(&home, &config_home)
        .args([
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--json",
            "--query",
            "Fo",
            "--limit",
            "10",
        ])
        .output()
        .expect("demo snapshot");
    assert!(
        demo.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&demo.stderr)
    );
    let demo_short_search_json: Value =
        serde_json::from_slice(&demo.stdout).expect("compare demo json");
    assert_eq!(
        demo_short_search_json["snapshot"]["mode"],
        Value::String("searchCompare".to_string())
    );
    assert_eq!(
        demo_short_search_json["snapshot"]["viewMode"],
        Value::String("full".to_string())
    );
    assert_eq!(
        demo_short_search_json["snapshot"]["sort"],
        Value::String("module".to_string())
    );
    assert!(
        demo_short_search_json["snapshot"]["rightPane"]["rows"]
            .as_array()
            .expect("semantic rows")
            .iter()
            .any(|row| row["fqName"] == Value::String("lib.FooWidget".to_string())),
        "compare demo should include semantic FooWidget: {demo_short_search_json}"
    );
    assert!(
        demo_short_search_json["snapshot"]["leftPane"]["rows"]
            .as_array()
            .expect("lexical rows")
            .iter()
            .any(|row| row["label"] == Value::String("FooNotes".to_string())
                && row["badge"] == Value::String("lexicalOnly".to_string())),
        "compare demo should show lexical-only candidates: {demo_short_search_json}"
    );
    assert!(
        !demo_short_search_json["snapshot"]["leftPane"]["rows"]
            .as_array()
            .expect("lexical rows")
            .iter()
            .any(|row| row["fqName"] == Value::String("app.A".to_string())),
        "lexical identifier hits in A.kt must not be attributed to the app.A declaration: {demo_short_search_json}"
    );
    assert!(
        demo_short_search_json["snapshot"]["leftPane"]["rows"]
            .as_array()
            .expect("lexical rows")
            .iter()
            .any(|row| row["label"] == Value::String("Foo".to_string())
                && row["fqName"].is_null()
                && row["path"]
                    .as_str()
                    .is_some_and(|path| path.ends_with("app/A.kt"))),
        "compare demo should keep source tokens as lexical rows: {demo_short_search_json}"
    );
    assert!(
        demo_short_search_json["snapshot"]["filters"]["chips"]
            .as_array()
            .expect("filter chips")
            .iter()
            .any(|chip| chip["key"] == Value::String("kind".to_string())
                && chip["selected"] == Value::String("any".to_string())),
        "compare demo should expose compact filter chips: {demo_short_search_json}"
    );
    assert!(
        demo_short_search_json["snapshot"]["diffBuckets"]["lexicalOnly"]
            .as_array()
            .expect("lexical-only diff bucket")
            .iter()
            .any(|row| row["label"] == Value::String("FooNotes".to_string())),
        "compare diff should include lexical-only FooNotes: {demo_short_search_json}"
    );
    assert!(
        demo_short_search_json["snapshot"]["preview"]["title"]
            .as_str()
            .expect("preview title")
            .contains("Foo"),
        "compare demo should include a useful source preview: {demo_short_search_json}"
    );

    let demo_symbol_view = kast(&home, &config_home)
        .args([
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--view",
            "symbol",
            "--json",
            "--symbol",
            "app.A",
        ])
        .output()
        .expect("demo symbol view snapshot");
    assert!(
        demo_symbol_view.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&demo_symbol_view.stderr)
    );
    let demo_symbol_view_json: Value =
        serde_json::from_slice(&demo_symbol_view.stdout).expect("symbol view json");
    assert_eq!(
        demo_symbol_view_json["snapshot"]["mode"],
        Value::String("symbolWalk".to_string())
    );
    assert_eq!(
        demo_symbol_view_json["snapshot"]["current"]["fqName"],
        Value::String("app.A".to_string())
    );

    let demo_help = kast(&home, &config_home)
        .args(["demo", "--help"])
        .output()
        .expect("demo help");
    assert!(
        demo_help.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&demo_help.stderr)
    );
    let demo_help_stdout = String::from_utf8_lossy(&demo_help.stdout);
    assert!(
        demo_help_stdout.contains("compare"),
        "compare demo should remain exposed: {demo_help_stdout}"
    );
    assert!(
        demo_help_stdout.contains("symbol"),
        "symbol demo should remain exposed: {demo_help_stdout}"
    );
    let metrics_rpc_request = serde_json::json!({
        "jsonrpc": "2.0",
        "method": "database/metrics",
        "params": {
            "metric": "fanIn",
            "limit": 1
        },
        "id": 41
    });
    let metrics_rpc_body = metrics_rpc_request.to_string();
    let metrics_rpc = kast(&home, &config_home)
        .args([
            "rpc",
            metrics_rpc_body.as_str(),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("metrics rpc");
    assert!(
        metrics_rpc.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&metrics_rpc.stderr)
    );
    let metrics_rpc_json: Value =
        serde_json::from_slice(&metrics_rpc.stdout).expect("metrics rpc json");
    assert_eq!(
        metrics_rpc_json["jsonrpc"],
        Value::String("2.0".to_string())
    );
    assert_eq!(metrics_rpc_json["id"], Value::Number(41.into()));
    assert_eq!(
        metrics_rpc_json["result"]["type"],
        Value::String("METRICS_SUCCESS".to_string())
    );
    assert_eq!(
        metrics_rpc_json["result"]["results"][0]["targetFqName"],
        Value::String("lib.Foo".to_string())
    );

    let unsupported_metrics_rpc_request = serde_json::json!({
        "jsonrpc": "2.0",
        "method": "database/metrics",
        "params": {
            "metric": "apiSurface"
        },
        "id": 43
    });
    let unsupported_metrics_rpc_body = unsupported_metrics_rpc_request.to_string();
    let unsupported_metrics_rpc = kast(&home, &config_home)
        .args([
            "rpc",
            unsupported_metrics_rpc_body.as_str(),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("unsupported metrics rpc");
    assert!(
        unsupported_metrics_rpc.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&unsupported_metrics_rpc.stderr)
    );
    let unsupported_metrics_rpc_json: Value =
        serde_json::from_slice(&unsupported_metrics_rpc.stdout).expect("unsupported metrics json");
    assert_eq!(
        unsupported_metrics_rpc_json["result"]["type"],
        Value::String("METRICS_FAILURE".to_string())
    );
    assert_eq!(
        unsupported_metrics_rpc_json["result"]["stage"],
        Value::String("validate".to_string())
    );

    let symbol_rpc_request = serde_json::json!({
        "jsonrpc": "2.0",
        "method": "symbol/query",
        "params": {
            "query": "lib.Foo",
            "modes": ["exact", "structural", "graph"],
            "filters": {
                "modulePath": ":lib",
                "sourceSet": "main",
                "kinds": ["CLASS"]
            },
            "graph": {
                "direction": "INCOMING",
                "edgeKinds": ["CALL"],
                "depth": 1,
                "maxEdgesPerResult": 5
            },
            "limit": 10,
            "includeNextRequests": true
        },
        "id": 42
    });
    let symbol_rpc_body = symbol_rpc_request.to_string();
    let symbol_rpc = kast(&home, &config_home)
        .args([
            "rpc",
            symbol_rpc_body.as_str(),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("symbol query rpc");
    assert!(
        symbol_rpc.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&symbol_rpc.stderr)
    );
    let symbol_rpc_json: Value =
        serde_json::from_slice(&symbol_rpc.stdout).expect("symbol query json");
    assert_symbol_query_response_matches_schema(&symbol_rpc_json);
    assert_eq!(symbol_rpc_json["jsonrpc"], Value::String("2.0".to_string()));
    assert_eq!(symbol_rpc_json["id"], Value::Number(42.into()));
    assert_eq!(
        symbol_rpc_json["result"]["type"],
        Value::String("SYMBOL_QUERY_SUCCESS".to_string())
    );
    assert_eq!(
        symbol_rpc_json["result"]["results"][0]["declaration"]["fqName"],
        Value::String("lib.Foo".to_string())
    );
    assert!(
        symbol_rpc_json["result"]["results"][0]["signals"]["graph"]["paths"]
            .as_array()
            .expect("graph paths")
            .iter()
            .any(|path| path["fromFqName"] == Value::String("app.A".to_string())),
        "symbol/query should include direct SQLite graph evidence: {symbol_rpc_json}"
    );
}

#[test]
fn symbol_query_reports_token_evidence_for_camel_case_declarations() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);

    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "method": "symbol/query",
        "params": {
            "query": "card payment",
            "modes": ["lexical"],
            "limit": 10
        },
        "id": 44
    });
    let body = request.to_string();
    let output = kast(&home, &config_home)
        .args([
            "rpc",
            body.as_str(),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("symbol query rpc");
    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let response: Value = serde_json::from_slice(&output.stdout).expect("symbol query json");
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

fn run_symbol_query(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    id: i64,
    params: Value,
) -> Value {
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "method": "symbol/query",
        "params": params,
        "id": id
    });
    let body = request.to_string();
    let output = kast(home, config_home)
        .args([
            "rpc",
            body.as_str(),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("symbol query rpc");
    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).expect("symbol query json")
}

fn result_fq_names(response: &Value) -> Vec<String> {
    response["result"]["results"]
        .as_array()
        .expect("results")
        .iter()
        .map(|result| {
            result["declaration"]["fqName"]
                .as_str()
                .expect("fqName")
                .to_string()
        })
        .collect()
}

fn assert_hard_filter_fields<const N: usize>(response: &Value, expected: [&str; N]) {
    let fields: std::collections::BTreeSet<_> = response["result"]["hardFilters"]
        .as_array()
        .expect("hard filters")
        .iter()
        .map(|filter| filter["field"].as_str().expect("hard filter field"))
        .collect();
    for field in expected {
        assert!(
            fields.contains(field),
            "hard filters should include {field}: {response}"
        );
    }
}

fn assert_structural_constraint_fields<const N: usize>(response: &Value, expected: [&str; N]) {
    let fields: std::collections::BTreeSet<_> =
        response["result"]["results"][0]["signals"]["structural"]["constraints"]
            .as_array()
            .expect("structural constraints")
            .iter()
            .map(|constraint| constraint["field"].as_str().expect("constraint field"))
            .collect();
    for field in expected {
        assert!(
            fields.contains(field),
            "structural constraints should include {field}: {response}"
        );
    }
}

fn assert_declaration_facets<const N: usize>(response: &Value, expected: [&str; N]) {
    let facets: std::collections::BTreeSet<_> =
        response["result"]["results"][0]["declaration"]["usageFacets"]
            .as_array()
            .expect("usage facets")
            .iter()
            .map(|facet| facet.as_str().expect("usage facet"))
            .collect();
    for facet in expected {
        assert!(
            facets.contains(facet),
            "usage facets should include {facet}: {response}"
        );
    }
}

fn seed_source_index(workspace: &std::path::Path) {
    let db_path = workspace.join(".gradle/kast/cache/source-index.db");
    std::fs::create_dir_all(db_path.parent().expect("db parent")).expect("db parent");
    seed_source_files(workspace);
    let conn = Connection::open(db_path).expect("sqlite");
    conn.execute_batch(&format!(
        r#"
        CREATE TABLE schema_version (version INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0, head_commit TEXT);
        INSERT INTO schema_version (version, generation, head_commit) VALUES ({}, 0, NULL);
        CREATE TABLE path_prefixes (prefix_id INTEGER PRIMARY KEY, dir_path TEXT NOT NULL UNIQUE);
        CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE);
        CREATE VIRTUAL TABLE fq_names_fts USING fts5(fq_name, tokenize='trigram');
        CREATE TRIGGER fq_names_ai AFTER INSERT ON fq_names BEGIN
            INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
        END;
        CREATE TRIGGER fq_names_ad AFTER DELETE ON fq_names BEGIN
            DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
        END;
        CREATE TRIGGER fq_names_au AFTER UPDATE OF fq_name ON fq_names BEGIN
            DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
            INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
        END;
        CREATE TABLE identifier_paths (identifier TEXT NOT NULL, prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, PRIMARY KEY (identifier, prefix_id, filename));
        CREATE TABLE file_metadata (prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, package_fq_id INTEGER, module_path TEXT, source_set TEXT, PRIMARY KEY (prefix_id, filename));
        CREATE TABLE file_manifest (prefix_id INTEGER NOT NULL, filename TEXT NOT NULL, last_modified_millis INTEGER NOT NULL, PRIMARY KEY (prefix_id, filename));
        CREATE TABLE declarations (
            fq_id INTEGER NOT NULL,
            kind TEXT NOT NULL,
            visibility TEXT NOT NULL,
            prefix_id INTEGER NOT NULL,
            filename TEXT NOT NULL,
            declaration_offset INTEGER,
            module_path TEXT,
            source_set TEXT,
            PRIMARY KEY (fq_id, prefix_id, filename)
        );
        CREATE TABLE symbol_references (
            src_prefix_id INTEGER NOT NULL,
            src_filename TEXT NOT NULL,
            source_offset INTEGER NOT NULL,
            source_fq_id INTEGER,
            target_fq_id INTEGER NOT NULL,
            tgt_prefix_id INTEGER,
            tgt_filename TEXT,
            target_offset INTEGER,
            edge_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
            PRIMARY KEY (src_prefix_id, src_filename, source_offset, target_fq_id)
        );
        "#,
        source_index_schema_version(),
    ))
    .expect("schema");

    for (prefix_id, dir_path) in [
        (1, "app"),
        (2, "lib"),
        (3, "lib/test"),
        (4, "build-logic"),
        (5, "lib/payments"),
        (
            6,
            "analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing",
        ),
    ] {
        conn.execute(
            "INSERT INTO path_prefixes VALUES (?, ?)",
            params![prefix_id, dir_path],
        )
        .expect("path prefix");
    }
    for (id, name) in [
        (1, "app.A"),
        (2, "app.B"),
        (3, "lib.Foo"),
        (4, "lib.Bar"),
        (5, "app.Unused"),
        (6, "lib.FooWidget"),
        (7, "lib.CardPaymentProcessor"),
        (8, "lib.CardPaymentProcessorTest"),
        (9, "buildlogic.BuildPaymentProcessor"),
        (10, "lib.payments.PaymentBridge"),
        (11, "io.github.amichne.kast.testing.FakeAnalysisBackend"),
    ] {
        conn.execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
            params![id, name],
        )
        .expect("fq name");
    }
    for (prefix, filename, module, source_set) in [
        (1, "A.kt", ":app", "main"),
        (1, "B.kt", ":app", "main"),
        (1, "Unused.kt", ":app", "main"),
        (2, "Foo.kt", ":lib", "main"),
        (2, "Bar.kt", ":lib", "main"),
        (2, "FooWidget.kt", ":lib", "main"),
        (2, "FooNotes.md", ":lib", "main"),
        (2, "CardPaymentProcessor.kt", ":lib", "main"),
        (3, "CardPaymentProcessorTest.kt", ":lib", "test"),
        (4, "BuildPaymentProcessor.kt", ":build-logic", "main"),
        (5, "PaymentBridge.kt", ":lib:payments", "main"),
        (6, "FakeAnalysisBackend.kt", ":analysis-api", "testFixtures"),
    ] {
        conn.execute(
            "INSERT INTO file_metadata(prefix_id, filename, module_path, source_set) VALUES (?, ?, ?, ?)",
            params![prefix, filename, module, source_set],
        )
        .expect("file metadata");
        conn.execute(
            "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (?, ?, 1)",
            params![prefix, filename],
        )
        .expect("file manifest");
        if filename.ends_with(".kt") {
            conn.execute(
                "INSERT INTO identifier_paths(identifier, prefix_id, filename) VALUES (?, ?, ?)",
                params![filename.trim_end_matches(".kt"), prefix, filename],
            )
            .expect("identifier path");
        }
    }
    conn.execute(
        "INSERT INTO identifier_paths(identifier, prefix_id, filename) VALUES ('FooNotes', 2, 'FooNotes.md')",
        [],
    )
    .expect("lexical-only identifier");
    conn.execute(
        "INSERT INTO identifier_paths(identifier, prefix_id, filename) VALUES ('Foo', 1, 'A.kt')",
        [],
    )
    .expect("lexical token in source file");
    for (fq_id, kind, visibility, prefix, filename, module, source_set) in [
        (1, "CLASS", "PUBLIC", 1, "A.kt", ":app", "main"),
        (2, "CLASS", "PUBLIC", 1, "B.kt", ":app", "main"),
        (3, "CLASS", "PUBLIC", 2, "Foo.kt", ":lib", "main"),
        (4, "FUNCTION", "INTERNAL", 2, "Bar.kt", ":lib", "main"),
        (5, "FUNCTION", "PRIVATE", 1, "Unused.kt", ":app", "main"),
        (6, "CLASS", "PUBLIC", 2, "FooWidget.kt", ":lib", "main"),
        (
            7,
            "CLASS",
            "PUBLIC",
            2,
            "CardPaymentProcessor.kt",
            ":lib",
            "main",
        ),
        (
            8,
            "CLASS",
            "PUBLIC",
            3,
            "CardPaymentProcessorTest.kt",
            ":lib",
            "test",
        ),
        (
            9,
            "CLASS",
            "PUBLIC",
            4,
            "BuildPaymentProcessor.kt",
            ":build-logic",
            "main",
        ),
        (
            10,
            "CLASS",
            "PUBLIC",
            5,
            "PaymentBridge.kt",
            ":lib:payments",
            "main",
        ),
        (
            11,
            "CLASS",
            "PUBLIC",
            6,
            "FakeAnalysisBackend.kt",
            ":analysis-api",
            "testFixtures",
        ),
    ] {
        conn.execute(
            "INSERT INTO declarations(fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
            params![fq_id, kind, visibility, prefix, filename, module, source_set],
        )
        .expect("declaration");
    }
    for (
        src_prefix,
        src_filename,
        offset,
        source_fq_id,
        target_fq_id,
        tgt_prefix,
        tgt_filename,
        edge_kind,
    ) in [
        (1, "A.kt", 10, 1, 3, 2, "Foo.kt", "CALL"),
        (1, "A.kt", 20, 1, 3, 2, "Foo.kt", "CALL"),
        (1, "A.kt", 30, 1, 4, 2, "Bar.kt", "TYPE_REF"),
        (1, "B.kt", 10, 2, 3, 2, "Foo.kt", "CALL"),
        (1, "B.kt", 20, 2, 1, 1, "A.kt", "CALL"),
    ] {
        conn.execute(
            "INSERT INTO symbol_references(src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id, tgt_prefix_id, tgt_filename, target_offset, edge_kind) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)",
            params![src_prefix, src_filename, offset, source_fq_id, target_fq_id, tgt_prefix, tgt_filename, edge_kind],
        )
        .expect("reference");
    }
}

fn source_index_schema_version() -> i64 {
    env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        .parse()
        .expect("numeric source_index_schema_version")
}

fn seed_source_files(workspace: &std::path::Path) {
    std::fs::create_dir_all(workspace.join("app")).expect("app sources");
    std::fs::create_dir_all(workspace.join("lib")).expect("lib sources");
    std::fs::create_dir_all(workspace.join("lib/test")).expect("lib test sources");
    std::fs::create_dir_all(workspace.join("build-logic")).expect("build logic sources");
    std::fs::create_dir_all(workspace.join("lib/payments")).expect("lib payments sources");
    std::fs::create_dir_all(
        workspace.join("analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing"),
    )
    .expect("analysis-api test fixtures sources");
    std::fs::write(
        workspace.join("app/A.kt"),
        r#"package app

import lib.Bar
import lib.Foo

class A {
    fun render() {
        Foo()
        Bar()
    }
}
"#,
    )
    .expect("A.kt");
    std::fs::write(
        workspace.join("app/B.kt"),
        r#"package app

class B {
    fun touch(a: A) {
        a.render()
    }
}
"#,
    )
    .expect("B.kt");
    std::fs::write(
        workspace.join("app/Unused.kt"),
        r#"package app

private fun Unused() = Unit
"#,
    )
    .expect("Unused.kt");
    std::fs::write(
        workspace.join("lib/Foo.kt"),
        r#"package lib

class Foo
"#,
    )
    .expect("Foo.kt");
    std::fs::write(
        workspace.join("lib/Bar.kt"),
        r#"package lib

internal fun Bar() = Unit
"#,
    )
    .expect("Bar.kt");
    std::fs::write(
        workspace.join("lib/FooWidget.kt"),
        r#"package lib

class FooWidget
"#,
    )
    .expect("FooWidget.kt");
    std::fs::write(workspace.join("lib/FooNotes.md"), "# FooNotes\n").expect("FooNotes.md");
    std::fs::write(
        workspace.join("lib/CardPaymentProcessor.kt"),
        r#"package lib

class CardPaymentProcessor
"#,
    )
    .expect("CardPaymentProcessor.kt");
    std::fs::write(
        workspace.join("lib/test/CardPaymentProcessorTest.kt"),
        r#"package lib

class CardPaymentProcessorTest
"#,
    )
    .expect("CardPaymentProcessorTest.kt");
    std::fs::write(
        workspace.join("build-logic/BuildPaymentProcessor.kt"),
        r#"package buildlogic

class BuildPaymentProcessor
"#,
    )
    .expect("BuildPaymentProcessor.kt");
    std::fs::write(
        workspace.join("lib/payments/PaymentBridge.kt"),
        r#"package lib.payments

class PaymentBridge
"#,
    )
    .expect("PaymentBridge.kt");
    std::fs::write(
        workspace.join("analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt"),
        r#"package io.github.amichne.kast.testing

class FakeAnalysisBackend
"#,
    )
    .expect("FakeAnalysisBackend.kt");
}
