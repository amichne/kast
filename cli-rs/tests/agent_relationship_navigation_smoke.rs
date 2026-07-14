mod support;

use support::kast;

fn exact_selector() -> [&'static str; 6] {
    [
        "--symbol",
        "sample.Service.run",
        "--declaration-file",
        "src/main/kotlin/sample/Service.kt",
        "--declaration-start-offset",
        "42",
    ]
}

fn help_lists_command(stdout: &str, command: &str) -> bool {
    stdout
        .lines()
        .any(|line| line.trim_start().starts_with(command))
}

#[test]
fn standalone_relationship_commands_are_public() {
    let temp = tempfile::tempdir().expect("tempdir");
    let output = kast(&temp.path().join("home"), &temp.path().join("config"))
        .args(["agent", "--help"])
        .output()
        .expect("agent help");

    assert!(output.status.success());
    let stdout = String::from_utf8_lossy(&output.stdout);
    for command in [
        "references",
        "callers",
        "callees",
        "implementations",
        "hierarchy",
    ] {
        assert!(
            help_lists_command(&stdout, command),
            "agent help should show {command}: {stdout}",
        );
    }
}

#[test]
fn one_shot_symbol_relationship_flags_are_retired() {
    for retired_flag in [
        "--references",
        "--reference-page-token",
        "--callers",
        "--caller-depth",
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(["agent", "symbol", "--query", "Service", retired_flag])
            .output()
            .expect("retired symbol flag");

        assert_eq!(
            output.status.code(),
            Some(2),
            "flag={retired_flag} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}

#[test]
fn relationship_commands_accept_exact_identity_selectors() {
    for (command, command_args) in [
        ("references", Vec::new()),
        ("callers", vec!["--depth", "2"]),
        ("callees", vec!["--depth", "2"]),
        ("implementations", Vec::new()),
        ("hierarchy", vec!["--direction", "both", "--depth", "2"]),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut invocation = vec!["--output", "json", "agent", command];
        invocation.extend(exact_selector());
        invocation.extend(command_args);
        invocation.extend(["--limit", "17", "--fields", "subject,page"]);

        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(invocation)
            .output()
            .expect("typed relationship command");

        assert_eq!(
            output.status.code(),
            Some(1),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("relationship error json");
        assert_eq!(
            stdout["error"]["code"], "RELATIONSHIP_ENDPOINT_UNAVAILABLE",
            "command={command} output={stdout}",
        );
    }
}

#[test]
fn relationship_types_reject_invalid_values_before_runtime_io() {
    for (command, extra_args) in [
        ("references", vec!["--limit", "0"]),
        ("references", vec!["--limit", "201"]),
        ("references", vec!["--page-token", "not-a-token"]),
        ("callers", vec!["--depth", "0"]),
        ("callees", vec!["--depth", "9"]),
        ("hierarchy", vec!["--direction", "sideways"]),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut invocation = vec!["agent", command];
        invocation.extend(exact_selector());
        invocation.extend(extra_args);

        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(invocation)
            .output()
            .expect("invalid relationship command");

        assert_eq!(
            output.status.code(),
            Some(2),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}
