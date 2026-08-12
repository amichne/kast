/// Selects whether existing provider resources are reconciled or replaced.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AgentResourceInstallMode {
    Reconcile,
    Replace,
}

impl AgentResourceInstallMode {
    pub fn from_force_flag(force: bool) -> Self {
        if force { Self::Replace } else { Self::Reconcile }
    }
}

pub fn install_agent_resources(
    requested: &[KastHarness],
    mode: AgentResourceInstallMode,
) -> Result<()> {
    let selected = [KastHarness::Codex, KastHarness::Claude, KastHarness::Copilot]
        .into_iter()
        .filter(|harness| requested.contains(harness))
        .collect::<Vec<_>>();
    let mut failures = Vec::new();
    for harness in selected {
        if let Err(message) = install_agent_harness(harness, mode) {
            failures.push(format!("{}: {message}", harness_name(harness)));
        }
    }
    if failures.is_empty() {
        return Ok(());
    }
    Err(CliError::new(
        "KAST_AGENT_RESOURCE_INSTALL_FAILED",
        format!(
            "Agent resources could not be installed for {}.",
            failures.join("; ")
        ),
    ))
}

fn install_agent_harness(
    harness: KastHarness,
    mode: AgentResourceInstallMode,
) -> std::result::Result<(), String> {
    let marketplace_root = materialize_agent_harness(harness).map_err(|error| error.to_string())?;
    let root = marketplace_root.display().to_string();
    if mode == AgentResourceInstallMode::Replace {
        for args in agent_harness_cleanup_commands(harness) {
            let _ = run_agent_harness_command(harness, &args);
        }
    }
    let commands = match harness {
        KastHarness::Codex => vec![
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "add".to_string(),
                root,
                "--json".to_string(),
            ],
            vec![
                "plugin".to_string(),
                "add".to_string(),
                "kast@kast".to_string(),
                "--json".to_string(),
            ],
        ],
        KastHarness::Claude => vec![
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "add".to_string(),
                root,
                "--scope".to_string(),
                "user".to_string(),
            ],
            vec![
                "plugin".to_string(),
                "install".to_string(),
                "kast@kast".to_string(),
                "--scope".to_string(),
                "user".to_string(),
            ],
        ],
        KastHarness::Copilot => vec![
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "add".to_string(),
                root,
            ],
            vec![
                "plugin".to_string(),
                "install".to_string(),
                "kast@kast".to_string(),
            ],
        ],
    };
    for args in commands {
        run_agent_harness_command(harness, &args)?;
    }
    Ok(())
}

fn agent_harness_cleanup_commands(harness: KastHarness) -> Vec<Vec<String>> {
    match harness {
        KastHarness::Codex => vec![
            vec![
                "plugin".to_string(),
                "remove".to_string(),
                "kast@kast".to_string(),
                "--json".to_string(),
            ],
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "remove".to_string(),
                "kast".to_string(),
                "--json".to_string(),
            ],
        ],
        KastHarness::Claude => vec![
            vec![
                "plugin".to_string(),
                "uninstall".to_string(),
                "kast@kast".to_string(),
                "--scope".to_string(),
                "user".to_string(),
            ],
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "remove".to_string(),
                "kast".to_string(),
            ],
        ],
        KastHarness::Copilot => vec![
            vec![
                "plugin".to_string(),
                "uninstall".to_string(),
                "kast@kast".to_string(),
            ],
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "remove".to_string(),
                "kast".to_string(),
                "--force".to_string(),
            ],
        ],
    }
}
