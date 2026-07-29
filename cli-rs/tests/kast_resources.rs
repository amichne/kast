use std::ffi::OsString;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::Command;

fn kast() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command.arg0("kast");
    command
}

fn resource_files(root: &Path) -> Vec<String> {
    fn visit(root: &Path, directory: &Path, files: &mut Vec<String>) {
        for entry in fs::read_dir(directory).expect("read embedded resource directory") {
            let entry = entry.expect("read embedded resource entry");
            let path = entry.path();
            if path.is_dir() {
                visit(root, &path, files);
            } else {
                files.push(
                    path.strip_prefix(root)
                        .expect("resource path under root")
                        .to_string_lossy()
                        .replace('\\', "/"),
                );
            }
        }
    }

    let mut files = Vec::new();
    visit(root, root, &mut files);
    files.sort();
    files
}

#[test]
fn embedded_provider_resource_sources_are_complete_and_local() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources")
        .join("kast");
    assert!(
        root.is_dir(),
        "missing embedded Kast resource root: {}",
        root.display()
    );

    let expected = [
        "SKILL.md",
        "claude/hooks.json",
        "claude/marketplace.json",
        "claude/plugin.json",
        "codex/hooks.json",
        "codex/marketplace.json",
        "codex/plugin.json",
        "copilot/hooks.json",
        "copilot/marketplace.json",
        "copilot/plugin.json",
    ];
    assert_eq!(resource_files(&root), expected);

    for path in expected {
        let contents = fs::read_to_string(root.join(path)).expect("read embedded resource");
        assert!(
            !contents.contains("amichne/kast-marketplace")
                && !contents.contains("kagent")
                && !contents.contains("--ref main"),
            "legacy remote marketplace leaked into {path}"
        );
        if path.ends_with(".json") {
            serde_json::from_str::<serde_json::Value>(&contents)
                .unwrap_or_else(|error| panic!("{path} is not valid JSON: {error}"));
        }
    }
}

fn write_provider(path: &Path) {
    fs::write(
        path,
        r#"#!/usr/bin/env bash
set -euo pipefail
provider="${0##*/}"
printf '%s %s\n' "$provider" "$*" >>"${KAST_PROVIDER_LOG:?}"
if [[ "$*" == *"--version"* ]]; then
  printf '%s\n' "${provider} 999.0.0"
elif [[ "$*" == *"--json"* ]]; then
  printf '%s\n' '[]'
fi
if [[ "$provider" == "claude" && "$*" == *"plugin install"* ]]; then
  exit 71
fi
"#,
    )
    .expect("write provider test double");
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))
        .expect("make provider test double executable");
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
