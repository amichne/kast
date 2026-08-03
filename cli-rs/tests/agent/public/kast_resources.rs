use std::ffi::OsString;
use std::fs;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};

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
if [[ "${KAST_TEST_FAIL_CLAUDE_INSTALL:-}" == "1" && "$provider" == "claude" && "$*" == *"plugin install"* ]]; then
  exit 71
fi
"#,
    )
    .expect("write provider test double");
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))
        .expect("make provider test double executable");
}

fn installed_plugin_root(kast_home: &Path, harness: &str) -> PathBuf {
    let digest_roots = fs::read_dir(kast_home.join("state/agent-resources"))
        .expect("read materialized resource state")
        .map(|entry| entry.expect("read materialized digest entry").path())
        .collect::<Vec<_>>();
    assert_eq!(digest_roots.len(), 1, "one materialized resource digest");
    digest_roots[0].join(harness).join("plugins/kast")
}

fn hook_command(plugin_root: &Path, hooks_path: &str, pointer: &str) -> String {
    let hooks: serde_json::Value = serde_json::from_str(
        &fs::read_to_string(plugin_root.join(hooks_path)).expect("read harness hooks"),
    )
    .expect("parse harness hooks");
    hooks
        .pointer(pointer)
        .and_then(serde_json::Value::as_str)
        .expect("harness hook command")
        .to_string()
}

fn assert_object_keys(value: &serde_json::Value, pointer: &str, expected: &[&str]) {
    let mut actual = value
        .pointer(pointer)
        .and_then(serde_json::Value::as_object)
        .unwrap_or_else(|| panic!("missing hook object at {pointer}"))
        .keys()
        .map(String::as_str)
        .collect::<Vec<_>>();
    actual.sort_unstable();
    assert_eq!(actual, expected, "invalid hook fields at {pointer}");
}

fn assert_provider_hook_schema(plugin_root: &Path, harness: HarnessCase) {
    let hooks: serde_json::Value = serde_json::from_str(
        &fs::read_to_string(plugin_root.join(harness.hooks_path)).expect("read harness hooks"),
    )
    .expect("parse harness hooks");
    match harness.name {
        "codex" => {
            assert_object_keys(&hooks, "", &["hooks"]);
            assert_object_keys(&hooks, "/hooks/SessionStart/0", &["hooks", "matcher"]);
            assert_object_keys(
                &hooks,
                "/hooks/SessionStart/0/hooks/0",
                &["command", "commandWindows", "timeout", "type"],
            );
            let command_windows = hooks
                .pointer("/hooks/SessionStart/0/hooks/0/commandWindows")
                .and_then(serde_json::Value::as_str)
                .expect("Codex Windows command");
            assert!(
                command_windows.contains("$env:KAST_AGENT_PROVIDER='codex'")
                    && command_windows.contains("$env:KAST_AGENT_RESOURCE_ROOT=$env:PLUGIN_ROOT")
                    && command_windows.contains("developer agent-hook"),
                "Codex Windows hook must use commandWindows and PLUGIN_ROOT: {command_windows}"
            );
        }
        "claude" => {
            assert_object_keys(&hooks, "", &["hooks"]);
            assert_object_keys(&hooks, "/hooks/SessionStart/0", &["hooks", "matcher"]);
            assert_object_keys(
                &hooks,
                "/hooks/SessionStart/0/hooks/0",
                &["command", "timeout", "type"],
            );
        }
        "copilot" => {
            assert_object_keys(&hooks, "", &["hooks", "version"]);
            for pointer in ["/hooks/sessionStart/0", "/hooks/preToolUse/0"] {
                assert_object_keys(
                    &hooks,
                    pointer,
                    &["bash", "powershell", "timeoutSec", "type"],
                );
                let powershell = hooks
                    .pointer(&format!("{pointer}/powershell"))
                    .and_then(serde_json::Value::as_str)
                    .expect("Copilot PowerShell command");
                assert!(
                    powershell.contains("$env:KAST_AGENT_PROVIDER='copilot'")
                        && powershell.contains("$env:KAST_AGENT_RESOURCE_ROOT='${PLUGIN_ROOT}'")
                        && powershell.contains("developer agent-hook"),
                    "Copilot PowerShell hook must use the provider schema and plugin root: {powershell}"
                );
            }
        }
        provider => panic!("unknown harness {provider}"),
    }
}

fn install_control_binary(kast_home: &Path) {
    let control = kast_home.join("current/libexec/kastctl");
    fs::create_dir_all(control.parent().expect("control binary parent"))
        .expect("create control binary parent");
    fs::copy(env!("CARGO_BIN_EXE_kast"), &control).expect("install control test binary");
    fs::set_permissions(&control, fs::Permissions::from_mode(0o755))
        .expect("make control test binary executable");
}

fn run_hook(
    command: &str,
    fixture: &Path,
    kast_home: &Path,
    plugin_root: &Path,
    input: serde_json::Value,
) -> Output {
    let mut child = Command::new("/bin/bash")
        .args(["-c", command])
        .current_dir(fixture)
        .env("HOME", fixture.join("home"))
        .env("KAST_HOME", kast_home)
        .env("PLUGIN_ROOT", plugin_root)
        .env("CLAUDE_PLUGIN_ROOT", plugin_root)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("start materialized Codex SessionStart hook");
    child
        .stdin
        .take()
        .expect("hook stdin")
        .write_all(input.to_string().as_bytes())
        .expect("write hook payload");
    child.wait_with_output().expect("finish harness hook")
}

fn session_start_input(fixture: &Path) -> serde_json::Value {
    serde_json::json!({
        "cwd": fixture,
        "hook_event_name": "SessionStart",
        "source": "startup"
    })
}

fn assert_activation_blocked(output: &Output, harness: &str, mismatch: &str) {
    assert!(
        output.status.success(),
        "the harness consumes the typed activation response from an exit-zero hook: {output:?}"
    );
    let response: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("parse hook response");
    let message = if harness == "copilot" {
        response
            .get("additionalContext")
            .and_then(serde_json::Value::as_str)
            .expect("Copilot activation mismatch context")
    } else {
        assert_eq!(response.get("continue"), Some(&serde_json::json!(false)));
        response
            .get("systemMessage")
            .and_then(serde_json::Value::as_str)
            .expect("activation mismatch systemMessage")
    };
    for expected in [
        mismatch,
        "detectedVersion",
        "expectedVersion",
        "detectedDigest",
        "expectedDigest",
        &format!("kast __internal resources install --harness {harness}"),
    ] {
        assert!(
            message.contains(expected),
            "missing {expected:?} in activation rejection: {message}"
        );
    }
}

fn assert_copilot_tool_blocked(output: &Output, mismatch: &str) {
    assert!(
        output.status.success(),
        "Copilot consumes a typed deny response: {output:?}"
    );
    let response: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("parse Copilot pre-tool response");
    assert_eq!(
        response.get("permissionDecision"),
        Some(&serde_json::json!("deny"))
    );
    let reason = response
        .get("permissionDecisionReason")
        .and_then(serde_json::Value::as_str)
        .expect("Copilot deny reason");
    let expected = if mismatch.contains("mismatch") {
        vec![mismatch, "expectedVersion", "expectedDigest"]
    } else {
        vec![mismatch]
    };
    for expected in expected {
        assert!(
            reason.contains(expected),
            "missing {expected:?} in Copilot denial: {reason}"
        );
    }
}

fn run_control_hook(
    fixture: &Path,
    kast_home: &Path,
    event: &str,
    provider: Option<&str>,
    plugin_root: Option<&Path>,
) -> Output {
    let mut command = Command::new(kast_home.join("current/libexec/kastctl"));
    command
        .args(["developer", "agent-hook", event])
        .current_dir(fixture)
        .env("HOME", fixture.join("home"))
        .env("KAST_HOME", kast_home)
        .env_remove("KAST_AGENT_PROVIDER")
        .env_remove("KAST_AGENT_RESOURCE_ROOT")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if let Some(provider) = provider {
        command.env("KAST_AGENT_PROVIDER", provider);
    }
    if let Some(plugin_root) = plugin_root {
        command.env("KAST_AGENT_RESOURCE_ROOT", plugin_root);
    }
    let mut child = command.spawn().expect("start direct control hook");
    child
        .stdin
        .take()
        .expect("control hook stdin")
        .write_all(session_start_input(fixture).to_string().as_bytes())
        .expect("write control hook payload");
    child
        .wait_with_output()
        .expect("finish direct control hook")
}

fn assert_identity_rejected(output: &Output, harness: Option<&str>, message: &str) {
    assert!(
        output.status.success(),
        "hook identity rejection: {output:?}"
    );
    let response: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("parse identity rejection");
    let rendered = if harness == Some("copilot") {
        response
            .get("additionalContext")
            .and_then(serde_json::Value::as_str)
            .expect("Copilot identity context")
    } else {
        assert_eq!(response.get("continue"), Some(&serde_json::json!(false)));
        response
            .get("systemMessage")
            .and_then(serde_json::Value::as_str)
            .expect("blocking identity message")
    };
    assert!(
        rendered.contains(message),
        "missing {message:?}: {rendered}"
    );
}

#[derive(Clone, Copy)]
struct HarnessCase {
    name: &'static str,
    plugin_path: &'static str,
    hooks_path: &'static str,
    session_pointer: &'static str,
    root_token: &'static str,
}

const HARNESSES: [HarnessCase; 3] = [
    HarnessCase {
        name: "codex",
        plugin_path: ".codex-plugin/plugin.json",
        hooks_path: "hooks/hooks.json",
        session_pointer: "/hooks/SessionStart/0/hooks/0/command",
        root_token: "${PLUGIN_ROOT}",
    },
    HarnessCase {
        name: "claude",
        plugin_path: ".claude-plugin/plugin.json",
        hooks_path: "hooks/hooks.json",
        session_pointer: "/hooks/SessionStart/0/hooks/0/command",
        root_token: "${CLAUDE_PLUGIN_ROOT}",
    },
    HarnessCase {
        name: "copilot",
        plugin_path: "plugin.json",
        hooks_path: "hooks.json",
        session_pointer: "/hooks/sessionStart/0/bash",
        root_token: "${PLUGIN_ROOT}",
    },
];

#[path = "kast_resources/activation.rs"]
mod activation_tests;
#[path = "kast_resources/install.rs"]
mod install_tests;
