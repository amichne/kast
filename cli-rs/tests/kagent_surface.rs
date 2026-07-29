use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::Command;

fn kagent() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command.arg0("kagent");
    command
}

#[test]
fn help_exposes_only_the_agent_contract() {
    let output = kagent().arg("--help").output().expect("run kagent help");
    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8(output.stdout).expect("utf-8 help");

    assert!(stdout.contains("Usage: kagent [COMMAND]"), "{stdout}");
    for command in [
        "up", "refresh", "files", "symbol", "graph", "check", "change", "apply",
    ] {
        assert!(stdout.contains(command), "missing {command}: {stdout}");
    }
    for legacy in ["setup", "developer", "rpc", "--output", "schemaVersion"] {
        assert!(!stdout.contains(legacy), "leaked {legacy}: {stdout}");
    }
}

#[test]
fn removed_output_flag_is_a_usage_error() {
    let output = kagent()
        .args(["--output", "json"])
        .output()
        .expect("run invalid kagent flag");

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
    let output = kagent()
        .current_dir(workspace)
        .env("KAST_HOME", state.path().join("kast"))
        .env("XDG_CONFIG_HOME", state.path().join("config"))
        .output()
        .expect("run kagent home");

    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("root:"), "{stdout}");
    assert!(stdout.contains("ready:"), "{stdout}");
    assert!(stdout.contains("referenceIndexReady:"), "{stdout}");
    assert!(stdout.contains("next:"), "{stdout}");
    for cruft in ["state: UNKNOWN", "schemaVersion", "ok:", "method:"] {
        assert!(!stdout.contains(cruft), "leaked {cruft}: {stdout}");
    }
}

#[test]
fn regular_kast_surface_is_unchanged() {
    let output = Command::new(env!("CARGO_BIN_EXE_kast"))
        .arg("--help")
        .output()
        .expect("run kast help");
    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);

    assert!(stdout.contains("Usage: kast [OPTIONS] [COMMAND]"), "{stdout}");
    for command in ["setup", "developer", "rpc", "agent"] {
        assert!(stdout.contains(command), "missing {command}: {stdout}");
    }
}
