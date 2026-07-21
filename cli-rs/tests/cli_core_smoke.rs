mod support;

use support::*;

fn help_lists_command(stdout: &str, command: &str) -> bool {
    stdout
        .lines()
        .any(|line| line.split_whitespace().next() == Some(command))
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
fn agent_surface_keeps_semantic_commands_and_rejects_raw_call() {
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

    let call = kast(&home, &config_home)
        .args(["--output", "json", "agent", "call", "symbol/resolve"])
        .output()
        .expect("removed call");
    assert!(!call.status.success());
    let payload: serde_json::Value = serde_json::from_slice(&call.stdout).expect("call JSON");
    assert_eq!(payload["error"]["code"], "AGENT_COMMAND_REMOVED");
}
