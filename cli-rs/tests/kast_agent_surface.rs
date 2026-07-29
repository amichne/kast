#[path = "support/mod.rs"]
mod support;

use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::Command;

use support::workspace_database_path_for_test;

fn named(name: &str) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command.arg0(name);
    command
}

#[test]
fn help_exposes_only_the_agent_contract() {
    let output = named("kast").arg("--help").output().expect("run kast help");
    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8(output.stdout).expect("utf-8 help");

    assert!(stdout.contains("Usage: kast [COMMAND]"), "{stdout}");
    for command in [
        "up", "refresh", "files", "symbol", "graph", "check", "change", "apply",
    ] {
        assert!(stdout.contains(command), "missing {command}: {stdout}");
    }
    for legacy in ["setup", "developer", "rpc", "--output", "schemaVersion"] {
        assert!(!stdout.contains(legacy), "leaked {legacy}: {stdout}");
    }

    let graph = named("kast")
        .args(["graph", "--help"])
        .output()
        .expect("run graph help");
    assert!(graph.status.success(), "{graph:?}");
    let graph = String::from_utf8(graph.stdout).expect("UTF-8 graph help");
    for removed in ["path", "cycles", "bridges"] {
        assert!(!graph.contains(removed), "leaked {removed}: {graph}");
    }
}

#[test]
fn removed_output_flag_is_a_usage_error() {
    let output = named("kast")
        .args(["--output", "json"])
        .output()
        .expect("run invalid kast flag");

    assert_eq!(output.status.code(), Some(2), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("error:"), "{stdout}");
    assert!(stdout.contains("--output"), "{stdout}");
    assert!(stdout.contains("next:"), "{stdout}");
    assert!(output.stderr.is_empty(), "{output:?}");
}

#[test]
fn home_reports_live_workspace_state_without_protocol_cruft() {
    let state = tempfile::tempdir().expect("temporary state");
    let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repository root");
    let output = named("kast")
        .current_dir(workspace)
        .env("KAST_HOME", state.path().join("kast"))
        .env("XDG_CONFIG_HOME", state.path().join("config"))
        .output()
        .expect("run kast home");

    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("root:"), "{stdout}");
    assert!(stdout.contains("ready:"), "{stdout}");
    assert!(stdout.contains("referenceIndexReady:"), "{stdout}");
    assert!(stdout.contains("next["), "{stdout}");
    for cruft in ["state: UNKNOWN", "schemaVersion", "ok:", "method:"] {
        assert!(!stdout.contains(cruft), "leaked {cruft}: {stdout}");
    }
}

#[test]
fn graph_summary_is_a_direct_deterministic_toon_result_without_protocol_cruft() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"kast-graph-summary\"\n",
    )
    .expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");

    let database = workspace_database_path_for_test(&workspace);
    std::fs::create_dir_all(database.parent().expect("database parent"))
        .expect("database directory");
    let connection = rusqlite::Connection::open(database).expect("graph database");
    connection
        .execute_batch(&format!(
            r#"
            CREATE TABLE schema_version(version INTEGER NOT NULL, generation INTEGER NOT NULL);
            INSERT INTO schema_version VALUES ({}, 7);
            CREATE TABLE semantic_symbols(id INTEGER PRIMARY KEY, stable_key TEXT NOT NULL UNIQUE);
            INSERT INTO semantic_symbols VALUES
                (1, 'class:sample.Source'),
                (2, 'class:sample.Target');
            CREATE TABLE semantic_edge_occurrences(source_id INTEGER NOT NULL, target_id INTEGER NOT NULL);
            INSERT INTO semantic_edge_occurrences VALUES (1, 2), (1, 2);
            "#,
            env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        ))
        .expect("graph fixture");
    drop(connection);

    let output = named("kast")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", fixture.path().join("config"))
        .args(["graph", "summary"])
        .output()
        .expect("run kast graph summary");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(output.stderr.is_empty(), "{output:?}");
    let rendered = String::from_utf8(output.stdout).expect("UTF-8 graph summary");
    assert!(
        !rendered.trim_start().starts_with('{'),
        "graph summary must default to TOON: {rendered}"
    );
    let decoded: serde_json::Value =
        toon_format::decode_default(rendered.trim()).expect("graph summary is valid TOON");
    assert_eq!(decoded["generation"], 7);
    assert_eq!(decoded["nodeCount"], 2);
    assert_eq!(decoded["edgeOccurrenceCount"], 2);
    assert!(
        decoded.get("result").is_none(),
        "result must not be envelope-wrapped: {decoded:#}"
    );
    for cruft in ["ok", "method", "schemaVersion"] {
        assert!(
            decoded.get(cruft).is_none(),
            "graph summary leaked {cruft}: {decoded:#}"
        );
    }
}

#[test]
fn public_read_commands_delegate_to_typed_operations() {
    let fixture = tempfile::tempdir().expect("temporary workspace");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    for args in [
        &["up"][..],
        &["files"][..],
        &["files", "src/**/*.kt"][..],
        &["symbol", "find", "Widget"][..],
        &["symbol", "show", "sample.Widget"][..],
        &["symbol", "refs", "sample.Widget"][..],
        &["symbol", "callers", "sample.Widget.run"][..],
        &["symbol", "callees", "sample.Widget.run"][..],
        &["symbol", "implementations", "sample.Widget"][..],
        &["symbol", "supertypes", "sample.Widget"][..],
        &["symbol", "subtypes", "sample.Widget"][..],
        &["graph", "nodes"][..],
        &["graph", "neighbors", "class:sample.Widget"][..],
        &["graph", "topology"][..],
        &["graph", "communities"][..],
        &["graph", "impact", "sample.Widget"][..],
        &["check", "src/main/kotlin/App.kt"][..],
    ] {
        let output = named("kast")
            .current_dir(&workspace)
            .env("HOME", fixture.path().join("home"))
            .env("KAST_HOME", fixture.path().join("kast"))
            .env("KAST_CONFIG_HOME", fixture.path().join("config"))
            .args(args)
            .output()
            .unwrap_or_else(|error| panic!("run `kast {}`: {error}", args.join(" ")));
        let stdout = String::from_utf8(output.stdout).expect("UTF-8 agent output");

        assert!(
            !stdout.contains("KAST_AGENT_NOT_IMPLEMENTED"),
            "`kast {}` did not delegate:\n{stdout}",
            args.join(" ")
        );
        for cruft in ["schemaVersion", "method:", "ok:"] {
            assert!(
                !stdout.contains(cruft),
                "`kast {}` leaked {cruft}:\n{stdout}",
                args.join(" ")
            );
        }
    }
}

#[test]
fn internal_control_surface_preserves_the_existing_cli() {
    let output = named("_kastctl")
        .arg("--help")
        .output()
        .expect("run _kastctl help");
    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);

    assert!(
        stdout.contains("Usage: _kastctl [OPTIONS] [COMMAND]"),
        "{stdout}"
    );
    for command in ["setup", "developer", "rpc", "agent"] {
        assert!(stdout.contains(command), "missing {command}: {stdout}");
    }
}

#[test]
fn retired_kagent_entrypoint_is_rejected() {
    let output = named("kagent")
        .arg("--help")
        .output()
        .expect("run retired kagent entrypoint");

    assert_eq!(output.status.code(), Some(2), "{output:?}");
    assert!(output.stderr.is_empty(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("error:"), "{stdout}");
    assert!(stdout.contains("kagent"), "{stdout}");
    assert!(stdout.contains("kast --help"), "{stdout}");
    assert!(!stdout.contains("Usage:"), "{stdout}");
}
