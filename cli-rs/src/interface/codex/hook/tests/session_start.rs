#[test]
fn global_and_per_hook_switches_gate_events() {
    let disabled = CodexHooksConfig {
        enabled: false,
        session_start: true,
        post_tool_use: true,
        auto_start_indexer: IndexerAutoStartConsent::Enabled,
    };
    let session_only = CodexHooksConfig {
        enabled: true,
        session_start: true,
        post_tool_use: false,
        auto_start_indexer: IndexerAutoStartConsent::Unconfigured,
    };

    assert!(!hook_enabled(&disabled, CodexHookEvent::SessionStart));
    assert!(hook_enabled(&session_only, CodexHookEvent::SessionStart));
    assert!(!hook_enabled(&session_only, CodexHookEvent::PostToolUse));
}

#[test]
fn global_auto_start_value_does_not_authorize_an_unconfigured_worktree() {
    let global = CodexHooksConfig {
        enabled: true,
        session_start: true,
        post_tool_use: true,
        auto_start_indexer: IndexerAutoStartConsent::Enabled,
    };
    let calls = std::cell::Cell::new(0);

    assert!(hook_enabled(&global, CodexHookEvent::SessionStart));
    let output = session_start_with_consent_and_runner(
        KastHarness::Codex,
        Path::new("/workspace"),
        IndexerAutoStartConsent::Unconfigured,
        |_| {
            calls.set(calls.get() + 1);
            Ok(String::new())
        },
    );

    assert_eq!(calls.get(), 0);
    assert!(
        output
            .pointer("/hookSpecificOutput/additionalContext")
            .is_some()
    );
}

#[test]
fn explicitly_enabled_session_start_is_quiet_after_background_handoff() {
    let calls = std::cell::RefCell::new(Vec::new());

    let output = session_start_with_consent_at_and_runner(
        KastHarness::Codex,
        Path::new("/workspace"),
        IndexerAutoStartConsent::Enabled,
        std::time::UNIX_EPOCH + std::time::Duration::from_millis(1_000),
        |args| {
            calls.borrow_mut().push(args.to_vec());
            Ok("{\"state\":\"INDEXING\"}".to_string())
        },
    );

    assert_eq!(calls.borrow().len(), 1);
    assert_eq!(
        calls.borrow()[0],
        [
            OsString::from("--output"),
            OsString::from("json"),
            OsString::from("developer"),
            OsString::from("runtime"),
            OsString::from("start-background"),
            OsString::from("--wait-timeout-ms"),
            OsString::from("20000"),
            OsString::from("--start-deadline-unix-epoch-millis"),
            OsString::from("21000"),
            OsString::from("--workspace-root"),
            OsString::from("/workspace"),
            OsString::from("--accept-indexing"),
        ],
    );
    assert_eq!(output, json!({}));
}

#[test]
fn unconfigured_session_start_requests_exact_worktree_consent_without_launching() {
    let calls = std::cell::Cell::new(0);

    let output = session_start_with_consent_and_runner(
        KastHarness::Codex,
        Path::new("/workspace"),
        IndexerAutoStartConsent::Unconfigured,
        |_| {
            calls.set(calls.get() + 1);
            Ok(String::new())
        },
    );

    assert_eq!(calls.get(), 0);
    let context = output
        .pointer("/hookSpecificOutput/additionalContext")
        .and_then(Value::as_str)
        .expect("Codex SessionStart consent context");
    assert!(context.contains("explicit consent"), "{context}");
    assert!(
        context.contains("codex.hooks.autoStartIndexer"),
        "{context}"
    );
    assert!(context.contains("/workspace"), "{context}");
}

#[test]
fn disabled_session_start_is_quiet_without_launching() {
    let calls = std::cell::Cell::new(0);

    let output = session_start_with_consent_and_runner(
        KastHarness::Claude,
        Path::new("/workspace"),
        IndexerAutoStartConsent::Disabled,
        |_| {
            calls.set(calls.get() + 1);
            Ok(String::new())
        },
    );

    assert_eq!((calls.get(), output), (0, json!({})));
}

#[test]
fn copilot_consent_uses_top_level_additional_context() {
    let output = session_start_with_consent_and_runner(
        KastHarness::Copilot,
        Path::new("/workspace"),
        IndexerAutoStartConsent::Unconfigured,
        |_| panic!("unconfigured consent must not launch"),
    );

    assert!(output.get("additionalContext").is_some(), "{output:#}");
    assert!(output.get("hookSpecificOutput").is_none(), "{output:#}");
}

#[test]
fn hook_command_timeout_is_typed_and_bounded_without_sleeping() {
    let elapsed = std::cell::Cell::new(std::time::Duration::ZERO);
    let error = run_command_bounded_with_wait(
        Path::new("/bin/sh"),
        &[OsString::from("-c"), OsString::from("sleep 2")],
        std::time::Duration::from_millis(25),
        || elapsed.get(),
        |duration| elapsed.set(elapsed.get() + duration),
    )
    .expect_err("slow hook child must time out");

    assert_eq!(error.code, "AGENT_HOOK_COMMAND_TIMEOUT");
    assert_eq!(elapsed.get(), std::time::Duration::from_millis(30));
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

    evaluate_with_consent_and_runner(
        CodexHookEvent::SessionStart,
        Some(KastHarness::Codex),
        input,
        |_| Ok(IndexerAutoStartConsent::Enabled),
        |args| {
            workspace_argument.replace(
                args.iter()
                    .position(|argument| argument == "--workspace-root")
                    .and_then(|index| args.get(index + 1))
                    .cloned(),
            );
            Ok(String::new())
        },
    )
    .expect("hook evaluation");

    assert_eq!(
        workspace_argument.into_inner(),
        Some(temp.path().as_os_str().to_os_string())
    );
}

#[test]
fn unsupported_root_does_not_reach_consent_or_runner() {
    let temp = tempfile::tempdir().expect("tempdir");
    let calls = std::cell::Cell::new(0);
    let input = HookInput {
        cwd: Some(temp.path().to_path_buf()),
        tool_name: None,
        tool_input: Value::Null,
        tool_response: Value::Null,
    };

    let output = evaluate_with_consent_and_runner(
        CodexHookEvent::SessionStart,
        Some(KastHarness::Codex),
        input,
        |_| panic!("unsupported roots must not resolve consent"),
        |_| {
            calls.set(calls.get() + 1);
            Ok(String::new())
        },
    )
    .expect("hook evaluation");

    assert_eq!((calls.get(), output), (0, json!({})));
}

#[test]
fn unresolvable_linked_worktree_metadata_is_typed_and_never_launches() {
    let temp = tempfile::tempdir().expect("tempdir");
    std::fs::write(temp.path().join("settings.gradle.kts"), "").expect("Gradle marker");
    std::fs::write(
        temp.path().join(".git"),
        "gitdir: ../common/.git/worktrees/missing\n",
    )
    .expect("dangling linked-worktree metadata");
    let calls = std::cell::Cell::new(0);
    let input = HookInput {
        cwd: Some(temp.path().to_path_buf()),
        tool_name: None,
        tool_input: Value::Null,
        tool_response: Value::Null,
    };

    let error = evaluate_with_runner(
        CodexHookEvent::SessionStart,
        Some(KastHarness::Codex),
        input,
        |_| {
            calls.set(calls.get() + 1);
            Ok(String::new())
        },
    )
    .expect_err("unresolvable Git metadata must fail closed");

    assert_eq!(error.code, "GIT_WORKTREE_METADATA_UNRESOLVABLE");
    assert_eq!(calls.get(), 0);
}
