mod support;

use support::*;

fn help_lists_command(stdout: &str, command: &str) -> bool {
    stdout
        .lines()
        .any(|line| line.trim_start().starts_with(command))
}

#[test]
fn smoke_core_cli_commands() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let version = kast(&home, &config_home)
        .arg("version")
        .output()
        .expect("version");
    assert!(version.status.success());
    assert!(String::from_utf8_lossy(&version.stdout).contains("Kast CLI"));

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("help");
    assert!(help.status.success());
    let help_stdout = String::from_utf8_lossy(&help.stdout);
    assert!(help_stdout.contains("Usage: kast"));
    let expected_commands = if cfg!(target_os = "macos") {
        vec![
            "help",
            "version",
            "ready",
            "repair",
            "status",
            "developer",
            "agent",
        ]
    } else {
        vec![
            "help",
            "version",
            "setup",
            "ready",
            "repair",
            "status",
            "developer",
            "agent",
        ]
    };
    for command in expected_commands {
        assert!(
            help_lists_command(&help_stdout, command),
            "top-level help should show {command}: {help_stdout}"
        );
    }
    for hidden in [
        "runtime", "inspect", "machine", "release", "rpc", "doctor", "install", "paths", "up",
        "package",
    ] {
        assert!(
            !help_lists_command(&help_stdout, hidden),
            "hidden or legacy top-level command {hidden} should not appear in public help: {help_stdout}"
        );
    }
    for hidden_topic in ["runtime", "inspect", "machine", "release", "rpc", "doctor"] {
        let topic = kast(&home, &config_home)
            .args(["help", hidden_topic])
            .output()
            .unwrap_or_else(|error| panic!("help {hidden_topic}: {error}"));
        assert!(
            !topic.status.success(),
            "hidden help topic {hidden_topic} should not resolve"
        );
    }

    let agent_help = kast(&home, &config_home)
        .args(["agent", "--help"])
        .output()
        .expect("agent help");
    assert!(agent_help.status.success());
    let agent_help_stdout = String::from_utf8_lossy(&agent_help.stdout);
    for command in ["lsp", "verify", "symbol", "impact", "diagnostics", "rename"] {
        assert!(
            help_lists_command(&agent_help_stdout, command),
            "agent help should show {command}: {agent_help_stdout}"
        );
    }
    for hidden in [
        "up",
        "ready",
        "setup",
        "tools",
        "call",
        "workflow",
        "health",
        "runtime-status",
        "raw-resolve",
        "resolve",
        "metrics",
    ] {
        assert!(
            !help_lists_command(&agent_help_stdout, hidden),
            "hidden agent command {hidden} should not appear in agent help: {agent_help_stdout}"
        );
    }

    let agent_tools = kast(&home, &config_home)
        .args(["--output", "json", "agent", "tools", "--full"])
        .output()
        .expect("removed agent tools");
    assert!(
        !agent_tools.status.success(),
        "agent tools should be removed from the public surface"
    );
    let agent_tools_json: serde_json::Value =
        serde_json::from_slice(&agent_tools.stdout).expect("agent tools removal json");
    assert_eq!(agent_tools_json["ok"], false);
    assert_eq!(agent_tools_json["method"], "agent/tools");
    assert_eq!(agent_tools_json["error"]["code"], "AGENT_COMMAND_REMOVED");
    assert!(
        agent_tools_json["error"]["details"]["replacements"]
            .as_array()
            .expect("replacement commands")
            .iter()
            .any(|command| command == "kast help agent"),
        "{agent_tools_json:#}"
    );

    let rename_plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "com.example.Target",
            "--new-name",
            "RenamedTarget",
        ])
        .output()
        .expect("agent rename plan");
    assert!(
        rename_plan.status.success(),
        "agent rename without --apply should be plan-only: stdout={}, stderr={}",
        String::from_utf8_lossy(&rename_plan.stdout),
        String::from_utf8_lossy(&rename_plan.stderr)
    );
    let rename_plan_json: serde_json::Value =
        serde_json::from_slice(&rename_plan.stdout).expect("rename plan json");
    assert_eq!(rename_plan_json["ok"], true, "{rename_plan_json:#}");
    assert_eq!(rename_plan_json["method"], "agent/rename");
    assert_eq!(rename_plan_json["result"]["type"], "KAST_AGENT_RENAME_PLAN");
    assert_eq!(
        rename_plan_json["result"]["request"]["method"],
        "symbol/rename"
    );
    assert_eq!(
        rename_plan_json["result"]["request"]["params"]["type"],
        "RENAME_BY_SYMBOL_REQUEST"
    );
    assert!(
        rename_plan_json["result"]["request"]["params"]
            .as_object()
            .expect("rename params")
            .get("offset")
            .is_none(),
        "{rename_plan_json:#}"
    );

    if cfg!(target_os = "macos") {
        let setup = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "setup",
                "--workspace-root",
                workspace.to_str().expect("workspace path"),
            ])
            .output()
            .expect("setup macos refusal");
        assert!(
            !setup.status.success(),
            "setup should fail closed on macOS: stdout={}, stderr={}",
            String::from_utf8_lossy(&setup.stdout),
            String::from_utf8_lossy(&setup.stderr)
        );
        let setup_json: serde_json::Value =
            serde_json::from_slice(&setup.stdout).expect("setup refusal json");
        assert_eq!(setup_json["ok"], false);
        assert_eq!(setup_json["method"], "setup");
        assert_eq!(setup_json["error"]["code"], "AGENT_COMMAND_REMOVED");
    } else {
        let setup_help = kast(&home, &config_home)
            .args(["setup", "--help"])
            .output()
            .expect("setup help");
        assert!(setup_help.status.success());
        let setup_help_stdout = String::from_utf8_lossy(&setup_help.stdout);
        for flag in [
            "--workspace-root",
            "--skill-target-dir",
            "--context-file",
            "--dry-run",
        ] {
            assert!(
                setup_help_stdout.contains(flag),
                "setup help should expose {flag}: {setup_help_stdout}"
            );
        }
        assert!(
            !setup_help_stdout.contains("--backend"),
            "setup should not expose backend/runtime selection: {setup_help_stdout}"
        );
        let setup_plan = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "setup",
                "--workspace-root",
                workspace.to_str().expect("workspace path"),
                "--dry-run",
            ])
            .output()
            .expect("setup dry-run");
        assert!(
            setup_plan.status.success(),
            "setup dry-run should plan without requiring installed backend: stdout={}, stderr={}",
            String::from_utf8_lossy(&setup_plan.stdout),
            String::from_utf8_lossy(&setup_plan.stderr)
        );
        let setup_plan_json: serde_json::Value =
            serde_json::from_slice(&setup_plan.stdout).expect("setup plan json");
        assert_eq!(setup_plan_json["type"], "AGENT_SETUP_PLAN");
        assert_eq!(setup_plan_json["dryRun"], true);
        assert_eq!(
            setup_plan_json["installCommand"][1], "setup",
            "root setup dry-run should advertise root setup, not hidden agent setup: {setup_plan_json:#}"
        );
        assert!(
            setup_plan_json.get("runtimeCommand").is_none(),
            "setup without --backend should not plan runtime warmup: {setup_plan_json:#}"
        );
    }
    assert!(
        !install_manifest_path(&home).exists(),
        "setup --dry-run must not run readiness repair or write install state"
    );

    for removed_root in ["runtime", "inspect", "machine", "release", "rpc"] {
        let direct = kast(&home, &config_home)
            .args([removed_root, "--help"])
            .output()
            .unwrap_or_else(|error| panic!("{removed_root} --help: {error}"));
        assert!(
            !direct.status.success(),
            "removed root command {removed_root} should not be directly callable"
        );
    }

    let invalid_agent_call = kast(&home, &config_home)
        .args(["--output", "json", "agent", "call", "symbol/resolve"])
        .output()
        .expect("agent call removal");
    assert!(
        !invalid_agent_call.status.success(),
        "agent call should be removed from the public surface"
    );
    let invalid_agent_json: serde_json::Value =
        serde_json::from_slice(&invalid_agent_call.stdout).expect("agent call removal json");
    assert_eq!(invalid_agent_json["ok"], false);
    assert_eq!(invalid_agent_json["method"], "agent/call");
    assert_eq!(invalid_agent_json["error"]["code"], "AGENT_COMMAND_REMOVED");

    let activate_bundle_help = kast(&home, &config_home)
        .args(["developer", "release", "activate", "bundle", "--help"])
        .output()
        .expect("developer release activate bundle help");
    assert!(activate_bundle_help.status.success());
    let activate_bundle_stdout = String::from_utf8_lossy(&activate_bundle_help.stdout);
    assert!(
        activate_bundle_stdout.contains("--verify-only"),
        "release activate bundle help should expose read-only verification: {activate_bundle_stdout}"
    );

    let agent_setup_help = kast(&home, &config_home)
        .args(["agent", "setup", "--help"])
        .output()
        .expect("hidden agent setup help");
    assert!(
        !agent_setup_help.status.success()
            || !String::from_utf8_lossy(&agent_setup_help.stdout).contains("copilot"),
        "agent setup should not expose portable asset installers: stdout={}, stderr={}",
        String::from_utf8_lossy(&agent_setup_help.stdout),
        String::from_utf8_lossy(&agent_setup_help.stderr)
    );
    let shell_help = kast(&home, &config_home)
        .args(["developer", "machine", "shell", "--help"])
        .output()
        .expect("developer machine shell help");
    assert!(shell_help.status.success());
    let shell_help_stdout = String::from_utf8_lossy(&shell_help.stdout);
    assert!(
        shell_help_stdout.contains("--shell"),
        "machine shell help should expose --shell: {shell_help_stdout}"
    );

    let lsp_help = kast(&home, &config_home)
        .args(["agent", "lsp", "--help"])
        .output()
        .expect("agent lsp help");
    assert!(lsp_help.status.success());
    let lsp_help_stdout = String::from_utf8_lossy(&lsp_help.stdout);
    for visible in [
        "--stdio",
        "--workspace-root",
        "--backend",
        "--request-timeout-ms",
    ] {
        assert!(
            lsp_help_stdout.contains(visible),
            "lsp help should expose {visible}: {lsp_help_stdout}"
        );
    }

    let lsp_without_stdio = kast(&home, &config_home)
        .args(["agent", "lsp"])
        .output()
        .expect("lsp without stdio");
    assert!(
        !lsp_without_stdio.status.success(),
        "lsp without --stdio should fail closed"
    );
    assert!(
        String::from_utf8_lossy(&lsp_without_stdio.stdout).contains("kast agent lsp --stdio"),
        "lsp usage error should name the supported command: stdout={}, stderr={}",
        String::from_utf8_lossy(&lsp_without_stdio.stdout),
        String::from_utf8_lossy(&lsp_without_stdio.stderr)
    );
    assert!(
        shell_help_stdout.contains("--profile"),
        "machine shell help should expose --profile: {shell_help_stdout}"
    );
    let demo_help = kast(&home, &config_home)
        .args(["developer", "inspect", "demo", "--help"])
        .output()
        .expect("developer inspect demo help");
    assert!(demo_help.status.success());
    let demo_help_stdout = String::from_utf8_lossy(&demo_help.stdout);
    assert!(demo_help_stdout.contains("source-index demo"));
    assert!(demo_help_stdout.contains("compare"));

    let repair_plan = kast(&home, &config_home)
        .args(["--output", "json", "repair"])
        .output()
        .expect("repair plan");
    assert!(
        !repair_plan.status.success(),
        "repair without --apply should plan but still report not ready before manifest exists"
    );
    assert!(!install_manifest_path(&home).exists());

    let repair = kast(&home, &config_home)
        .args(["--output", "json", "repair", "--apply"])
        .output()
        .expect("repair apply");
    #[cfg(not(target_os = "macos"))]
    {
        assert!(
            repair.status.success(),
            "repair --apply should converge the install: stdout={}, stderr={}",
            String::from_utf8_lossy(&repair.stdout),
            String::from_utf8_lossy(&repair.stderr)
        );
        let repair_json: serde_json::Value =
            serde_json::from_slice(&repair.stdout).expect("repair json");
        assert_eq!(repair_json["type"], "KAST_REPAIR");
        assert_eq!(repair_json["applied"], true);
        assert!(install_manifest_path(&home).is_file());
    }
    #[cfg(target_os = "macos")]
    {
        assert!(
            !repair.status.success(),
            "macOS repair --apply should not report agent-ready without plugin metadata"
        );
        let repair_json: serde_json::Value =
            serde_json::from_slice(&repair.stdout).expect("repair json");
        assert_eq!(repair_json["type"], "KAST_REPAIR");
        assert_eq!(repair_json["applied"], true);
        assert!(
            repair_json["ready"]["issues"]
                .as_array()
                .expect("ready issues")
                .iter()
                .any(|issue| issue
                    .as_str()
                    .is_some_and(|issue| issue.contains("plugin-prepared workspace metadata"))),
            "{repair_json:#}"
        );
        assert!(install_manifest_path(&home).is_file());
    }

    let skill_dir = temp.path().join("skills");
    let skill = kast(&home, &config_home)
        .args([
            "setup",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "--force",
        ])
        .output()
        .expect("setup target-dir rejected");
    assert!(
        !skill.status.success(),
        "root setup should not accept legacy --target-dir asset installs"
    );

    let status = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("status");
    #[cfg(not(target_os = "macos"))]
    {
        assert!(status.status.success());
        assert!(String::from_utf8_lossy(&status.stdout).contains("\"candidates\": []"));
    }
    #[cfg(target_os = "macos")]
    {
        assert!(
            !status.status.success(),
            "macOS status should fail without plugin-prepared workspace metadata"
        );
        let status_json: serde_json::Value =
            serde_json::from_slice(&status.stdout).expect("status json");
        assert_eq!(status_json["code"], "MACOS_PLUGIN_WORKSPACE_REQUIRED");
    }
}
