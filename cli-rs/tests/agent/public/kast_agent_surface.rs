#[path = "../../support/mod.rs"]
mod support;

use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::Command;

use rusqlite::params;
use sha2::{Digest, Sha256};
use support::workspace_database_path_for_test;
use support::workspace_files::WorkspaceIndexFixture;
#[cfg(target_os = "macos")]
use support::{
    default_bin_dir, default_libexec_dir, write_current_cli_install_manifest_for_test,
    write_macos_plugin_workspace_metadata_for_cli as write_workspace_metadata,
};

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
fn public_pageable_commands_use_one_page_flag() {
    for args in [
        &["files", "--help"][..],
        &["symbol", "refs", "--help"][..],
        &["symbol", "callers", "--help"][..],
        &["symbol", "callees", "--help"][..],
        &["symbol", "implementations", "--help"][..],
        &["symbol", "supertypes", "--help"][..],
        &["symbol", "subtypes", "--help"][..],
        &["graph", "nodes", "--help"][..],
        &["graph", "impact", "--help"][..],
    ] {
        let output = named("kast")
            .args(args)
            .output()
            .unwrap_or_else(|error| panic!("run `kast {}`: {error}", args.join(" ")));
        assert!(output.status.success(), "{output:?}");
        let help = String::from_utf8(output.stdout).expect("UTF-8 help");
        assert!(
            help.contains("--page <PAGE>"),
            "`kast {}` omitted the uniform page input:\n{help}",
            args.join(" ")
        );
        for private in ["--page-token", "--after-id", "--generation"] {
            assert!(
                !help.contains(private),
                "`kast {}` leaked {private}:\n{help}",
                args.join(" ")
            );
        }
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

#[cfg(target_os = "macos")]
fn installed_public_home(unrelated_legacy_plugin_authority: bool) -> String {
    let fixture = tempfile::tempdir().expect("temporary install");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    write_current_cli_install_manifest_for_test(&home, &config_home);
    let public_binary = default_bin_dir(&home).join("kast");
    let control_binary = if unrelated_legacy_plugin_authority {
        let binary = fixture.path().join("unrelated/kastctl");
        std::fs::create_dir_all(binary.parent().expect("unrelated parent"))
            .expect("unrelated directory");
        std::fs::copy(&public_binary, &binary).expect("unrelated control binary");
        binary
    } else {
        default_libexec_dir(&home).join("kastctl")
    };
    write_workspace_metadata(&workspace, &control_binary, env!("CARGO_PKG_VERSION"));

    let output = Command::new(public_binary)
        .arg0("kast")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_HOME", home.join(".local/share/kast"))
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("run installed public kast");

    assert!(output.status.success(), "{output:?}");
    String::from_utf8(output.stdout).expect("UTF-8 home output")
}

#[cfg(target_os = "macos")]
#[test]
fn installed_public_entrypoint_uses_private_kastctl_libexec() {
    assert!(!installed_public_home(false).contains("does not match the running Kast executable"));
}

#[cfg(target_os = "macos")]
#[test]
fn installed_public_entrypoint_ignores_retired_plugin_control_binary_metadata() {
    assert!(!installed_public_home(true).contains("does not match the running Kast executable"));
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

    let _index = seed_public_graph(&workspace, false);

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
    assert_eq!(decoded["generation"], 41);
    assert_eq!(decoded["nodeCount"], 2);
    assert_eq!(decoded["edgeOccurrenceCount"], 2);
    assert_eq!(decoded["qualification"], "CURRENT");
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
fn public_graph_nodes_exposes_and_consumes_an_opaque_next_page() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = seed_public_graph(&workspace, false);
    let connection = index.connection();
    for id in 3_i64..=501 {
        connection
            .execute(
                "INSERT INTO semantic_symbols(id, stable_key, kind, name, file_id)
                 VALUES (?, ?, 'CLASS', ?, 1)",
                params![
                    id,
                    format!("class:sample.Node{id:03}"),
                    format!("Node{id:03}")
                ],
            )
            .expect("graph symbol");
    }
    drop(connection);

    let first = named("kast")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", fixture.path().join("config"))
        .args(["graph", "nodes"])
        .output()
        .expect("first graph page");
    assert!(
        first.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr)
    );
    let first: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&first.stdout)
            .expect("UTF-8 first graph page")
            .trim(),
    )
    .expect("first graph page TOON");
    assert_eq!(first["nodes"].as_array().map(Vec::len), Some(500));
    assert_eq!(first["truncated"], true);
    let next_page = first["nextPage"]
        .as_str()
        .expect("opaque public graph continuation")
        .to_string();
    assert!(next_page.starts_with("kgn1."), "{first:#}");
    for private in ["pageToken", "nextPageToken", "afterId", "nextAfterId"] {
        assert!(first.get(private).is_none(), "leaked {private}: {first:#}");
    }

    let second = named("kast")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", fixture.path().join("config"))
        .args(["graph", "nodes", "--page", &next_page])
        .output()
        .expect("second graph page");
    assert!(
        second.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr)
    );
    let second: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&second.stdout)
            .expect("UTF-8 second graph page")
            .trim(),
    )
    .expect("second graph page TOON");
    assert_eq!(second["nodes"].as_array().map(Vec::len), Some(1));
    assert_eq!(second["nodes"][0]["id"], 501);
    assert_eq!(second["truncated"], false);
    assert!(second.get("nextPage").is_none(), "{second:#}");

    let other_workspace = fixture.path().join("other-workspace");
    std::fs::create_dir_all(&other_workspace).expect("other workspace");
    std::fs::write(other_workspace.join("settings.gradle.kts"), "").expect("other Gradle marker");
    let other_workspace = other_workspace
        .canonicalize()
        .expect("canonical other workspace");
    let _other_index = seed_public_graph(&other_workspace, false);
    let wrong_root = named("kast")
        .current_dir(&other_workspace)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", fixture.path().join("config"))
        .args(["graph", "nodes", "--page", &next_page])
        .output()
        .expect("cross-workspace graph page");
    assert_eq!(wrong_root.status.code(), Some(1), "{wrong_root:?}");
    let wrong_root: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&wrong_root.stdout)
            .expect("UTF-8 cross-workspace page")
            .trim(),
    )
    .expect("cross-workspace page error TOON");
    assert_eq!(wrong_root["error"], "GRAPH_PAGE_TOKEN_MISMATCH");
}

#[test]
fn graph_summary_qualifies_stale_noncritical_persisted_facts() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_public_graph(&workspace, true);

    let output = named("kast")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", fixture.path().join("config"))
        .args(["graph", "summary"])
        .output()
        .expect("run stale graph summary");

    assert!(output.status.success(), "{output:?}");
    let decoded: serde_json::Value =
        toon_format::decode_default(std::str::from_utf8(&output.stdout).expect("UTF-8").trim())
            .expect("qualified stale graph summary is valid TOON");
    assert_eq!(decoded["qualification"], "QUALIFIED", "{decoded:#}");
    assert_eq!(decoded["coverage"]["stale"], 1, "{decoded:#}");
}

#[test]
fn graph_summary_ignores_current_reference_external_boundaries() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = seed_public_graph(&workspace, false);
    let failure_id = uuid::Uuid::new_v4().hyphenated().to_string();
    index
        .connection()
        .execute_batch(&format!(
            "UPDATE file_stage_outcomes
             SET outcome_status = 'EXTERNAL_BOUNDARY',
                 limitations_json = '[]',
                 failure_id = '{failure_id}',
                 failure_code = 'PSI_UNAVAILABLE',
                 failure_message = 'PSI is unavailable'
             WHERE stage = 'RELATIONSHIPS' AND filename = 'Source0000.kt';"
        ))
        .expect("external reference boundary");

    let output = named("kast")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", fixture.path().join("config"))
        .args(["graph", "summary"])
        .output()
        .expect("run graph summary with an external reference boundary");

    assert!(output.status.success(), "{output:?}");
    let decoded: serde_json::Value =
        toon_format::decode_default(std::str::from_utf8(&output.stdout).expect("UTF-8").trim())
            .expect("current graph summary is valid TOON");
    assert_eq!(decoded["qualification"], "CURRENT", "{decoded:#}");
    assert_eq!(decoded["coverage"]["limited"], 0, "{decoded:#}");
    assert_eq!(decoded["coverage"]["pending"], 0, "{decoded:#}");
    assert_eq!(decoded["nodeCount"], 2, "{decoded:#}");
}

include!("surface/graph_fixture_and_dispatch.rs");
