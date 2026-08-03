use super::*;

#[test]
fn direct_public_cli_does_not_require_agent_resources() {
    let fixture = tempfile::tempdir().expect("temporary direct CLI fixture");
    let output = kast()
        .args(["change", "--help"])
        .env("HOME", fixture.path().join("home"))
        .env("KAST_HOME", fixture.path().join("kast"))
        .output()
        .expect("run public change help without agent resources");

    assert!(output.status.success(), "direct public CLI: {output:?}");
    assert!(!fixture.path().join("kast/state/agent-resources").exists());
}

#[test]
fn provider_install_attempts_every_harness_before_reporting_failure() {
    let fixture = tempfile::tempdir().expect("temporary provider fixture");
    let bin = fixture.path().join("bin");
    fs::create_dir(&bin).expect("create provider bin directory");
    for provider in ["codex", "claude", "copilot"] {
        write_provider(&bin.join(provider));
    }

    let provider_log = fixture.path().join("providers.log");
    let mut path_entries = vec![bin];
    path_entries.extend(std::env::split_paths(
        &std::env::var_os("PATH").unwrap_or_else(|| OsString::from("/usr/bin:/bin")),
    ));
    let path = std::env::join_paths(path_entries).expect("provider PATH");

    let output = kast()
        .args([
            "__internal",
            "resources",
            "install",
            "--harness",
            "codex",
            "--harness",
            "claude",
            "--harness",
            "copilot",
        ])
        .env("PATH", path)
        .env("HOME", fixture.path().join("home"))
        .env("KAST_HOME", fixture.path().join("kast"))
        .env("KAST_PROVIDER_LOG", &provider_log)
        .env("KAST_TEST_FAIL_CLAUDE_INSTALL", "1")
        .output()
        .expect("run embedded provider installer");

    assert_eq!(
        output.status.code(),
        Some(1),
        "one provider failure must produce an aggregate operational failure: {output:?}"
    );
    let log = fs::read_to_string(&provider_log).expect("provider invocation log");
    for invocation in [
        "codex plugin add",
        "claude plugin install",
        "copilot plugin install",
    ] {
        assert!(
            log.lines().any(|line| line.contains(invocation)),
            "missing {invocation} after provider failure:\n{log}"
        );
    }
}
