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
    let metrics_response = run_lsp_custom_request(
        &home,
        &config_home,
        &workspace,
        "kast/databaseMetrics",
        41,
        serde_json::json!({
            "metric": "fanIn",
            "limit": 1
        }),
    );
    assert!(
        metrics_response.error.is_none(),
        "database/metrics LSP response should succeed: stderr={}\nresponse={:#}",
        metrics_response.stderr,
        metrics_response.value
    );
    let metrics_rpc_json = serde_json::json!({
        "jsonrpc": "2.0",
        "id": 41,
        "result": metrics_response.value["result"].clone()
    });
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

    let unsupported_response = run_lsp_custom_request(
        &home,
        &config_home,
        &workspace,
        "kast/databaseMetrics",
        43,
        serde_json::json!({
            "metric": "apiSurface"
        }),
    );
    assert!(
        unsupported_response.error.is_none(),
        "unsupported metrics are reported as a typed RPC result, not an LSP transport failure: {:#}",
        unsupported_response.value
    );
    assert_eq!(
        unsupported_response.value["result"]["type"],
        Value::String("METRICS_FAILURE".to_string())
    );
    assert_eq!(
        unsupported_response.value["result"]["stage"],
        Value::String("validate".to_string())
    );

    let symbol_rpc_json = run_symbol_query(
        &home,
        &config_home,
        &workspace,
        42,
        serde_json::json!({
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
        }),
    );
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
