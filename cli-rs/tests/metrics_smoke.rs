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
    assert!(fan_in_stdout.contains("\"targetFqName\": \"lib.Foo\""));
    assert!(fan_in_stdout.contains("\"occurrenceCount\": 3"));

    let search = kast(&home, &config_home)
        .args([
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

    let graph = kast(&home, &config_home)
        .args([
            "metrics",
            "graph",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--json",
            "lib.Foo",
        ])
        .output()
        .expect("metrics graph");
    assert!(
        graph.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&graph.stderr)
    );
    let graph_stdout = String::from_utf8_lossy(&graph.stdout);
    assert!(graph_stdout.contains("\"focalNodeId\": \"symbol:lib.Foo\""));
    assert!(graph_stdout.contains("\"edgeType\": \"REFERENCED_BY\""));

    let demo = kast(&home, &config_home)
        .args([
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--json",
            "--symbol",
            "app.A",
        ])
        .output()
        .expect("demo snapshot");
    assert!(
        demo.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&demo.stderr)
    );
    let demo_stdout = String::from_utf8_lossy(&demo.stdout);
    assert!(demo_stdout.contains("\"mode\": \"symbolWalk\""));
    assert!(demo_stdout.contains("\"fqName\": \"app.A\""));
    assert!(demo_stdout.contains("\"fqName\": \"app.B\""));
    assert!(demo_stdout.contains("\"fqName\": \"lib.Foo\""));
    assert!(demo_stdout.contains("\"title\": \"Declaration: A\""));
    assert!(demo_stdout.contains("\"focusedLine\""));

    let demo_short_search = kast(&home, &config_home)
        .args([
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--json",
            "--query",
            "Fo",
            "--limit",
            "5",
        ])
        .output()
        .expect("demo short search snapshot");
    assert!(
        demo_short_search.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&demo_short_search.stderr)
    );
    let demo_short_search_json: Value =
        serde_json::from_slice(&demo_short_search.stdout).expect("demo short search json");
    assert!(
        demo_short_search_json["snapshot"]["searchResults"]
            .as_array()
            .expect("search results")
            .iter()
            .any(|hit| hit["fqName"] == Value::String("lib.FooWidget".to_string())),
        "demo short search should include FooWidget: {demo_short_search_json}"
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

    let spatial = kast(&home, &config_home)
        .args([
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--view",
            "spatial",
            "--json",
            "--symbol",
            "app.A",
        ])
        .output()
        .expect("spatial demo snapshot");
    assert!(
        spatial.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&spatial.stderr)
    );
    let spatial_json: Value = serde_json::from_slice(&spatial.stdout).expect("spatial json");
    assert_eq!(
        spatial_json["snapshot"]["mode"],
        Value::String("spatialAst".to_string())
    );
    assert_eq!(
        spatial_json["snapshot"]["selection"]["nodeId"],
        Value::String("symbol:app.A".to_string())
    );
    assert_eq!(
        spatial_json["snapshot"]["tree"]["rootId"],
        Value::String("workspace".to_string())
    );
    let identities: std::collections::BTreeSet<_> = spatial_json["snapshot"]["tree"]["nodes"]
        .as_array()
        .expect("tree nodes")
        .iter()
        .filter_map(|node| node["identity"].as_str())
        .collect();
    for identity in [
        "compilerSymbol",
        "sourceIndexDeclaration",
        "fileOutlineNode",
        "structuralOnly",
        "syntheticAggregate",
    ] {
        assert!(
            identities.contains(identity),
            "spatial snapshot should include {identity}: {spatial_json}"
        );
    }
    assert!(
        spatial_json["snapshot"]["visibleNodes"]
            .as_array()
            .expect("visible nodes")
            .iter()
            .any(
                |node| node["nodeId"] == Value::String("symbol:app.A".to_string())
                    && node["selected"] == Value::Bool(true)
            ),
        "selected symbol should be projected into the visible node list: {spatial_json}"
    );
    assert!(
        spatial_json["snapshot"]["incoming"]
            .as_array()
            .expect("incoming relations")
            .iter()
            .any(|relation| relation["fqName"] == Value::String("app.B".to_string())),
        "spatial snapshot should carry callers/references in: {spatial_json}"
    );
    assert!(
        spatial_json["snapshot"]["outgoing"]
            .as_array()
            .expect("outgoing relations")
            .iter()
            .any(|relation| relation["fqName"] == Value::String("lib.Foo".to_string())),
        "spatial snapshot should carry callees/references out: {spatial_json}"
    );
    assert!(
        spatial_json["snapshot"]["preview"]["title"]
            .as_str()
            .expect("preview title")
            .contains("A")
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

    conn.execute("INSERT INTO path_prefixes VALUES (1, 'app')", [])
        .expect("app prefix");
    conn.execute("INSERT INTO path_prefixes VALUES (2, 'lib')", [])
        .expect("lib prefix");
    for (id, name) in [
        (1, "app.A"),
        (2, "app.B"),
        (3, "lib.Foo"),
        (4, "lib.Bar"),
        (5, "app.Unused"),
        (6, "lib.FooWidget"),
    ] {
        conn.execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
            params![id, name],
        )
        .expect("fq name");
    }
    for (prefix, filename, module) in [
        (1, "A.kt", ":app"),
        (1, "B.kt", ":app"),
        (1, "Unused.kt", ":app"),
        (2, "Foo.kt", ":lib"),
        (2, "Bar.kt", ":lib"),
        (2, "FooWidget.kt", ":lib"),
    ] {
        conn.execute(
            "INSERT INTO file_metadata(prefix_id, filename, module_path, source_set) VALUES (?, ?, ?, 'main')",
            params![prefix, filename, module],
        )
        .expect("file metadata");
        conn.execute(
            "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (?, ?, 1)",
            params![prefix, filename],
        )
        .expect("file manifest");
        conn.execute(
            "INSERT INTO identifier_paths(identifier, prefix_id, filename) VALUES (?, ?, ?)",
            params![filename.trim_end_matches(".kt"), prefix, filename],
        )
        .expect("identifier path");
    }
    for (fq_id, kind, visibility, prefix, filename, module) in [
        (1, "CLASS", "PUBLIC", 1, "A.kt", ":app"),
        (2, "CLASS", "PUBLIC", 1, "B.kt", ":app"),
        (3, "CLASS", "PUBLIC", 2, "Foo.kt", ":lib"),
        (4, "FUNCTION", "INTERNAL", 2, "Bar.kt", ":lib"),
        (5, "FUNCTION", "PRIVATE", 1, "Unused.kt", ":app"),
        (6, "CLASS", "PUBLIC", 2, "FooWidget.kt", ":lib"),
    ] {
        conn.execute(
            "INSERT INTO declarations(fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set) VALUES (?, ?, ?, ?, ?, 1, ?, 'main')",
            params![fq_id, kind, visibility, prefix, filename, module],
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
}
