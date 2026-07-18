mod support;

use std::os::unix::fs::PermissionsExt;
use std::process::Output;
use support::*;

#[test]
fn codex_generator_materializes_cli_only_plugin() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let output = temp.path().join("generated");
    std::fs::create_dir_all(&home).expect("home");

    let result = kast(&home, &config_home)
        .args([
            "developer",
            "codex",
            "generate",
            "--release",
            "--output-dir",
        ])
        .arg(&output)
        .output()
        .expect("generate Codex plugin");

    assert!(
        result.status.success(),
        "generation failed: {}",
        String::from_utf8_lossy(&result.stdout)
    );

    for relative in [
        "marketplace.json",
        ".agents/plugins/marketplace.json",
        "plugins/kast/.codex-plugin/plugin.json",
        "plugins/kast/hooks/hooks.json",
        "plugins/kast/scripts/kast-codex-hook",
        "plugins/kast/skills/kast-codex/SKILL.md",
        "plugins/kast/skills/kast-codex/agents/openai.yaml",
        "plugins/kast/skills/kast-codex/references/commands.md",
        "plugins/kast/skills/kast-codex/references/examples.md",
        "plugins/kast/assets/codex-exposure.toon",
        "plugins/kast/assets/hook-recovery-messages.toon",
        "plugins/kast/assets/kast.svg",
    ] {
        assert!(output.join(relative).is_file(), "missing {relative}");
    }

    for forbidden in [".mcp.json", ".app.json", "commands.json"] {
        assert!(
            !walk_contains_name(&output, forbidden),
            "generated plugin must not contain {forbidden}"
        );
    }

    let manifest: serde_json::Value = serde_json::from_slice(
        &std::fs::read(output.join("plugins/kast/.codex-plugin/plugin.json"))
            .expect("plugin manifest"),
    )
    .expect("valid plugin manifest");
    assert_eq!(manifest["name"], "kast");
    assert_eq!(manifest["version"], env!("CARGO_PKG_VERSION"));
    assert!(manifest.get("hooks").is_none());
    assert!(manifest.get("mcpServers").is_none());
    assert!(manifest.get("apps").is_none());
    assert_eq!(
        std::fs::read(output.join("marketplace.json")).expect("release marketplace"),
        std::fs::read(output.join(".agents/plugins/marketplace.json"))
            .expect("Codex discovery marketplace")
    );
}

#[test]
fn codex_generator_check_reports_drift() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let output = temp.path().join("generated");
    std::fs::create_dir_all(&home).expect("home");

    let generated = kast(&home, &config_home)
        .args(["developer", "codex", "generate", "--output-dir"])
        .arg(&output)
        .output()
        .expect("generate");
    assert!(generated.status.success());

    let clean = kast(&home, &config_home)
        .args(["developer", "codex", "generate", "--check", "--output-dir"])
        .arg(&output)
        .output()
        .expect("check");
    assert!(clean.status.success());

    std::fs::write(
        output.join("plugins/kast/skills/kast-codex/references/commands.md"),
        "stale\n",
    )
    .expect("introduce drift");
    let drift = kast(&home, &config_home)
        .args(["developer", "codex", "generate", "--check", "--output-dir"])
        .arg(&output)
        .output()
        .expect("drift check");
    assert!(!drift.status.success());
    assert!(String::from_utf8_lossy(&drift.stdout).contains("CODEX_GENERATED_ASSETS_DRIFT"));
}

#[test]
fn codex_generator_check_does_not_require_a_writable_temporary_directory() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let output = temp.path().join("generated");
    let invalid_temp_directory = temp.path().join("not-a-directory");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&invalid_temp_directory, "file").expect("invalid temp directory");

    let generated = kast(&home, &config_home)
        .args(["developer", "codex", "generate", "--output-dir"])
        .arg(&output)
        .output()
        .expect("generate");
    assert!(generated.status.success());

    let checked = kast(&home, &config_home)
        .env("TMPDIR", &invalid_temp_directory)
        .args(["developer", "codex", "generate", "--check", "--output-dir"])
        .arg(&output)
        .output()
        .expect("check without scratch storage");

    assert!(
        checked.status.success(),
        "check unexpectedly used TMPDIR: {}",
        String::from_utf8_lossy(&checked.stdout)
    );
}

#[test]
fn codex_hooks_guard_kotlin_mutations_and_current_diagnostics() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Sample.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source dir");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&source, "class Sample\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);

    let session = serde_json::json!({
        "session_id": "session-1",
        "cwd": workspace,
        "source": "startup",
        "hook_event_name": "SessionStart"
    });
    let started = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &session,
    );
    assert_hook_success(&started);
    let start_output: serde_json::Value =
        serde_json::from_slice(&started.stdout).expect("SessionStart JSON");
    assert_eq!(
        start_output["hookSpecificOutput"]["hookEventName"],
        "SessionStart"
    );
    assert!(plugin_data.join("sessions/session-1.json").is_file());
    assert_eq!(
        std::fs::metadata(plugin_data.join("sessions/session-1.json"))
            .expect("state metadata")
            .permissions()
            .mode()
            & 0o777,
        0o600
    );

    let generic_edit = serde_json::json!({
        "session_id": "session-1",
        "cwd": workspace,
        "tool_name": "apply_patch",
        "tool_input": {"patch": "*** Update File: src/Sample.kt\n"}
    });
    let denied = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "pre-tool-use",
        &generic_edit,
    );
    assert_hook_success(&denied);
    let denied_output: serde_json::Value =
        serde_json::from_slice(&denied.stdout).expect("PreToolUse JSON");
    assert_eq!(
        denied_output["hookSpecificOutput"]["permissionDecision"],
        "deny"
    );

    let typed_failure = serde_json::json!({
        "session_id": "session-1",
        "cwd": workspace,
        "tool_name": "Bash",
        "tool_input": {"command": "kast --output toon agent add-file --workspace-root . --file-path src/Sample.kt --content-file /tmp/content.kt"},
        "tool_response": "ok: false\ncode: UNSUPPORTED_OPERATION\n"
    });
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "post-tool-use",
        &typed_failure,
    ));
    let allowed = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "pre-tool-use",
        &generic_edit,
    );
    assert_hook_success(&allowed);
    let allowed_output: serde_json::Value =
        serde_json::from_slice(&allowed.stdout).expect("allowed JSON");
    assert!(allowed_output.get("hookSpecificOutput").is_none());

    std::fs::write(&source, "class Sample { fun changed() = Unit }\n").expect("change source");
    let stop = serde_json::json!({
        "session_id": "session-1",
        "cwd": workspace,
        "last_assistant_message": "Implemented the change."
    });
    let blocked = hook(&home, &config_home, &plugin_data, &workspace, "stop", &stop);
    assert_hook_success(&blocked);
    let blocked_output: serde_json::Value =
        serde_json::from_slice(&blocked.stdout).expect("Stop JSON");
    assert_eq!(blocked_output["decision"], "block");

    let blocker = serde_json::json!({
        "session_id": "session-1",
        "cwd": workspace,
        "last_assistant_message": "Blocked by typed Kast failure UNSUPPORTED_OPERATION for src/Sample.kt."
    });
    let blocker_reported = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "stop",
        &blocker,
    );
    assert_hook_success(&blocker_reported);
    assert_eq!(
        serde_json::from_slice::<serde_json::Value>(&blocker_reported.stdout)
            .expect("blocker Stop JSON"),
        serde_json::json!({})
    );

    let diagnostics = serde_json::json!({
        "session_id": "session-1",
        "cwd": workspace,
        "tool_name": "Bash",
        "tool_input": {"command": "kast --output toon agent diagnostics --workspace-root . --file-path src/Sample.kt"},
        "tool_response": "ok: true\nmethod: agent/diagnostics\nresult:\n  type: KAST_AGENT_DIAGNOSTICS_RESULT\n  ok: true\n  filePaths[1]: src/Sample.kt\n"
    });
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "post-tool-use",
        &diagnostics,
    ));
    let completed = hook(&home, &config_home, &plugin_data, &workspace, "stop", &stop);
    assert_hook_success(&completed);
    let completed_output: serde_json::Value =
        serde_json::from_slice(&completed.stdout).expect("completed Stop JSON");
    assert_eq!(completed_output, serde_json::json!({}));

    std::fs::write(&source, "class Sample { fun changedAgain() = Unit }\n")
        .expect("change after diagnostics");
    let stale = hook(&home, &config_home, &plugin_data, &workspace, "stop", &stop);
    assert_hook_success(&stale);
    let stale_output: serde_json::Value =
        serde_json::from_slice(&stale.stdout).expect("stale diagnostics Stop JSON");
    assert_eq!(stale_output["decision"], "block");

    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "post-tool-use",
        &diagnostics,
    ));
    let current = hook(&home, &config_home, &plugin_data, &workspace, "stop", &stop);
    assert_hook_success(&current);
    assert_eq!(
        serde_json::from_slice::<serde_json::Value>(&current.stdout)
            .expect("current diagnostics Stop JSON"),
        serde_json::json!({})
    );
}

#[test]
fn compact_recovery_preserves_preexisting_kotlin_baseline_and_worktree_context() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Preexisting.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source dir");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&source, "class Preexisting\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);
    std::fs::write(&source, "class Preexisting { val before = 1 }\n").expect("preexisting dirt");

    let start = serde_json::json!({
        "session_id": "compact-session",
        "cwd": workspace,
        "source": "startup"
    });
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &start,
    ));
    let stop = serde_json::json!({
        "session_id": "compact-session",
        "cwd": workspace,
        "last_assistant_message": "No new Kotlin changes."
    });
    let unchanged = hook(&home, &config_home, &plugin_data, &workspace, "stop", &stop);
    assert_hook_success(&unchanged);
    assert_eq!(
        serde_json::from_slice::<serde_json::Value>(&unchanged.stdout).expect("Stop JSON"),
        serde_json::json!({})
    );

    let subagent = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "subagent-start",
        &serde_json::json!({"session_id": "compact-session", "cwd": workspace}),
    );
    assert_hook_success(&subagent);
    assert!(String::from_utf8_lossy(&subagent.stdout).contains(&workspace.display().to_string()));

    std::fs::write(&source, "class Preexisting { val after = 2 }\n").expect("new dirt");
    let compact = serde_json::json!({
        "session_id": "compact-session",
        "cwd": workspace,
        "source": "compact"
    });
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &compact,
    ));
    let blocked = hook(&home, &config_home, &plugin_data, &workspace, "stop", &stop);
    assert_hook_success(&blocked);
    let blocked_json: serde_json::Value =
        serde_json::from_slice(&blocked.stdout).expect("blocked Stop JSON");
    assert_eq!(blocked_json["decision"], "block");
}

#[test]
fn hook_state_never_crosses_linked_worktree_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let linked = temp.path().join("linked");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("Sample.kt"), "class Sample\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);
    let worktree = std::process::Command::new("git")
        .args(["worktree", "add", "-q", "-b", "codex-linked-test"])
        .arg(&linked)
        .arg("HEAD")
        .current_dir(&workspace)
        .status()
        .expect("linked worktree");
    assert!(worktree.success());

    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &serde_json::json!({
            "session_id": "worktree-session",
            "cwd": workspace,
            "source": "startup"
        }),
    ));

    let crossed = hook(
        &home,
        &config_home,
        &plugin_data,
        &linked,
        "subagent-start",
        &serde_json::json!({"session_id": "worktree-session", "cwd": linked}),
    );
    assert!(!crossed.status.success());
    let payload: serde_json::Value =
        serde_json::from_slice(&crossed.stdout).expect("worktree mismatch envelope");
    assert_eq!(
        payload["systemMessage"]["code"],
        "CODEX_HOOK_WORKSPACE_MISMATCH"
    );
}

#[test]
fn exec_command_results_record_structured_typed_failures_for_scoped_fallback() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Sample.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source dir");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&source, "class Sample\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);

    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &serde_json::json!({
            "session_id": "exec-session",
            "cwd": workspace,
            "source": "startup"
        }),
    ));
    let generic_edit = serde_json::json!({
        "session_id": "exec-session",
        "cwd": workspace,
        "tool_name": "exec_command",
        "tool_input": {"cmd": "perl -pi -e 's/Sample/Changed/' src/Sample.kt"}
    });
    let denied = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "pre-tool-use",
        &generic_edit,
    );
    assert_hook_success(&denied);
    let denied_payload: serde_json::Value =
        serde_json::from_slice(&denied.stdout).expect("denied payload");
    assert_eq!(
        denied_payload["hookSpecificOutput"]["permissionDecision"],
        "deny"
    );

    let typed_failure = serde_json::json!({
        "session_id": "exec-session",
        "cwd": workspace,
        "tool_name": "exec_command",
        "tool_input": {"cmd": "kast --output toon agent add-file --workspace-root . --file-path src/Sample.kt --content-file /tmp/content.kt"},
        "tool_response": {
            "exit_code": 2,
            "output": "ok: false\ncode: UNSUPPORTED_OPERATION\naffectedFiles[1]: src/Sample.kt\n"
        }
    });
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "post-tool-use",
        &typed_failure,
    ));

    let allowed = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "pre-tool-use",
        &generic_edit,
    );
    assert_hook_success(&allowed);
    assert_eq!(
        serde_json::from_slice::<serde_json::Value>(&allowed.stdout).expect("allowed payload"),
        serde_json::json!({})
    );
    let state: serde_json::Value = serde_json::from_slice(
        &std::fs::read(plugin_data.join("sessions/exec-session.json")).expect("state"),
    )
    .expect("state JSON");
    assert_eq!(state["typedAttempts"][0]["outcome"], "FAILED");
    assert_eq!(state["typedAttempts"][0]["code"], "UNSUPPORTED_OPERATION");
}

#[test]
fn untyped_process_failures_do_not_unlock_generic_kotlin_fallbacks() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Sample.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source dir");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&source, "class Sample\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);

    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &serde_json::json!({
            "session_id": "untyped-failure-session",
            "cwd": workspace,
            "source": "startup"
        }),
    ));
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "post-tool-use",
        &serde_json::json!({
            "session_id": "untyped-failure-session",
            "cwd": workspace,
            "tool_name": "exec_command",
            "tool_input": {"cmd": "kast --output toon agent add-file --workspace-root . --file-path src/Sample.kt --content-file /tmp/content.kt"},
            "tool_response": {"exit_code": 127, "output": "kast: command not found"}
        }),
    ));

    let guarded = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "pre-tool-use",
        &serde_json::json!({
            "session_id": "untyped-failure-session",
            "cwd": workspace,
            "tool_name": "apply_patch",
            "tool_input": {"patch": "*** Update File: src/Sample.kt\n"}
        }),
    );
    assert_hook_success(&guarded);
    let payload: serde_json::Value =
        serde_json::from_slice(&guarded.stdout).expect("guard payload");
    assert_eq!(payload["hookSpecificOutput"]["permissionDecision"], "deny");
}

#[test]
fn diagnostics_evidence_requires_a_parsed_command_and_typed_result() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Sample.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source dir");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&source, "class Sample\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &serde_json::json!({
            "session_id": "diagnostics-proof-session",
            "cwd": workspace,
            "source": "startup"
        }),
    ));
    std::fs::write(&source, "class Sample { val changed = true }\n").expect("change source");

    for (command, output) in [
        (
            "echo kast agent diagnostics src/Sample.kt",
            "ok: true\nmethod: agent/diagnostics\nresult:\n  type: KAST_AGENT_DIAGNOSTICS_RESULT\n  ok: true\n  filePaths[1]: src/Sample.kt\n",
        ),
        (
            "kast --output toon agent diagnostics --workspace-root . --file-path src/Sample.kt",
            "ok: true\ncode: NOT_A_DIAGNOSTICS_ENVELOPE\n",
        ),
    ] {
        assert_hook_success(&hook(
            &home,
            &config_home,
            &plugin_data,
            &workspace,
            "post-tool-use",
            &serde_json::json!({
                "session_id": "diagnostics-proof-session",
                "cwd": workspace,
                "tool_name": "exec_command",
                "tool_input": {"cmd": command},
                "tool_response": {"exit_code": 0, "output": output}
            }),
        ));
    }

    let stopped = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "stop",
        &serde_json::json!({
            "session_id": "diagnostics-proof-session",
            "cwd": workspace,
            "last_assistant_message": "Done."
        }),
    );
    assert_hook_success(&stopped);
    let payload: serde_json::Value = serde_json::from_slice(&stopped.stdout).expect("Stop payload");
    assert_eq!(payload["decision"], "block");
}

#[test]
fn committed_kotlin_changes_still_require_current_diagnostics() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Sample.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source dir");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&source, "class Sample\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "session-start",
        &serde_json::json!({
            "session_id": "committed-session",
            "cwd": workspace,
            "source": "startup"
        }),
    ));

    std::fs::write(&source, "class Sample { val committed = true }\n").expect("change source");
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "change"]);
    let stop_input = serde_json::json!({
        "session_id": "committed-session",
        "cwd": workspace,
        "last_assistant_message": "Done."
    });
    let blocked = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "stop",
        &stop_input,
    );
    assert_hook_success(&blocked);
    let payload: serde_json::Value =
        serde_json::from_slice(&blocked.stdout).expect("blocked Stop payload");
    assert_eq!(payload["decision"], "block");

    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "post-tool-use",
        &serde_json::json!({
            "session_id": "committed-session",
            "cwd": workspace,
            "tool_name": "exec_command",
            "tool_input": {"cmd": "kast --output toon agent diagnostics --workspace-root . --file-path src/Sample.kt"},
            "tool_response": {
                "exit_code": 0,
                "output": "ok: true\nmethod: agent/diagnostics\nresult:\n  type: KAST_AGENT_DIAGNOSTICS_RESULT\n  ok: true\n  filePaths[1]: src/Sample.kt\n"
            }
        }),
    ));
    let completed = hook(
        &home,
        &config_home,
        &plugin_data,
        &workspace,
        "stop",
        &stop_input,
    );
    assert_hook_success(&completed);
    assert_eq!(
        serde_json::from_slice::<serde_json::Value>(&completed.stdout).expect("Stop payload"),
        serde_json::json!({})
    );
}

#[test]
fn hook_events_from_a_nested_cwd_share_repo_root_relative_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    let nested = workspace.join("module/src");
    let source = nested.join("Sample.kt");
    std::fs::create_dir_all(&nested).expect("nested source dir");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&source, "class Sample\n").expect("source");
    git(&workspace, ["init", "-q"]);
    git(&workspace, ["config", "user.email", "test@example.com"]);
    git(&workspace, ["config", "user.name", "Test"]);
    git(&workspace, ["add", "."]);
    git(&workspace, ["commit", "-qm", "base"]);
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &nested,
        "session-start",
        &serde_json::json!({
            "session_id": "nested-session",
            "cwd": nested,
            "source": "startup"
        }),
    ));
    std::fs::write(&source, "class Sample { val changed = true }\n").expect("change source");
    assert_hook_success(&hook(
        &home,
        &config_home,
        &plugin_data,
        &nested,
        "post-tool-use",
        &serde_json::json!({
            "session_id": "nested-session",
            "cwd": nested,
            "tool_name": "exec_command",
            "tool_input": {"cmd": "kast --output toon agent diagnostics --workspace-root ../.. --file-path module/src/Sample.kt"},
            "tool_response": {
                "exit_code": 0,
                "output": "ok: true\nmethod: agent/diagnostics\nresult:\n  type: KAST_AGENT_DIAGNOSTICS_RESULT\n  ok: true\n  filePaths[1]: module/src/Sample.kt\n"
            }
        }),
    ));
    let stopped = hook(
        &home,
        &config_home,
        &plugin_data,
        &nested,
        "stop",
        &serde_json::json!({
            "session_id": "nested-session",
            "cwd": nested,
            "last_assistant_message": "Done."
        }),
    );
    assert_hook_success(&stopped);
    assert_eq!(
        serde_json::from_slice::<serde_json::Value>(&stopped.stdout).expect("Stop payload"),
        serde_json::json!({})
    );
    let state: serde_json::Value = serde_json::from_slice(
        &std::fs::read(plugin_data.join("sessions/nested-session.json")).expect("state"),
    )
    .expect("state JSON");
    assert_eq!(
        state["workspaceRoot"],
        workspace
            .canonicalize()
            .expect("canonical workspace")
            .display()
            .to_string()
    );
}

#[test]
fn malformed_hook_input_returns_only_the_codex_json_error_envelope() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let plugin_data = temp.path().join("plugin-data");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let mut child = kast(&home, &config_home)
        .args(["developer", "codex", "hook", "session-start"])
        .env("PLUGIN_DATA", &plugin_data)
        .current_dir(&workspace)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .expect("spawn malformed hook");
    use std::io::Write as _;
    child
        .stdin
        .as_mut()
        .expect("stdin")
        .write_all(b"{")
        .expect("malformed input");
    let output = child.wait_with_output().expect("malformed output");
    assert!(!output.status.success());
    let payload: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("JSON error envelope");
    assert_eq!(payload["continue"], false);
    assert_eq!(payload["systemMessage"]["code"], "CODEX_HOOK_INPUT_INVALID");
}

#[test]
fn launcher_resolves_absolute_override_and_forwards_event_and_stdin() {
    let temp = tempfile::tempdir().expect("tempdir");
    let fake_kast = temp.path().join("fake-kast");
    std::fs::write(
        &fake_kast,
        "#!/bin/sh\nprintf 'argv:%s %s %s %s\\n' \"$1\" \"$2\" \"$3\" \"$4\"\ncat\n",
    )
    .expect("fake Kast");
    std::fs::set_permissions(&fake_kast, std::fs::Permissions::from_mode(0o755))
        .expect("fake Kast mode");
    let launcher = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/codex-plugin/plugins/kast/scripts/kast-codex-hook");
    let mut child = std::process::Command::new(launcher)
        .arg("post-tool-use")
        .env("KAST_CODEX_BINARY", &fake_kast)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .expect("launcher");
    use std::io::Write as _;
    child
        .stdin
        .as_mut()
        .expect("launcher stdin")
        .write_all(b"{\"session_id\":\"forwarded\"}\n")
        .expect("launcher input");
    let output = child.wait_with_output().expect("launcher output");
    assert!(output.status.success());
    assert_eq!(
        String::from_utf8(output.stdout).expect("UTF-8 launcher output"),
        "argv:developer codex hook post-tool-use\n{\"session_id\":\"forwarded\"}\n"
    );
}

fn hook(
    home: &std::path::Path,
    config_home: &std::path::Path,
    plugin_data: &std::path::Path,
    workspace: &std::path::Path,
    event: &str,
    input: &serde_json::Value,
) -> Output {
    let mut child = kast(home, config_home)
        .args(["developer", "codex", "hook", event])
        .env("PLUGIN_DATA", plugin_data)
        .env(
            "PLUGIN_ROOT",
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("resources/codex-plugin/plugins/kast"),
        )
        .current_dir(workspace)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .expect("spawn hook");
    serde_json::to_writer(child.stdin.as_mut().expect("hook stdin"), input).expect("hook input");
    child.wait_with_output().expect("hook output")
}

fn assert_hook_success(output: &Output) {
    assert!(
        output.status.success(),
        "hook failed: {}",
        String::from_utf8_lossy(&output.stdout)
    );
}

fn git<const N: usize>(workspace: &std::path::Path, args: [&str; N]) {
    let status = std::process::Command::new("git")
        .args(args)
        .current_dir(workspace)
        .status()
        .expect("git");
    assert!(status.success());
}

fn walk_contains_name(root: &std::path::Path, expected: &str) -> bool {
    let Ok(entries) = std::fs::read_dir(root) else {
        return false;
    };
    entries.filter_map(Result::ok).any(|entry| {
        entry.file_name() == expected
            || (entry.file_type().is_ok_and(|kind| kind.is_dir())
                && walk_contains_name(&entry.path(), expected))
    })
}
