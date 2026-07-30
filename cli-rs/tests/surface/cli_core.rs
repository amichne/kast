#[path = "../support/mod.rs"]
mod support;

use support::*;

fn help_lists_command(stdout: &str, command: &str) -> bool {
    stdout
        .lines()
        .any(|line| line.split_whitespace().next() == Some(command))
}

#[test]
fn control_context_suggests_only_control_entrypoint() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("help");
    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    for command in ["start", "status", "stop"] {
        assert!(
            help_lists_command(&stdout, command),
            "missing {command}: {stdout}"
        );
    }

    let context = kast(&home, &config_home)
        .args([
            "context",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("context");
    assert!(context.status.success());
    let stdout = String::from_utf8_lossy(&context.stdout);
    for command in [
        "kastctl start --workspace-root <repo>",
        "kastctl status --workspace-root <repo>",
        "kastctl stop --workspace-root <repo>",
        "kastctl config list --workspace-root <repo>",
        "kastctl agent verify --workspace-root <repo>",
        "kastctl agent symbol --query <name> --workspace-root <repo>",
        "kastctl --help",
        "kastctl setup --source <bundle>",
    ] {
        assert!(stdout.contains(command), "missing {command}: {stdout}");
    }
    for invalid in [
        "kast start ",
        "kast status ",
        "kast stop ",
        "kast config ",
        "kast agent ",
        "kast setup ",
    ] {
        assert!(
            !stdout.contains(invalid),
            "public entrypoint suggested control grammar `{invalid}`: {stdout}"
        );
    }
}

#[test]
fn public_cli_exposes_setup_and_no_retired_install_mutators() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("help");
    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    for command in [
        "help",
        "version",
        "setup",
        "ready",
        "status",
        "rpc",
        "developer",
        "agent",
    ] {
        assert!(
            help_lists_command(&stdout, command),
            "missing {command}: {stdout}"
        );
    }
    for retired in ["repair", "machine", "install"] {
        assert!(
            !help_lists_command(&stdout, retired),
            "retired {retired}: {stdout}"
        );
    }

    let setup = kast(&home, &config_home)
        .args(["setup", "--help"])
        .output()
        .expect("setup help");
    assert!(setup.status.success());
    let setup_stdout = String::from_utf8_lossy(&setup.stdout);
    assert!(setup_stdout.contains("--source"), "{setup_stdout}");
    for retired in ["--workspace-root", "--dry-run", "--target-dir", "--backend"] {
        assert!(
            !setup_stdout.contains(retired),
            "retired {retired}: {setup_stdout}"
        );
    }

    for retired in [
        ["repair", "--help"].as_slice(),
        ["machine", "--help"].as_slice(),
        ["developer", "machine", "--help"].as_slice(),
        ["developer", "release", "activate", "--help"].as_slice(),
        ["agent", "setup", "--help"].as_slice(),
    ] {
        let output = kast(&home, &config_home)
            .args(retired)
            .output()
            .expect("retired command");
        assert!(
            !output.status.success(),
            "retired command remained callable: {retired:?}"
        );
    }
}

#[test]
fn agent_surface_keeps_semantic_commands() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .args(["agent", "--help"])
        .output()
        .expect("agent help");
    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    for command in ["verify", "symbol", "diagnostics", "impact", "rename"] {
        assert!(
            help_lists_command(&stdout, command),
            "missing {command}: {stdout}"
        );
    }
}

#[test]
fn operational_help_exposes_canonical_runtime_and_graph_recipes() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let runtime = kast(&home, &config_home)
        .args(["developer", "runtime", "up", "--help"])
        .output()
        .expect("runtime up help");
    assert!(runtime.status.success());
    let runtime_stdout = String::from_utf8_lossy(&runtime.stdout);
    for expected in [
        "--accept-indexing",
        "kast developer runtime up --workspace-root \"$PWD\" --backend idea --accept-indexing",
    ] {
        assert!(
            runtime_stdout.contains(expected),
            "missing {expected}: {runtime_stdout}"
        );
    }

    let graph = kast(&home, &config_home)
        .args(["agent", "graph", "--help"])
        .output()
        .expect("agent graph help");
    assert!(graph.status.success());
    let graph_stdout = String::from_utf8_lossy(&graph.stdout);
    for expected in [
        "kast agent graph --workspace-root \"$PWD\" --operation summary",
        "kast agent graph --workspace-root \"$PWD\" --operation refresh --file-path src/main/kotlin/App.kt",
    ] {
        assert!(
            graph_stdout.contains(expected),
            "missing {expected}: {graph_stdout}"
        );
    }
}
