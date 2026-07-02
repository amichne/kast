use super::kast;
use rusqlite::{Connection, params};
use serde_json::Value;

pub(crate) fn symbol_query_response_schema() -> Value {
    serde_json::from_str(include_str!(
        "../../../analysis-api/src/main/resources/contracts/symbol-query/symbol-query-response.schema.json"
    ))
    .expect("symbol/query response schema")
}

pub(crate) fn assert_symbol_query_response_matches_schema(response: &Value) {
    let schema = symbol_query_response_schema();
    let validator = jsonschema::validator_for(&schema).expect("schema compiles");
    if let Err(error) = validator.validate(response) {
        panic!("symbol/query response does not match schema: {error}\nresponse: {response}");
    }
}

pub(crate) fn run_symbol_query(
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
    let (success, envelope, stderr) =
        run_agent_call(home, config_home, workspace, "symbol/query", request);
    assert!(success, "stderr: {stderr}\nenvelope: {envelope:#}");
    envelope["response"].clone()
}

pub(crate) fn run_agent_call(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    method: &str,
    input: Value,
) -> (bool, Value, String) {
    let body = input.to_string();
    let output = kast(home, config_home)
        .args([
            "--output",
            "json",
            "agent",
            "call",
            method,
            "--params",
            body.as_str(),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .unwrap_or_else(|error| panic!("agent call {method}: {error}"));
    let success = output.status.success();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    let envelope: Value = serde_json::from_slice(&output.stdout)
        .unwrap_or_else(|error| panic!("agent call {method} json: {error}\nstderr: {stderr}"));
    (success, envelope, stderr)
}

pub(crate) fn result_fq_names(response: &Value) -> Vec<String> {
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

pub(crate) fn assert_hard_filter_fields<const N: usize>(response: &Value, expected: [&str; N]) {
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

pub(crate) fn assert_structural_constraint_fields<const N: usize>(
    response: &Value,
    expected: [&str; N],
) {
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

pub(crate) fn assert_declaration_facets<const N: usize>(response: &Value, expected: [&str; N]) {
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

pub(crate) fn seed_source_index(workspace: &std::path::Path) {
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

pub(crate) fn source_index_schema_version() -> i64 {
    env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        .parse()
        .expect("numeric source_index_schema_version")
}

pub(crate) fn seed_source_files(workspace: &std::path::Path) {
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
