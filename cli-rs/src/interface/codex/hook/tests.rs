#[cfg(test)]
mod tests {
    use super::*;

    fn input(tool_name: &str, tool_input: Value, tool_response: Value) -> HookInput {
        HookInput {
            cwd: None,
            tool_name: Some(tool_name.to_string()),
            tool_input,
            tool_response,
        }
    }

    #[test]
    fn successful_write_collects_every_kotlin_path() {
        let input = input(
            "apply_patch",
            json!({"patch": "*** Update File: src/A.kt\n*** Add File: build.gradle.kts"}),
            json!({"success": true}),
        );

        assert_eq!(
            qualifying_kotlin_paths(&input, Path::new("/workspace"), Path::new("/workspace")),
            BTreeSet::from(["build.gradle.kts".to_string(), "src/A.kt".to_string()])
        );
    }

    #[test]
    fn diagnostics_command_targets_one_file() {
        assert_eq!(
            diagnostics_args(Path::new("/workspace"), "src/A.kt"),
            [
                "--output",
                "json",
                "agent",
                "diagnostics",
                "--workspace-root",
                "/workspace",
                "--backend",
                "idea",
                "--file-path",
                "src/A.kt",
            ]
            .map(OsString::from)
        );
    }

    #[test]
    fn failed_or_non_kotlin_edits_are_ignored() {
        let failed = input(
            "Edit",
            json!({"file_path": "src/A.kt"}),
            json!({"success": false}),
        );
        let non_kotlin = input(
            "Write",
            json!({"file_path": "README.md"}),
            json!({"success": true}),
        );

        assert!(
            qualifying_kotlin_paths(&failed, Path::new("/workspace"), Path::new("/workspace"))
                .is_empty()
        );
        assert!(
            qualifying_kotlin_paths(
                &non_kotlin,
                Path::new("/workspace"),
                Path::new("/workspace")
            )
            .is_empty()
        );
    }

    #[test]
    fn nested_session_resolves_root_relative_kotlin_paths() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let nested = workspace.join("module");
        std::fs::create_dir_all(&nested).expect("nested workspace");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("workspace marker");
        let input = input(
            "Write",
            json!({"file_path": "src/A.kt"}),
            json!({"success": true}),
        );

        let resolved = crate::config::resolve_workspace_root_from(&nested);

        assert_eq!(resolved, workspace);
        assert_eq!(
            qualifying_kotlin_paths(&input, &resolved, &nested),
            BTreeSet::from(["module/src/A.kt".to_string()])
        );
    }

    #[test]
    fn status_requires_a_healthy_exact_root() {
        let healthy = json!({
            "workspaceRoot": "/workspace",
            "selected": {
                "ready": true,
                "descriptor": {"workspaceRoot": "/workspace"},
                "runtimeStatus": {"healthy": true}
            }
        });

        assert!(status_is_healthy(
            &healthy.to_string(),
            Path::new("/workspace")
        ));
        assert!(!status_is_healthy(
            &healthy.to_string(),
            Path::new("/other")
        ));
    }

    #[test]
    fn failures_render_as_advisory_context() {
        let context = advisory_result(
            "Kast diagnostics",
            Err(CliError::new(
                "DIAGNOSTICS_FAILED",
                "diagnostics unavailable",
            )),
        );

        assert!(context.contains("advisory failure"));
    }

    #[test]
    fn global_and_per_hook_switches_gate_events() {
        let disabled = CodexHooksConfig {
            enabled: false,
            session_start: true,
            post_tool_use: true,
        };
        let session_only = CodexHooksConfig {
            enabled: true,
            session_start: true,
            post_tool_use: false,
        };

        assert!(!hook_enabled(&disabled, CodexHookEvent::SessionStart));
        assert!(hook_enabled(&session_only, CodexHookEvent::SessionStart));
        assert!(!hook_enabled(&session_only, CodexHookEvent::PostToolUse));
    }

    #[test]
    fn session_start_invokes_kast_once_and_accepts_indexing() {
        let calls = std::cell::RefCell::new(Vec::new());

        let output = session_start_with_runner(Path::new("/workspace"), |args| {
            calls.borrow_mut().push(args.to_vec());
            Ok("{\"state\":\"INDEXING\"}".to_string())
        });

        assert_eq!(calls.borrow().len(), 1);
        assert!(calls.borrow()[0].contains(&OsString::from("--accept-indexing")));
        assert_eq!(
            output.pointer("/hookSpecificOutput/hookEventName"),
            Some(&json!("SessionStart"))
        );
    }
}
