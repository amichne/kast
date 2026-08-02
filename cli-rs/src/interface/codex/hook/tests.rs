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
    fn settings_file_wins_over_nested_build_file() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let module = workspace.join("module");
        let nested = module.join("src");
        std::fs::create_dir_all(&nested).expect("nested module");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings file");
        std::fs::write(module.join("build.gradle.kts"), "").expect("build file");

        assert_eq!(
            crate::config::find_workspace_root_from(&nested),
            Some(workspace)
        );
    }

    #[test]
    fn marker_directory_is_not_a_hook_workspace() {
        let temp = tempfile::tempdir().expect("tempdir");
        std::fs::create_dir(temp.path().join("build.gradle.kts")).expect("marker directory");

        assert_eq!(crate::config::find_workspace_root_from(temp.path()), None);
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
    fn successful_session_start_is_quiet() {
        let calls = std::cell::RefCell::new(Vec::new());

        let output = session_start_with_runner(Path::new("/workspace"), |args| {
            calls.borrow_mut().push(args.to_vec());
            Ok("{\"state\":\"INDEXING\"}".to_string())
        });

        assert_eq!(calls.borrow().len(), 1);
        assert!(calls.borrow()[0].contains(&OsString::from("--accept-indexing")));
        assert_eq!(output, json!({}));
    }

    #[test]
    fn relative_hook_cwd_resolves_to_an_absolute_workspace() {
        let current = std::env::current_dir().expect("current dir");
        let temp = tempfile::tempdir_in(&current).expect("tempdir");
        let nested = temp.path().join("module");
        std::fs::create_dir(&nested).expect("nested workspace");
        std::fs::write(temp.path().join("settings.gradle.kts"), "").expect("settings file");
        let relative = nested.strip_prefix(&current).expect("relative cwd");
        let input = HookInput {
            cwd: Some(relative.to_path_buf()),
            tool_name: None,
            tool_input: Value::Null,
            tool_response: Value::Null,
        };
        let workspace_argument = std::cell::RefCell::new(None);

        evaluate_with_runner(CodexHookEvent::SessionStart, input, |args| {
            workspace_argument.replace(args.get(6).cloned());
            Ok(String::new())
        })
        .expect("hook evaluation");

        assert_eq!(
            workspace_argument.into_inner(),
            Some(temp.path().as_os_str().to_os_string())
        );
    }

    #[test]
    fn unsupported_root_does_not_reach_the_hook_runner() {
        let temp = tempfile::tempdir().expect("tempdir");
        let calls = std::cell::Cell::new(0);
        let input = HookInput {
            cwd: Some(temp.path().to_path_buf()),
            tool_name: None,
            tool_input: Value::Null,
            tool_response: Value::Null,
        };

        let output = evaluate_with_runner(CodexHookEvent::SessionStart, input, |_| {
            calls.set(calls.get() + 1);
            Ok(String::new())
        })
        .expect("hook evaluation");

        assert_eq!((calls.get(), output), (0, json!({})));
    }

    #[test]
    fn unavailable_status_is_quiet_and_skips_diagnostics() {
        let calls = std::cell::RefCell::new(Vec::new());
        let input = input(
            "Write",
            json!({"file_path": "src/A.kt"}),
            json!({"success": true}),
        );

        let output = post_tool_use_with_runner(
            &input,
            Path::new("/workspace"),
            Path::new("/workspace"),
            |args| {
                calls.borrow_mut().push(args.to_vec());
                Err(CliError::new(
                    "CODEX_HOOK_COMMAND_FAILED",
                    "Kast is unavailable",
                ))
            },
        );

        assert_eq!(calls.borrow().len(), 1);
        assert_eq!(output, json!({}));
    }

    #[test]
    fn healthy_status_runs_diagnostics() {
        let calls = std::cell::RefCell::new(Vec::new());
        let input = input(
            "Write",
            json!({"file_path": "src/A.kt"}),
            json!({"success": true}),
        );
        let healthy = json!({
            "workspaceRoot": "/workspace",
            "selected": {
                "ready": true,
                "descriptor": {"workspaceRoot": "/workspace"},
                "runtimeStatus": {"healthy": true}
            }
        })
        .to_string();

        let output = post_tool_use_with_runner(
            &input,
            Path::new("/workspace"),
            Path::new("/workspace"),
            |args| {
                let call_count = {
                    let mut calls = calls.borrow_mut();
                    calls.push(args.to_vec());
                    calls.len()
                };
                Ok(if call_count == 1 {
                    healthy.clone()
                } else {
                    "{\"diagnostics\":[]}".to_string()
                })
            },
        );

        assert_eq!(calls.borrow().len(), 2);
        assert!(
            output
                .pointer("/hookSpecificOutput/additionalContext")
                .and_then(Value::as_str)
                .is_some_and(|context| context.contains("Kast diagnostics: completed"))
        );
    }
}
