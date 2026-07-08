mod support;

use serde_json::Value;
use support::metrics::*;
use support::*;

#[test]
fn reads_metrics_directly_from_source_index_db() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    seed_source_index(&workspace);
    write_macos_plugin_workspace_metadata(&workspace);

    let fan_in = kast(&home, &config_home)
        .args([
            "--output",
            "human",
            "developer",
            "inspect",
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
            "developer",
            "inspect",
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
            "developer",
            "inspect",
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
            "developer",
            "inspect",
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
        .args(["developer", "inspect", "metrics", "--help"])
        .output()
        .expect("metrics help");
    assert!(
        metrics_help.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&metrics_help.stderr)
    );
    let demo = kast(&home, &config_home)
        .args([
            "developer",
            "inspect",
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
            "developer",
            "inspect",
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
        .args(["developer", "inspect", "demo", "--help"])
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
    let (metrics_success, metrics_envelope, metrics_stderr) = run_agent_call(
        &home,
        &config_home,
        &workspace,
        "database/metrics",
        metrics_rpc_request,
    );
    assert!(
        metrics_success,
        "stderr: {metrics_stderr}\nenvelope: {metrics_envelope:#}"
    );
    let metrics_rpc_json = metrics_envelope["response"].clone();
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
    let (unsupported_success, unsupported_envelope, unsupported_stderr) = run_agent_call(
        &home,
        &config_home,
        &workspace,
        "database/metrics",
        unsupported_metrics_rpc_request,
    );
    assert!(
        !unsupported_success,
        "unsupported metric should fail the agent envelope: {unsupported_envelope:#}"
    );
    assert!(
        unsupported_stderr.is_empty(),
        "agent envelope errors should be printed to stdout, not stderr: {unsupported_stderr}"
    );
    assert_eq!(
        unsupported_envelope["error"]["code"],
        Value::String("AGENT_REQUEST_INVALID".to_string())
    );
    assert_eq!(
        unsupported_envelope["error"]["details"]["validation"]["errors"][0]["path"],
        Value::String("/params/metric".to_string())
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
    let (symbol_success, symbol_envelope, symbol_stderr) = run_agent_call(
        &home,
        &config_home,
        &workspace,
        "symbol/query",
        symbol_rpc_request,
    );
    assert!(
        symbol_success,
        "stderr: {symbol_stderr}\nenvelope: {symbol_envelope:#}"
    );
    let symbol_rpc_json = symbol_envelope["response"].clone();
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
