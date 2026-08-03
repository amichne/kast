#[derive(Debug)]
struct AgentHarnessActivation {
    harness: KastHarness,
    plugin_root: PathBuf,
}

#[derive(Debug)]
struct AgentHarnessActivationFailure {
    harness: Option<KastHarness>,
    error: CliError,
}

impl AgentHarnessActivation {
    fn from_environment(
        event: CodexHookEvent,
    ) -> std::result::Result<Option<Self>, AgentHarnessActivationFailure> {
        if !matches!(
            event,
            CodexHookEvent::SessionStart | CodexHookEvent::PreToolUse
        ) {
            return Ok(None);
        }
        let provider = std::env::var_os(AGENT_PROVIDER_ENV).ok_or_else(|| {
            activation_identity_failure(
                None,
                "Kast agent-harness provider identity is required for activation.",
            )
        })?;
        let harness = match provider.to_str() {
            Some("codex") => KastHarness::Codex,
            Some("claude") => KastHarness::Claude,
            Some("copilot") => KastHarness::Copilot,
            Some(provider) => {
                return Err(activation_identity_failure(
                    None,
                    format!("Unknown agent harness `{provider}`."),
                ));
            }
            None => {
                return Err(activation_identity_failure(
                    None,
                    "Kast agent-harness provider identity must be UTF-8.",
                ));
            }
        };
        let plugin_root = std::env::var_os(AGENT_RESOURCE_ROOT_ENV).ok_or_else(|| {
            activation_identity_failure(
                Some(harness),
                "Kast agent-harness resource-root identity is required for activation.",
            )
        })?;
        let plugin_root = PathBuf::from(plugin_root);
        if !plugin_root.is_absolute() {
            return Err(activation_identity_failure(
                Some(harness),
                "Kast agent-harness resource-root identity must be absolute.",
            ));
        }
        Ok(Some(Self {
            harness,
            plugin_root,
        }))
    }

    fn validate(&self) -> Result<()> {
        crate::install::validate_agent_harness_activation(self.harness, &self.plugin_root)
    }
}

fn activation_identity_failure(
    harness: Option<KastHarness>,
    message: impl Into<String>,
) -> AgentHarnessActivationFailure {
    AgentHarnessActivationFailure {
        harness,
        error: CliError::new("KAST_AGENT_RESOURCES_INCOMPATIBLE", message),
    }
}
