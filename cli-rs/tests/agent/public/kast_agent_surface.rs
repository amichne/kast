#[path = "kast_agent_surface/developer_route.rs"]
mod developer_route;
#[path = "surface/graph_summary_protocol.rs"]
mod graph_summary_protocol;
#[path = "../../support/mod.rs"]
mod support;
#[path = "surface/typed_exact_operations.rs"]
mod typed_exact_operations;
#[path = "surface/typed_graph_protocol.rs"]
mod typed_graph_protocol;
#[path = "surface/typed_mutation_operations.rs"]
mod typed_mutation_operations;
#[path = "surface/typed_output_protocol.rs"]
mod typed_output_protocol;
#[path = "surface/typed_pagination.rs"]
mod typed_pagination;
#[path = "surface/typed_selector_rejections.rs"]
mod typed_selector_rejections;

use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::Command;

use rusqlite::params;
use sha2::{Digest, Sha256};
#[cfg(target_os = "macos")]
use support::default_bin_dir;
use support::workspace_files::WorkspaceIndexFixture;
use support::{
    published_semantic_command_for_reads, workspace_database_path_for_test,
    write_current_cli_install_manifest_for_test,
};

fn named(name: &str) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command.arg0(name);
    command
}

fn published_public_kast(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
) -> support::PublishedSemanticCommand {
    let mut command = named("kast");
    command
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    published_semantic_command_for_reads(command, home, config_home, workspace, 1)
}

#[test]
fn help_exposes_only_the_agent_contract() {
    let output = named("kast").arg("--help").output().expect("run kast help");
    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8(output.stdout).expect("utf-8 help");

    assert!(
        stdout.contains("Usage: kast [OPTIONS] [COMMAND]"),
        "{stdout}"
    );
    for command in [
        "workspace",
        "file",
        "symbol",
        "relation",
        "graph",
        "diagnostic",
        "change",
    ] {
        assert!(stdout.contains(command), "missing {command}: {stdout}");
    }
    for private_command in ["\n  setup ", "\n  developer ", "\n  rpc "] {
        assert!(
            !stdout.contains(private_command),
            "leaked private command {private_command}: {stdout}"
        );
    }
    assert!(stdout.contains("--output <OUTPUT>"), "{stdout}");
    assert!(!stdout.contains("schemaVersion"), "{stdout}");
    assert!(stdout.contains("developerOperations"), "{stdout}");
    assert!(stdout.contains("/kast:developer"), "{stdout}");

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
fn public_pageable_commands_use_one_continuation_flag() {
    for args in [
        &["file", "list", "--help"][..],
        &["relation", "references", "--help"][..],
        &["relation", "calls", "incoming", "--help"][..],
        &["relation", "calls", "outgoing", "--help"][..],
        &["relation", "implementations", "--help"][..],
        &["relation", "hierarchy", "supertypes", "--help"][..],
        &["relation", "hierarchy", "subtypes", "--help"][..],
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
            help.contains("--continuation <CONTINUATION>"),
            "`kast {}` omitted the uniform continuation input:\n{help}",
            args.join(" ")
        );
        for private in ["--page", "--page-token", "--after-id", "--generation"] {
            assert!(
                !help.contains(private),
                "`kast {}` leaked {private}:\n{help}",
                args.join(" ")
            );
        }
    }
}

#[test]
fn public_output_flag_selects_json() {
    let output = named("kast")
        .args(["--output", "json"])
        .output()
        .expect("run kast with JSON output");

    assert!(output.status.success(), "{output:?}");
    let value: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("public JSON output");
    assert_eq!(value["schemaVersion"], 2, "{value:#}");
    assert_eq!(value["operation"], "workspace.home", "{value:#}");
    assert_eq!(value["status"], "complete", "{value:#}");
    assert_eq!(value["result"]["type"], "home", "{value:#}");
    assert_eq!(value["result"]["bin"], "kast", "{value:#}");
    assert!(output.stderr.is_empty(), "{output:?}");
}

#[cfg(target_os = "macos")]
fn installed_public_home() -> String {
    let fixture = tempfile::tempdir().expect("temporary install");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    write_current_cli_install_manifest_for_test(&home, &config_home);
    let public_binary = default_bin_dir(&home).join("kast");

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
    assert!(!installed_public_home().contains("does not match the running Kast executable"));
}

#[test]
fn public_graph_nodes_issue_distinct_node_selectors_and_opaque_continuations() {
    let fixture = tempfile::tempdir().expect("temporary graph fixture");
    let home = fixture.path().join("home");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_paged_public_graph(&workspace);

    let first = published_public_kast(&home, &fixture.path().join("config"), &workspace)
        .current_dir(&workspace)
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
    assert_eq!(first["schemaVersion"], 2, "{first:#}");
    assert_eq!(first["operation"], "graph.nodes", "{first:#}");
    assert_eq!(first["status"], "complete", "{first:#}");
    assert_eq!(first["result"]["type"], "graph-nodes", "{first:#}");
    assert_eq!(first["result"]["nodes"].as_array().map(Vec::len), Some(500));
    assert_eq!(first["result"]["page"]["returned"], 500);
    assert_eq!(
        first["result"]["page"]["cardinality"]["type"],
        "known-minimum"
    );
    let node_selector = first["result"]["nodes"][0]["nodeSelector"]
        .as_str()
        .expect("opaque graph node selector")
        .to_string();
    assert!(node_selector.starts_with("kgns1."), "{first:#}");
    let continuation = first["result"]["page"]["continuation"]
        .as_str()
        .expect("opaque public graph continuation")
        .to_string();
    assert!(continuation.starts_with("kgn2."), "{first:#}");
    for private in [
        "truncated",
        "nextPage",
        "pageToken",
        "nextPageToken",
        "afterId",
        "nextAfterId",
    ] {
        assert!(
            first["result"].get(private).is_none(),
            "leaked {private}: {first:#}"
        );
    }

    let neighbors = published_public_kast(&home, &fixture.path().join("config"), &workspace)
        .current_dir(&workspace)
        .args(["graph", "neighbors", "--node-selector", &node_selector])
        .output()
        .expect("graph node selector consumer");
    assert!(
        neighbors.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&neighbors.stdout),
        String::from_utf8_lossy(&neighbors.stderr),
    );
    let neighbors: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&neighbors.stdout)
            .expect("UTF-8 neighbors")
            .trim(),
    )
    .expect("neighbors TOON");
    assert_eq!(
        neighbors["result"]["key"],
        first["result"]["nodes"][0]["stableKey"]
    );

    let second = published_public_kast(&home, &fixture.path().join("config"), &workspace)
        .current_dir(&workspace)
        .args(["graph", "nodes", "--continuation", &continuation])
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
    assert_eq!(second["result"]["nodes"].as_array().map(Vec::len), Some(1));
    assert_eq!(second["result"]["nodes"][0]["id"], 501);
    assert!(
        second["result"]["nodes"][0]["nodeSelector"]
            .as_str()
            .is_some_and(|selector| selector.starts_with("kgns1."))
    );
    assert_eq!(second["result"]["page"]["returned"], 1);
    assert_eq!(second["result"]["page"]["cardinality"]["type"], "exact");
    assert_eq!(second["result"]["page"]["cardinality"]["count"], 501);
    assert!(
        second["result"]["page"].get("continuation").is_none(),
        "{second:#}"
    );

    let other_workspace = fixture.path().join("other-workspace");
    std::fs::create_dir_all(&other_workspace).expect("other workspace");
    std::fs::write(other_workspace.join("settings.gradle.kts"), "").expect("other Gradle marker");
    let other_workspace = other_workspace
        .canonicalize()
        .expect("canonical other workspace");
    let _other_index = seed_public_graph(&other_workspace, false);
    let wrong_root = published_public_kast(&home, &fixture.path().join("config"), &other_workspace)
        .current_dir(&other_workspace)
        .args(["graph", "nodes", "--continuation", &continuation])
        .output()
        .expect("cross-workspace graph page");
    assert_eq!(wrong_root.status.code(), Some(1), "{wrong_root:?}");
    let wrong_root: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&wrong_root.stdout)
            .expect("UTF-8 cross-workspace page")
            .trim(),
    )
    .expect("cross-workspace page error TOON");
    assert_eq!(wrong_root["status"], "rejected", "{wrong_root:#}");
    assert_eq!(wrong_root["result"]["type"], "rejected", "{wrong_root:#}");
    assert_eq!(
        wrong_root["result"]["failure"]["code"], "GRAPH_PAGE_TOKEN_MISMATCH",
        "{wrong_root:#}"
    );

    let wrong_domain = published_public_kast(&home, &fixture.path().join("config"), &workspace)
        .current_dir(&workspace)
        .args([
            "graph",
            "neighbors",
            "--node-selector",
            "ksh1.symbol-selector-cannot-substitute",
        ])
        .output()
        .expect("wrong selector domain");
    assert_eq!(wrong_domain.status.code(), Some(1), "{wrong_domain:?}");
    let wrong_domain: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&wrong_domain.stdout)
            .expect("UTF-8 wrong-domain failure")
            .trim(),
    )
    .expect("wrong-domain failure TOON");
    assert_eq!(wrong_domain["status"], "rejected", "{wrong_domain:#}");
    assert_eq!(
        wrong_domain["result"]["failure"]["code"], "GRAPH_NODE_SELECTOR_MALFORMED",
        "{wrong_domain:#}"
    );

    let wrong_root_node =
        published_public_kast(&home, &fixture.path().join("config"), &other_workspace)
            .current_dir(&other_workspace)
            .args(["graph", "neighbors", "--node-selector", &node_selector])
            .output()
            .expect("cross-workspace node selector");
    assert_eq!(
        wrong_root_node.status.code(),
        Some(1),
        "{wrong_root_node:?}"
    );
    let wrong_root_node: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&wrong_root_node.stdout)
            .expect("UTF-8 wrong-root selector failure")
            .trim(),
    )
    .expect("wrong-root selector failure TOON");
    assert_eq!(wrong_root_node["status"], "rejected", "{wrong_root_node:#}");
    assert_eq!(
        wrong_root_node["result"]["failure"]["code"], "GRAPH_NODE_SELECTOR_WRONG_WORKSPACE",
        "{wrong_root_node:#}"
    );
}

include!("surface/graph_fixture_and_dispatch.rs");
include!("surface/typed_protocol.rs");
