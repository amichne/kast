mod support;

use std::os::unix::fs::PermissionsExt;
use std::process::Output;
use support::*;

#[test]
fn codex_generator_materializes_minimal_cli_only_plugin() {
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
        "plugins/kast/assets/codex-exposure.toon",
        "plugins/kast/assets/hook-recovery-messages.toon",
        "plugins/kast/assets/kast.svg",
    ] {
        assert!(output.join(relative).is_file(), "missing {relative}");
    }
    for forbidden in [
        ".mcp.json",
        ".app.json",
        "commands.json",
        "commands.md",
        "examples.md",
    ] {
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
    for forbidden in ["hooks", "mcpServers", "apps", "agents"] {
        assert!(manifest.get(forbidden).is_none());
    }
    assert_eq!(
        std::fs::read(output.join("marketplace.json")).expect("release marketplace"),
        std::fs::read(output.join(".agents/plugins/marketplace.json"))
            .expect("Codex discovery marketplace")
    );
    let recovery =
        std::fs::read_to_string(output.join("plugins/kast/assets/hook-recovery-messages.toon"))
            .expect("hook recovery messages");
    assert!(recovery.contains("HOOK_TRUST_REQUIRED"));
    assert!(!recovery.contains("--output json"));
}

#[test]
fn codex_generator_check_reports_drift_without_scratch_storage() {
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
    let clean = kast(&home, &config_home)
        .env("TMPDIR", &invalid_temp_directory)
        .args(["developer", "codex", "generate", "--check", "--output-dir"])
        .arg(&output)
        .output()
        .expect("check");
    assert!(clean.status.success());

    std::fs::write(
        output.join("plugins/kast/skills/kast-codex/SKILL.md"),
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
fn codex_hooks_share_the_task_core_and_stop_is_a_hard_gate() {
    let fixture = HookFixture::new();
    let session = "codex-session";
    let start = fixture.hook(
        "session-start",
        serde_json::json!({
            "session_id": session,
            "cwd": fixture.workspace,
            "source": "startup",
        }),
    );
    assert_hook_success(&start);
    let start_value = output_json(&start);
    assert_eq!(
        start_value["hookSpecificOutput"]["hookEventName"],
        "SessionStart"
    );
    let context = start_value["hookSpecificOutput"]["additionalContext"]
        .as_str()
        .expect("start context");
    assert!(context.contains("operation: begin"));
    assert!(context.contains("state: ACTIVE"));

    let resumed = fixture.hook(
        "session-start",
        serde_json::json!({
            "session_id": session,
            "cwd": fixture.workspace,
            "source": "resume",
        }),
    );
    assert_hook_success(&resumed);
    let resumed_value = output_json(&resumed);
    assert_eq!(
        resumed_value["hookSpecificOutput"]["hookEventName"],
        "SessionStart"
    );
    let resumed_context = resumed_value["hookSpecificOutput"]["additionalContext"]
        .as_str()
        .expect("resumed context");
    assert_eq!(
        task_context_without_observation_time(context),
        task_context_without_observation_time(resumed_context),
        "begin must rejoin the shared task and preserve its baseline",
    );

    let post = fixture.hook(
        "post-tool-use",
        serde_json::json!({
            "session_id": session,
            "cwd": fixture.workspace,
            "tool_name": "Read",
            "tool_input": {},
            "tool_response": {"ok": true},
        }),
    );
    assert_hook_success(&post);
    assert!(
        output_json(&post)["hookSpecificOutput"]["additionalContext"]
            .as_str()
            .expect("post context")
            .contains("operation: status")
    );

    std::fs::create_dir_all(fixture.workspace.join("src")).expect("source dir");
    std::fs::write(fixture.workspace.join("src/Main.java"), "class Main {}\n")
        .expect("relevant change");
    let stopped = fixture.hook(
        "stop",
        serde_json::json!({
            "session_id": session,
            "cwd": fixture.workspace,
            "last_assistant_message": "done",
        }),
    );
    assert_hook_success(&stopped);
    let stopped = output_json(&stopped);
    assert_eq!(stopped["decision"], "block");
    assert!(
        stopped["reason"]
            .as_str()
            .expect("stop reason")
            .contains("AGENT_TASK_EXPLICIT_FINISH_REQUIRED")
    );
}

#[test]
fn codex_pre_tool_use_allows_reads_across_sessions_and_gates_generic_kotlin_writes() {
    let fixture = HookFixture::new();
    assert_hook_success(&fixture.hook(
        "session-start",
        serde_json::json!({"session_id": "owner", "cwd": fixture.workspace}),
    ));
    let read = fixture.hook(
        "pre-tool-use",
        serde_json::json!({
            "session_id": "other",
            "cwd": fixture.workspace,
            "tool_name": "Read",
            "tool_input": {},
        }),
    );
    assert_hook_success(&read);
    assert_eq!(output_json(&read), serde_json::json!({}));

    let denied = fixture.hook(
        "pre-tool-use",
        serde_json::json!({
            "session_id": "other",
            "cwd": fixture.workspace,
            "tool_name": "Bash",
            "tool_input": {
                "command": "printf 'class Main' > src/Main.kt"
            },
        }),
    );
    assert_hook_success(&denied);
    let denied = output_json(&denied);
    assert_eq!(denied["hookSpecificOutput"]["permissionDecision"], "deny");
    assert!(
        denied["hookSpecificOutput"]["permissionDecisionReason"]
            .as_str()
            .expect("denial reason")
            .contains("KAST_TYPED_ROUTE_REQUIRED")
    );
}

#[test]
fn malformed_hook_input_returns_one_host_error_envelope() {
    let fixture = HookFixture::new();
    let output = kast_at(&fixture.binary, &fixture.home, &fixture.config_home)
        .args(["developer", "codex", "hook", "session-start"])
        .env("PLUGIN_DATA", &fixture.plugin_data)
        .current_dir(&fixture.workspace)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .and_then(|mut child| {
            child
                .stdin
                .as_mut()
                .expect("stdin")
                .write_all(b"not-json")?;
            child.wait_with_output()
        })
        .expect("malformed hook");
    assert!(!output.status.success());
    let value = output_json(&output);
    assert_eq!(value["continue"], false);
    assert!(
        value["stopReason"]
            .as_str()
            .expect("stop reason")
            .contains("CODEX_HOOK_INPUT_INVALID")
    );
    assert_eq!(String::from_utf8_lossy(&output.stdout).lines().count(), 1);
}

#[test]
fn launcher_resolves_only_an_attested_absolute_pair() {
    let temp = tempfile::tempdir().expect("tempdir");
    let pair = temp.path().join("attested pair with spaces");
    std::fs::create_dir_all(&pair).expect("pair");
    let task_launcher = pair.join("kast-agent-task");
    std::fs::write(&task_launcher, "#!/bin/sh\nexit 0\n").expect("task launcher");
    std::fs::set_permissions(&task_launcher, std::fs::Permissions::from_mode(0o755))
        .expect("task launcher mode");
    let fake_kast = pair.join("kast");
    std::fs::write(
        &fake_kast,
        "#!/bin/sh\nprintf 'argv:%s %s %s %s\\n' \"$1\" \"$2\" \"$3\" \"$4\"\ncat\n",
    )
    .expect("fake kast");
    std::fs::set_permissions(&fake_kast, std::fs::Permissions::from_mode(0o755))
        .expect("fake kast mode");

    let launcher = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/codex-plugin/plugins/kast/scripts/kast-codex-hook");
    let mut child = std::process::Command::new(&launcher)
        .arg("post-tool-use")
        .env("KAST_AGENT_TASK_LAUNCHER", &task_launcher)
        .env("PATH", "/usr/bin:/bin")
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .expect("launcher");
    child
        .stdin
        .take()
        .expect("stdin")
        .write_all(b"forwarded\n")
        .expect("write stdin");
    let output = child.wait_with_output().expect("launcher output");
    assert!(output.status.success());
    assert_eq!(
        String::from_utf8(output.stdout).expect("UTF-8 launcher output"),
        "argv:developer codex hook post-tool-use\nforwarded\n"
    );

    let relative = std::process::Command::new(&launcher)
        .arg("stop")
        .env("KAST_AGENT_TASK_LAUNCHER", "relative/kast-agent-task")
        .output()
        .expect("relative override");
    assert!(!relative.status.success());
    assert!(
        String::from_utf8_lossy(&relative.stderr)
            .contains("KAST_AGENT_TASK_LAUNCHER must be an absolute path")
    );

    std::fs::remove_file(fake_kast).expect("remove sibling");
    let missing = std::process::Command::new(&launcher)
        .arg("stop")
        .env("KAST_AGENT_TASK_LAUNCHER", &task_launcher)
        .output()
        .expect("missing sibling");
    assert!(!missing.status.success());
    assert!(
        String::from_utf8_lossy(&missing.stderr)
            .contains("attested sibling kast is not executable")
    );
}

struct HookFixture {
    _temp: tempfile::TempDir,
    home: std::path::PathBuf,
    config_home: std::path::PathBuf,
    plugin_data: std::path::PathBuf,
    workspace: std::path::PathBuf,
    binary: std::path::PathBuf,
    model: std::path::PathBuf,
}

impl HookFixture {
    fn new() -> Self {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let plugin_data = temp.path().join("plugin-data");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&home).expect("home");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(
            workspace.join(format!("settings.gradle.{}", "kts")),
            "rootProject.name = \"fixture\"\n",
        )
        .expect("settings");
        let wrapper = workspace.join("gradlew");
        std::fs::write(
            &wrapper,
            "#!/bin/sh\nset -eu\ncp \"$KAST_TEST_GRADLE_MODEL\" \"$KAST_AGENT_TASK_GRADLE_MODEL_RECEIPT\"\n",
        )
        .expect("wrapper");
        std::fs::set_permissions(&wrapper, std::fs::Permissions::from_mode(0o755))
            .expect("wrapper mode");
        git(&workspace, ["init", "-q"]);
        git(&workspace, ["config", "user.email", "test@example.com"]);
        git(&workspace, ["config", "user.name", "Test"]);
        git(&workspace, ["add", "."]);
        git(&workspace, ["commit", "-qm", "base"]);

        let model = temp.path().join("model.json");
        let canonical_workspace = workspace.canonicalize().expect("canonical workspace");
        std::fs::write(
            &model,
            serde_json::to_vec_pretty(&serde_json::json!({
                "schemaVersion": 1,
                "workspaceRoot": canonical_workspace.display().to_string(),
                "builds": [{
                    "buildRoot": ".",
                    "projects": [{
                        "projectPath": ":",
                        "projectDirectory": ".",
                        "sourceSets": [],
                        "tasks": [],
                    }],
                }],
            }))
            .expect("model JSON"),
        )
        .expect("model");
        let shim = write_legacy_local_install_for_test(&home, &config_home);
        let task_launcher = shim.parent().expect("shim parent").join("kast-agent-task");
        std::fs::write(
            &task_launcher,
            include_bytes!("../resources/agent-task/kast-agent-task"),
        )
        .expect("task launcher");
        std::fs::set_permissions(&task_launcher, std::fs::Permissions::from_mode(0o755))
            .expect("task launcher mode");
        let manifest_path = install_manifest_path(&home);
        let mut manifest: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&manifest_path).expect("install manifest"))
                .expect("install manifest JSON");
        manifest["entrypoints"]["taskLauncher"] =
            serde_json::Value::String(task_launcher.display().to_string());
        std::fs::write(
            &manifest_path,
            serde_json::to_vec_pretty(&manifest).expect("install manifest JSON"),
        )
        .expect("install manifest");
        Self {
            _temp: temp,
            home,
            config_home,
            plugin_data,
            workspace,
            binary: shim,
            model,
        }
    }

    fn hook(&self, event: &str, input: serde_json::Value) -> Output {
        let mut child = kast_at(&self.binary, &self.home, &self.config_home)
            .args(["developer", "codex", "hook", event])
            .env("PLUGIN_DATA", &self.plugin_data)
            .env("KAST_TEST_GRADLE_MODEL", &self.model)
            .current_dir(&self.workspace)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .spawn()
            .expect("spawn hook");
        serde_json::to_writer(child.stdin.as_mut().expect("hook stdin"), &input)
            .expect("hook input");
        child.wait_with_output().expect("hook output")
    }
}

fn output_json(output: &Output) -> serde_json::Value {
    serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "hook output is not JSON: {error}: {}",
            String::from_utf8_lossy(&output.stdout)
        )
    })
}

fn assert_hook_success(output: &Output) {
    assert!(
        output.status.success(),
        "hook failed: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
}

fn task_context_without_observation_time(context: &str) -> Vec<&str> {
    context
        .lines()
        .filter(|line| !line.trim_start().starts_with("updatedAt:"))
        .collect()
}

fn git<const N: usize>(workspace: &std::path::Path, args: [&str; N]) {
    assert!(
        std::process::Command::new("git")
            .args(args)
            .current_dir(workspace)
            .status()
            .expect("git")
            .success()
    );
}

fn walk_contains_name(root: &std::path::Path, expected: &str) -> bool {
    let Ok(entries) = std::fs::read_dir(root) else {
        return false;
    };
    entries.filter_map(Result::ok).any(|entry| {
        let path = entry.path();
        entry.file_name() == expected || (path.is_dir() && walk_contains_name(&path, expected))
    })
}
