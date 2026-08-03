use super::*;

#[test]
fn every_harness_activation_requires_matching_cli_plugin_hook_and_skill() {
    for harness in HARNESSES {
        let fixture = tempfile::tempdir().expect("temporary activation fixture");
        let bin = fixture.path().join("bin");
        fs::create_dir(&bin).expect("create provider bin directory");
        write_provider(&bin.join(harness.name));
        let provider_log = fixture.path().join("providers.log");
        let kast_home = fixture.path().join("kast");
        let mut path_entries = vec![bin];
        path_entries.extend(std::env::split_paths(
            &std::env::var_os("PATH").unwrap_or_else(|| OsString::from("/usr/bin:/bin")),
        ));

        let install = kast()
            .args([
                "__internal",
                "resources",
                "install",
                "--harness",
                harness.name,
            ])
            .env(
                "PATH",
                std::env::join_paths(path_entries).expect("provider PATH"),
            )
            .env("HOME", fixture.path().join("home"))
            .env("KAST_HOME", &kast_home)
            .env("KAST_PROVIDER_LOG", provider_log)
            .output()
            .expect("materialize harness resources");
        assert!(
            install.status.success(),
            "{} resource install: {install:?}",
            harness.name
        );

        let plugin_root = installed_plugin_root(&kast_home, harness.name);
        assert_provider_hook_schema(&plugin_root, harness);
        let command = hook_command(&plugin_root, harness.hooks_path, harness.session_pointer);
        assert!(
            command.contains(&format!("KAST_AGENT_PROVIDER={}", harness.name))
                && command.contains("KAST_AGENT_RESOURCE_ROOT=")
                && command.contains(harness.root_token)
                && command.contains("developer agent-hook session-start")
                && !command.contains("developer codex"),
            "{} SessionStart must enter the Rust pairing gate: {command}",
            harness.name
        );
        install_control_binary(&kast_home);

        let matching = run_hook(
            &command,
            fixture.path(),
            &kast_home,
            &plugin_root,
            session_start_input(fixture.path()),
        );
        assert!(
            matching.status.success(),
            "matching activation: {matching:?}"
        );
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&matching.stdout)
                .expect("parse matching activation"),
            serde_json::json!({})
        );
        let pre_tool_command = (harness.name == "copilot").then(|| {
            let command =
                hook_command(&plugin_root, harness.hooks_path, "/hooks/preToolUse/0/bash");
            assert!(
                command.contains("KAST_AGENT_PROVIDER=copilot")
                    && command.contains("developer agent-hook pre-tool-use")
                    && !command.contains("developer codex"),
                "Copilot pre-tool activation must enter the Rust pairing gate: {command}"
            );
            let allowed = run_hook(
                &command,
                fixture.path(),
                &kast_home,
                &plugin_root,
                serde_json::json!({
                    "cwd": fixture.path(),
                    "toolName": "bash",
                    "toolArgs": {"command": "true"}
                }),
            );
            assert!(
                allowed.status.success(),
                "matching Copilot pre-tool gate: {allowed:?}"
            );
            assert_eq!(
                serde_json::from_slice::<serde_json::Value>(&allowed.stdout)
                    .expect("parse matching Copilot pre-tool response"),
                serde_json::json!({})
            );
            command
        });

        let plugin_path = plugin_root.join(harness.plugin_path);
        let original_plugin = fs::read_to_string(&plugin_path).expect("read installed plugin");
        let mut version_mismatch: serde_json::Value =
            serde_json::from_str(&original_plugin).expect("parse installed plugin");
        version_mismatch["version"] = serde_json::json!("0.0.0-mismatch");
        fs::write(&plugin_path, version_mismatch.to_string()).expect("write version mismatch");
        assert_activation_blocked(
            &run_hook(
                &command,
                fixture.path(),
                &kast_home,
                &plugin_root,
                session_start_input(fixture.path()),
            ),
            harness.name,
            "version mismatch",
        );

        let mut plugin_mismatch: serde_json::Value =
            serde_json::from_str(&original_plugin).expect("parse installed plugin");
        plugin_mismatch["description"] = serde_json::json!("tampered plugin");
        fs::write(&plugin_path, plugin_mismatch.to_string()).expect("write plugin mismatch");
        assert_activation_blocked(
            &run_hook(
                &command,
                fixture.path(),
                &kast_home,
                &plugin_root,
                session_start_input(fixture.path()),
            ),
            harness.name,
            "plugin digest mismatch",
        );
        fs::write(&plugin_path, &original_plugin).expect("restore installed plugin");

        let hooks_path = plugin_root.join(harness.hooks_path);
        let original_hooks = fs::read_to_string(&hooks_path).expect("read installed hooks");
        let mut hooks_mismatch: serde_json::Value =
            serde_json::from_str(&original_hooks).expect("parse installed hooks");
        if harness.name == "copilot" {
            hooks_mismatch["hooks"]["sessionStart"][0]["timeoutSec"] = serde_json::json!(31);
        } else {
            hooks_mismatch["hooks"]["SessionStart"][0]["hooks"][0]["timeout"] =
                serde_json::json!(31);
        }
        fs::write(&hooks_path, hooks_mismatch.to_string()).expect("write hook mismatch");
        assert_activation_blocked(
            &run_hook(
                &command,
                fixture.path(),
                &kast_home,
                &plugin_root,
                session_start_input(fixture.path()),
            ),
            harness.name,
            "hook digest mismatch",
        );
        fs::write(&hooks_path, &original_hooks).expect("restore installed hooks");

        let skill_path = plugin_root.join("skills/kast/SKILL.md");
        let original_skill = fs::read_to_string(&skill_path).expect("read installed skill");
        fs::write(&skill_path, format!("{original_skill}\ntampered\n"))
            .expect("write skill mismatch");
        assert_activation_blocked(
            &run_hook(
                &command,
                fixture.path(),
                &kast_home,
                &plugin_root,
                session_start_input(fixture.path()),
            ),
            harness.name,
            "skill digest mismatch",
        );

        if let Some(pre_tool_command) = pre_tool_command {
            assert_copilot_tool_blocked(
                &run_hook(
                    &pre_tool_command,
                    fixture.path(),
                    &kast_home,
                    &plugin_root,
                    serde_json::json!({
                        "cwd": fixture.path(),
                        "toolName": "bash",
                        "toolArgs": {"command": "true"}
                    }),
                ),
                "skill digest mismatch",
            );
        }
    }
}
#[test]
fn session_activation_requires_provider_and_resource_root_identity() {
    let fixture = tempfile::tempdir().expect("temporary activation identity fixture");
    let kast_home = fixture.path().join("kast");
    install_control_binary(&kast_home);

    let legacy = Command::new(kast_home.join("current/libexec/kastctl"))
        .args(["developer", "codex", "hook", "session-start"])
        .current_dir(fixture.path())
        .env("HOME", fixture.path().join("home"))
        .env("KAST_HOME", &kast_home)
        .env_remove("KAST_AGENT_PROVIDER")
        .env_remove("KAST_AGENT_RESOURCE_ROOT")
        .output()
        .expect("run legacy Codex hook alias");
    assert_identity_rejected(&legacy, None, "provider identity is required");

    assert_identity_rejected(
        &run_control_hook(
            fixture.path(),
            &kast_home,
            "session-start",
            None,
            Some(fixture.path()),
        ),
        None,
        "provider identity is required",
    );
    for harness in HARNESSES {
        assert_identity_rejected(
            &run_control_hook(
                fixture.path(),
                &kast_home,
                "session-start",
                Some(harness.name),
                None,
            ),
            Some(harness.name),
            "resource-root identity is required",
        );
    }

    let denied = run_control_hook(
        fixture.path(),
        &kast_home,
        "pre-tool-use",
        Some("copilot"),
        None,
    );
    assert_copilot_tool_blocked(&denied, "resource-root identity is required");
}
