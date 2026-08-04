fn seed_public_graph(workspace: &Path, stale: bool) -> WorkspaceIndexFixture {
    let database = workspace_database_path_for_test(workspace);
    let index = WorkspaceIndexFixture::at_database_path(workspace, &database);
    index.seed_high_cardinality_sources(1);
    index.seed_progress("app", "COMPLETE", 1, 1);
    let source_path = "src/main/kotlin/sample/Source0000.kt";
    let content_hash = hex::encode(Sha256::digest(
        std::fs::read(workspace.join(source_path)).expect("graph source"),
    ));
    let connection = index.connection();
    connection
        .execute_batch(
            "CREATE TABLE semantic_files(
                 id INTEGER PRIMARY KEY,
                 path TEXT NOT NULL UNIQUE,
                 package_name TEXT,
                 module_name TEXT,
                 content_hash TEXT,
                 refresh_status TEXT NOT NULL,
                 diagnostics_json TEXT NOT NULL
             );
             CREATE TABLE semantic_symbols(
                 id INTEGER PRIMARY KEY,
                 stable_key TEXT NOT NULL UNIQUE,
                 kind TEXT NOT NULL,
                 name TEXT NOT NULL,
                 file_id INTEGER NOT NULL
             );
             CREATE TABLE semantic_edge_occurrences(
                 source_id INTEGER NOT NULL,
                 target_id INTEGER NOT NULL,
                 source_file_id INTEGER NOT NULL,
                 kind TEXT NOT NULL,
                 context TEXT NOT NULL
             );
             CREATE VIEW semantic_module_quotient AS
                 SELECT source_file.module_name AS source_container,
                        target_file.module_name AS target_container,
                        edges.kind, edges.context, COUNT(*) AS weight
                 FROM semantic_edge_occurrences edges
                 JOIN semantic_symbols source ON source.id = edges.source_id
                 JOIN semantic_symbols target ON target.id = edges.target_id
                 JOIN semantic_files source_file ON source_file.id = source.file_id
                 JOIN semantic_files target_file ON target_file.id = target.file_id
                 WHERE source_file.module_name IS NOT NULL
                   AND target_file.module_name IS NOT NULL
                 GROUP BY 1, 2, edges.kind, edges.context;
             INSERT INTO semantic_symbols VALUES
                 (1, 'class:sample.Source', 'CLASS', 'Source', 1),
                 (2, 'class:sample.Target', 'CLASS', 'Target', 1);
             INSERT INTO semantic_edge_occurrences VALUES
                 (1, 2, 1, 'REFERENCE', 'TYPE'),
                 (1, 2, 1, 'REFERENCE', 'TYPE');",
        )
        .expect("native graph schema");
    connection
        .execute(
            "INSERT INTO semantic_files VALUES
             (1, ?, 'sample', 'app.main', ?, 'REFRESHED', '[]')",
            params![source_path, content_hash],
        )
        .expect("semantic graph file");
    drop(connection);
    index.synchronize_semantic_graph_scope_fingerprints();
    if stale {
        index
            .connection()
            .execute(
                "UPDATE file_manifest SET content_hash = ? WHERE filename = 'Source0000.kt'",
                params!["e".repeat(64)],
            )
            .expect("stale manifest");
    }
    index
}

#[test]
fn public_graph_exposes_read_only_topology() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_public_graph(&workspace, false);

    for (operation, scope, expected) in [
        ("summary", "symbol", "SYMBOL"),
        ("topology", "package", "PACKAGE"),
        ("communities", "module", "MODULE"),
    ] {
        let output = published_public_kast(&home, &fixture.path().join("config"), &workspace)
            .current_dir(&workspace)
            .args(["graph", operation, "--scope", scope])
            .output()
            .unwrap_or_else(|error| panic!("run graph {operation} at {scope} scope: {error}"));
        assert!(
            output.status.success(),
            "stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        let decoded: serde_json::Value = toon_format::decode_default(
            std::str::from_utf8(&output.stdout)
                .expect("UTF-8 graph projection")
                .trim(),
        )
        .expect("graph projection is valid TOON");
        assert_eq!(decoded["scope"], expected, "{decoded:#}");
    }

    let help = named("kast")
        .args(["graph", "topology", "--help"])
        .output()
        .expect("run topology help");
    assert!(help.status.success(), "{help:?}");
    let help = String::from_utf8(help.stdout).expect("UTF-8 topology help");
    assert!(help.contains("--scope <SCOPE>"), "{help}");
    assert!(
        help.contains("possible values: symbol, package, module"),
        "{help}"
    );
    for unsafe_surface in ["file", "database", "sql"] {
        assert!(
            !help.to_ascii_lowercase().contains(unsafe_surface),
            "public graph help leaked {unsafe_surface}: {help}"
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
    let output = named("kastctl")
        .arg("--help")
        .output()
        .expect("run kastctl help");
    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);

    assert!(
        stdout.contains("Usage: kastctl [OPTIONS] [COMMAND]"),
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
