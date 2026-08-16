const AGENT_HOOK_COMMAND_TIMEOUT_MILLIS: u64 = 25_000;
const AGENT_HOOK_RUNTIME_START_TIMEOUT_MILLIS: u64 = 20_000;
const AGENT_HOOK_COMMAND_TIMEOUT: std::time::Duration =
    std::time::Duration::from_millis(AGENT_HOOK_COMMAND_TIMEOUT_MILLIS);
const AGENT_HOOK_POLL_INTERVAL: std::time::Duration = std::time::Duration::from_millis(10);
const _: () = assert!(AGENT_HOOK_RUNTIME_START_TIMEOUT_MILLIS < AGENT_HOOK_COMMAND_TIMEOUT_MILLIS);

fn session_start_with_consent_and_runner(
    harness: KastHarness,
    workspace: &Path,
    consent: IndexerAutoStartConsent,
    runner: impl FnOnce(&[OsString]) -> Result<String>,
) -> Value {
    session_start_with_consent_at_and_runner(
        harness,
        workspace,
        consent,
        std::time::SystemTime::now(),
        runner,
    )
}

fn session_start_with_consent_at_and_runner(
    harness: KastHarness,
    workspace: &Path,
    consent: IndexerAutoStartConsent,
    hook_started_at: std::time::SystemTime,
    runner: impl FnOnce(&[OsString]) -> Result<String>,
) -> Value {
    match consent {
        IndexerAutoStartConsent::Unconfigured => agent_context(
            Some(harness),
            CodexHookEvent::SessionStart,
            format!(
                "Kast indexer auto-start needs explicit consent for exact workspace `{}`. Ask the user whether to enable it. Do not start Kast or write configuration before approval. After approval, invoke `/kast:developer`, set `codex.hooks.autoStartIndexer` to `true` for this workspace, and start its admitted background runtime. Set the field to `false` if declined.",
                workspace.display()
            ),
        ),
        IndexerAutoStartConsent::Disabled => json!({}),
        IndexerAutoStartConsent::Enabled => {
            let deadline = match hook_runtime_start_deadline(hook_started_at) {
                Ok(deadline) => deadline,
                Err(error) => {
                    return agent_context(
                        Some(harness),
                        CodexHookEvent::SessionStart,
                        advisory_result("Kast session launch", Err(error)),
                    );
                }
            };
            let args = [
                OsString::from("--output"),
                OsString::from("json"),
                OsString::from("developer"),
                OsString::from("runtime"),
                OsString::from("start-background"),
                OsString::from("--wait-timeout-ms"),
                OsString::from(AGENT_HOOK_RUNTIME_START_TIMEOUT_MILLIS.to_string()),
                OsString::from("--start-deadline-unix-epoch-millis"),
                OsString::from(deadline.to_string()),
                OsString::from("--workspace-root"),
                workspace.as_os_str().to_os_string(),
                OsString::from("--accept-indexing"),
            ];
            match runner(&args) {
                Ok(_) => json!({}),
                Err(error) => agent_context(
                    Some(harness),
                    CodexHookEvent::SessionStart,
                    advisory_result("Kast session launch", Err(error)),
                ),
            }
        }
    }
}

fn hook_runtime_start_deadline(
    hook_started_at: std::time::SystemTime,
) -> Result<RuntimeStartDeadlineUnixEpochMillis> {
    let deadline = hook_started_at
        .checked_add(std::time::Duration::from_millis(
            AGENT_HOOK_RUNTIME_START_TIMEOUT_MILLIS,
        ))
        .ok_or_else(|| {
            CliError::new(
                "AGENT_HOOK_DEADLINE_INVALID",
                "The hook start deadline exceeds the system clock representation.",
            )
        })?;
    let deadline_millis = deadline
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|error| {
            CliError::new(
                "AGENT_HOOK_DEADLINE_INVALID",
                format!("The hook start deadline precedes the Unix epoch: {error}"),
            )
        })?
        .as_millis();
    let deadline_millis = u64::try_from(deadline_millis).map_err(|_| {
        CliError::new(
            "AGENT_HOOK_DEADLINE_INVALID",
            "The hook start deadline exceeds the CLI representation.",
        )
    })?;
    Ok(RuntimeStartDeadlineUnixEpochMillis::new(deadline_millis))
}

fn run_kast(args: &[OsString]) -> Result<String> {
    let binary = std::env::current_exe()?;
    let output = run_command_bounded(&binary, args, AGENT_HOOK_COMMAND_TIMEOUT)?;
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if output.status.success() {
        return Ok(stdout);
    }
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    let message = if stderr.is_empty() { stdout } else { stderr };
    let mut error = CliError::new(
        "CODEX_HOOK_COMMAND_FAILED",
        format!(
            "{} exited with {}: {message}",
            binary.display(),
            output.status
        ),
    );
    error.details.insert(
        "command".to_string(),
        args.iter()
            .map(|argument| argument.to_string_lossy())
            .collect::<Vec<_>>()
            .join(" "),
    );
    Err(error)
}

fn run_command_bounded(
    binary: &Path,
    args: &[OsString],
    timeout: std::time::Duration,
) -> Result<Output> {
    let started = std::time::Instant::now();
    run_command_bounded_with_wait(
        binary,
        args,
        timeout,
        || started.elapsed(),
        std::thread::sleep,
    )
}

fn run_command_bounded_with_wait(
    binary: &Path,
    args: &[OsString],
    timeout: std::time::Duration,
    elapsed: impl Fn() -> std::time::Duration,
    pause: impl Fn(std::time::Duration),
) -> Result<Output> {
    let mut child = Command::new(binary)
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    loop {
        if let Some(status) = child.try_wait()? {
            let mut stdout = Vec::new();
            let mut stderr = Vec::new();
            if let Some(mut pipe) = child.stdout.take() {
                pipe.read_to_end(&mut stdout)?;
            }
            if let Some(mut pipe) = child.stderr.take() {
                pipe.read_to_end(&mut stderr)?;
            }
            return Ok(Output {
                status,
                stdout,
                stderr,
            });
        }
        if elapsed() >= timeout {
            child.kill()?;
            child.wait()?;
            let mut error = CliError::new(
                "AGENT_HOOK_COMMAND_TIMEOUT",
                format!(
                    "Kast stopped the hook control command after {} milliseconds.",
                    timeout.as_millis()
                ),
            );
            error
                .details
                .insert("binary".to_string(), binary.display().to_string());
            return Err(error);
        }
        pause(AGENT_HOOK_POLL_INTERVAL);
    }
}
