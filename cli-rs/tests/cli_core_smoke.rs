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
    for command in ["help", "version", "setup", "ready", "status", "developer"] {
        assert!(
            help_lists_command(&help_stdout, command),
            "top-level help should show {command}: {help_stdout}"
        );
    }
    for hidden in [
        "agent", "runtime", "inspect", "machine", "release", "rpc", "doctor", "install", "paths",
        "up", "package",
    ] {
        assert!(
            !help_lists_command(&help_stdout, hidden),
            "hidden or legacy top-level command {hidden} should not appear in public help: {help_stdout}"
        );
    }
    for hidden_topic in [
        "agent", "runtime", "inspect", "machine", "release", "rpc", "doctor",
    ] {
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
    for command in ["lsp", "tools", "call", "workflow"] {
        assert!(
            help_lists_command(&agent_help_stdout, command),
            "agent help should show {command}: {agent_help_stdout}"
        );
    }
    for hidden in [
        "up",
        "ready",
        "setup",
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
        .args(["agent", "tools"])
        .output()
        .expect("agent tools");
    assert!(agent_tools.status.success());
    let agent_tools_json: serde_json::Value =
        serde_json::from_slice(&agent_tools.stdout).expect("agent tools json");
    assert_eq!(agent_tools_json["ok"], true);
    assert_eq!(agent_tools_json["method"], "agent/tools");
    assert_eq!(agent_tools_json["result"]["type"], "KAST_AGENT_TOOLS");
    assert_eq!(
        agent_tools_json["result"]["catalogSha256"]
            .as_str()
            .expect("catalog checksum")
            .len(),
        64
    );
    assert_eq!(
        agent_tools_json["result"]["invocation"]["command"],
        "kast agent call"
    );
    let invocation_argv = agent_tools_json["result"]["invocation"]["argv"]
        .as_array()
        .expect("agent invocation argv");
    assert_eq!(invocation_argv.len(), 4, "{invocation_argv:#?}");
    assert!(
        !invocation_argv[0]
            .as_str()
            .expect("agent invocation executable")
            .is_empty(),
        "{invocation_argv:#?}"
    );
    assert_eq!(invocation_argv[1], "agent");
    assert_eq!(invocation_argv[2], "call");
    assert_eq!(invocation_argv[3], "<method>");
    let tools = agent_tools_json["result"]["tools"]
        .as_array()
        .expect("tools array");
    let tool_names = tools
        .iter()
        .map(|tool| tool["name"].as_str().expect("tool name"))
        .collect::<std::collections::BTreeSet<_>>();
    assert_eq!(
        tool_names,
        std::collections::BTreeSet::from([
            "kast_callers",
            "kast_diagnostics",
            "kast_file_outline",
            "kast_metrics",
            "kast_references",
            "kast_rename",
            "kast_resolve",
            "kast_scaffold",
            "kast_symbol_discover",
            "kast_symbol_query",
            "kast_workspace_files",
            "kast_workspace_search",
            "kast_workspace_symbol",
            "kast_write_and_validate",
        ])
    );
    assert_eq!(agent_tools_json["result"]["toolCount"], tools.len());
    let resolve_tool = tools
        .iter()
        .find(|tool| tool["name"] == "kast_resolve")
        .expect("resolve tool");
    assert_eq!(resolve_tool["method"], "symbol/resolve");
    assert_eq!(resolve_tool["mutates"], false);
    assert!(
        resolve_tool["parameters"]["required"]
            .as_array()
            .expect("resolve required")
            .iter()
            .any(|field| field == "symbol"),
        "{resolve_tool:#}"
    );
    let rename_tool = tools
        .iter()
        .find(|tool| tool["name"] == "kast_rename")
        .expect("rename tool");
    assert_eq!(rename_tool["mutates"], true);
    assert!(
        rename_tool["parameters"]["oneOf"]
            .as_array()
            .expect("rename variants")
            .len()
            >= 2,
        "{rename_tool:#}"
    );

    let setup_help = kast(&home, &config_home)
        .args(["setup", "--help"])
        .output()
        .expect("setup help");
    assert!(setup_help.status.success());
    let setup_help_stdout = String::from_utf8_lossy(&setup_help.stdout);
    for flag in [
        "--workspace-root",
        "--backend",
        "--agents-md",
        "--dry-run",
        "--no-open-ide",
    ] {
        assert!(
            setup_help_stdout.contains(flag),
            "setup help should expose {flag}: {setup_help_stdout}"
        );
    }
    let setup_plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "setup",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
            "--dry-run",
            "--no-open-ide",
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
    assert_eq!(setup_plan_json["setup"]["type"], "AGENT_SETUP_PLAN");
    assert_eq!(setup_plan_json["dryRun"], true);
    assert_eq!(
        setup_plan_json["setup"]["installCommand"][1], "setup",
        "root setup dry-run should advertise root setup, not hidden agent setup: {setup_plan_json:#}"
    );
    assert_eq!(
        setup_plan_json["runtimeCommand"][1], "setup",
        "root setup runtime action should advertise root setup, not hidden runtime up: {setup_plan_json:#}"
    );
    assert_eq!(
        setup_plan_json["nextActions"]
            .as_array()
            .expect("next actions")
            .len(),
        1,
        "root setup dry-run should expose one one-touch next action: {setup_plan_json:#}"
    );
    assert!(
        setup_plan_json["nextActions"][0]["argv"]
            .as_array()
            .expect("setup next action argv")
            .iter()
            .any(|arg| arg == "--no-open-ide"),
        "root setup dry-run next action should preserve noninteractive setup: {setup_plan_json:#}"
    );
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
        .args(["agent", "call", "symbol/resolve"])
        .output()
        .expect("agent validation failure");
    assert!(
        !invalid_agent_call.status.success(),
        "missing required params should fail validation before dispatch"
    );
    let invalid_agent_json: serde_json::Value =
        serde_json::from_slice(&invalid_agent_call.stdout).expect("agent validation json");
    assert_eq!(invalid_agent_json["ok"], false);
    assert_eq!(invalid_agent_json["method"], "symbol/resolve");
    assert_eq!(invalid_agent_json["error"]["code"], "AGENT_REQUEST_INVALID");

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

    for command in ["skill", "instructions", "copilot"] {
        let help = kast(&home, &config_home)
            .args(["agent", "setup", command, "--help"])
            .output()
            .unwrap_or_else(|error| panic!("agent setup {command} help: {error}"));
        assert!(
            help.status.success(),
            "agent setup {command} help should succeed"
        );
        let stdout = String::from_utf8_lossy(&help.stdout);
        assert!(
            stdout.contains("-f, --force"),
            "agent setup {command} help should expose -f/--force: {stdout}"
        );
    }
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
        String::from_utf8_lossy(&lsp_without_stdio.stderr).contains("kast agent lsp --stdio"),
        "lsp usage error should name the supported command: stderr={}",
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

    let repair = kast(&home, &config_home)
        .args(["ready", "--fix"])
        .output()
        .expect("ready repair");
    assert!(
        repair.status.success(),
        "ready --fix should converge the install: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );
    assert!(install_manifest_path(&home).is_file());

    let skill_dir = temp.path().join("skills");
    let skill = kast(&home, &config_home)
        .args([
            "agent",
            "setup",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "--force",
        ])
        .output()
        .expect("install skill");
    assert!(skill.status.success());
    assert!(skill_dir.join("kast/SKILL.md").is_file());
    assert!(!skill_dir.join("kast/AGENTS.md").exists());
    assert!(!skill_dir.join("kast/references").exists());
    assert!(!skill_dir.join("kast/scripts").exists());

    let instructions_dir = temp.path().join("instructions");
    let instructions = kast(&home, &config_home)
        .args([
            "agent",
            "setup",
            "instructions",
            "--target-dir",
            instructions_dir.to_str().expect("instructions path"),
            "--force",
        ])
        .output()
        .expect("install instructions");
    assert!(instructions.status.success());
    assert!(instructions_dir.join("kast/README.md").is_file());
    assert!(instructions_dir.join("kast/cli.md").is_file());
    assert!(instructions_dir.join("kast/tools.md").is_file());
    assert!(instructions_dir.join("kast/lsp.md").is_file());
    assert!(!instructions_dir.join("kast/rpc.md").exists());

    let github_dir = temp.path().join(".github");
    let copilot = kast(&home, &config_home)
        .args([
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot plugin");
    assert!(copilot.status.success());
    assert!(github_dir.join("lsp.json").is_file());
    assert!(!github_dir.join("agents/kast-reader.agent.md").exists());
    assert!(!github_dir.join("agents/kast-writer.agent.md").exists());
    assert!(!github_dir.join(".kast-copilot-version").exists());

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
    assert!(status.status.success());
    assert!(String::from_utf8_lossy(&status.stdout).contains("\"candidates\": []"));
}
