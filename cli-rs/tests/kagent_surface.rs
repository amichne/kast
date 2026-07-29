use std::os::unix::process::CommandExt;
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
    assert!(output.stderr.is_empty(), "{output:?}");
}
