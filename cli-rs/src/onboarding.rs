use crate::cli::{AgentUpArgs, BackendName, IdeaPluginInstallArgs, OutputFormat};
use crate::error::{CliError, Result};
use crate::{config, install, self_mgmt};
use dialoguer::{Confirm, Select};
use std::env;
use std::io::{self, IsTerminal};
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AgentUpOnboardingOutcome {
    NotEligible,
    Declined,
    Applied,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct AgentUpOnboardingEligibility {
    stdin_tty: bool,
    stdout_tty: bool,
    human_output: bool,
    dry_run: bool,
    no_onboard: bool,
    ci: bool,
    dumb_terminal: bool,
    config_allows: bool,
    homebrew_idea_plugin_installable: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AgentUpOnboardingScope {
    Global,
    Repository,
}

impl AgentUpOnboardingScope {
    fn label(self) -> &'static str {
        match self {
            Self::Global => "global",
            Self::Repository => "repository-scoped",
        }
    }
}

impl AgentUpOnboardingEligibility {
    fn allows_prompt(self) -> bool {
        self.stdin_tty
            && self.stdout_tty
            && self.human_output
            && !self.dry_run
            && !self.no_onboard
            && !self.ci
            && !self.dumb_terminal
            && self.config_allows
            && self.homebrew_idea_plugin_installable
    }
}

pub fn maybe_run_agent_up_onboarding(
    args: &mut AgentUpArgs,
    output_format: OutputFormat,
    workspace_root: &Path,
) -> Result<AgentUpOnboardingOutcome> {
    let config = config::KastConfig::load(workspace_root)?;
    let eligibility = AgentUpOnboardingEligibility {
        stdin_tty: io::stdin().is_terminal(),
        stdout_tty: io::stdout().is_terminal(),
        human_output: output_format == OutputFormat::Human,
        dry_run: args.dry_run,
        no_onboard: args.no_onboard,
        ci: env_flag("CI"),
        dumb_terminal: env::var("TERM").is_ok_and(|term| term.eq_ignore_ascii_case("dumb")),
        config_allows: config.can_run_agent_up_onboarding(),
        homebrew_idea_plugin_installable: true,
    };
    if !eligibility.allows_prompt() {
        return Ok(AgentUpOnboardingOutcome::NotEligible);
    }
    let eligibility = AgentUpOnboardingEligibility {
        homebrew_idea_plugin_installable: install::current_cli_can_install_homebrew_idea_plugin(),
        ..eligibility
    };
    if !eligibility.allows_prompt() {
        return Ok(AgentUpOnboardingOutcome::NotEligible);
    }

    eprintln!();
    eprintln!("Kast can configure IDEA-backed agent workflows for this repository.");
    eprintln!("It can install or refresh the JetBrains plugin, save IDEA defaults,");
    eprintln!("prepare repository agent resources, and warm the runtime.");
    eprintln!();

    let accepted = Confirm::new()
        .with_prompt("Use automatic IDEA setup now?")
        .default(true)
        .interact()
        .map_err(|error| CliError::new("PROMPT_FAILED", error.to_string()))?;

    if !accepted {
        mark_agent_up_onboarding_completed()?;
        return Ok(AgentUpOnboardingOutcome::Declined);
    }

    let scope = prompt_onboarding_scope()?;
    let mut reporter = install::HumanInstallReporter::new();
    install::install(
        crate::cli::InstallArgs {
            command: crate::cli::InstallCommand::Plugin(IdeaPluginInstallArgs {
                jetbrains_config_root: None,
                link_jetbrains_profiles: false,
                cask_token: None,
                force: args.force,
                dry_run: false,
            }),
        },
        &mut reporter,
    )?;
    apply_agent_up_onboarding_config(scope, workspace_root)?;
    prepare_current_invocation_for_idea(args);
    eprintln!();
    eprintln!(
        "Kast onboarding configured {} IDEA agent defaults for {}.",
        scope.label(),
        workspace_root.display()
    );
    Ok(AgentUpOnboardingOutcome::Applied)
}

fn prompt_onboarding_scope() -> Result<AgentUpOnboardingScope> {
    let items = [
        "Global machine defaults - use IDEA-backed agents for all repositories",
        "This repository only - save IDEA defaults for this workspace",
    ];
    let selected = Select::new()
        .with_prompt("Where should Kast save the automatic defaults?")
        .items(items)
        .default(0)
        .interact()
        .map_err(|error| CliError::new("PROMPT_FAILED", error.to_string()))?;
    Ok(match selected {
        0 => AgentUpOnboardingScope::Global,
        1 => AgentUpOnboardingScope::Repository,
        _ => AgentUpOnboardingScope::Global,
    })
}

fn env_flag(name: &str) -> bool {
    env::var(name)
        .ok()
        .is_some_and(|value| !value.trim().is_empty() && value != "0")
}

fn prepare_current_invocation_for_idea(args: &mut AgentUpArgs) {
    if args.runtime.backend_name.is_none() {
        args.runtime.backend_name = Some(BackendName::Idea);
    }
}

fn mark_agent_up_onboarding_completed() -> Result<()> {
    self_mgmt::update_global_config(|document| {
        table(document, "onboarding")?.insert("agentUpCompleted".to_string(), true.into());
        Ok(())
    })?;
    Ok(())
}

fn apply_agent_up_onboarding_config(
    scope: AgentUpOnboardingScope,
    workspace_root: &Path,
) -> Result<()> {
    match scope {
        AgentUpOnboardingScope::Global => self_mgmt::update_global_config(|document| {
            write_agent_up_onboarding_defaults(document)
        })?,
        AgentUpOnboardingScope::Repository => {
            self_mgmt::update_workspace_config(workspace_root, |document| {
                write_agent_up_onboarding_defaults(document)
            })?
        }
    };
    Ok(())
}

fn write_agent_up_onboarding_defaults(document: &mut toml::Table) -> Result<()> {
    self_mgmt::write_developer_machine_idea_defaults(document)?;

    let project_open = table(document, "projectOpen")?;
    project_open.insert("profileAutoInit".to_string(), true.into());

    table(document, "onboarding")?.insert("agentUpCompleted".to_string(), true.into());
    Ok(())
}

fn table<'a>(document: &'a mut toml::Table, key: &str) -> Result<&'a mut toml::Table> {
    document
        .entry(key.to_string())
        .or_insert_with(|| toml::Value::Table(toml::Table::new()))
        .as_table_mut()
        .ok_or_else(|| {
            CliError::new(
                "CONFIG_ERROR",
                format!("Cannot write onboarding config because `{key}` is not a TOML table."),
            )
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn eligible() -> AgentUpOnboardingEligibility {
        AgentUpOnboardingEligibility {
            stdin_tty: true,
            stdout_tty: true,
            human_output: true,
            dry_run: false,
            no_onboard: false,
            ci: false,
            dumb_terminal: false,
            config_allows: true,
            homebrew_idea_plugin_installable: true,
        }
    }

    #[test]
    fn onboarding_requires_interactive_human_terminal() {
        assert!(eligible().allows_prompt());
        assert!(
            !AgentUpOnboardingEligibility {
                stdin_tty: false,
                ..eligible()
            }
            .allows_prompt()
        );
        assert!(
            !AgentUpOnboardingEligibility {
                stdout_tty: false,
                ..eligible()
            }
            .allows_prompt()
        );
        assert!(
            !AgentUpOnboardingEligibility {
                human_output: false,
                ..eligible()
            }
            .allows_prompt()
        );
    }

    #[test]
    fn onboarding_skips_explicit_noninteractive_modes() {
        for eligibility in [
            AgentUpOnboardingEligibility {
                dry_run: true,
                ..eligible()
            },
            AgentUpOnboardingEligibility {
                no_onboard: true,
                ..eligible()
            },
            AgentUpOnboardingEligibility {
                ci: true,
                ..eligible()
            },
            AgentUpOnboardingEligibility {
                dumb_terminal: true,
                ..eligible()
            },
            AgentUpOnboardingEligibility {
                config_allows: false,
                ..eligible()
            },
            AgentUpOnboardingEligibility {
                homebrew_idea_plugin_installable: false,
                ..eligible()
            },
        ] {
            assert!(!eligibility.allows_prompt(), "{eligibility:?}");
        }
    }

    #[test]
    fn onboarding_scope_labels_are_human_readable() {
        assert_eq!(AgentUpOnboardingScope::Global.label(), "global");
        assert_eq!(
            AgentUpOnboardingScope::Repository.label(),
            "repository-scoped"
        );
    }

    #[test]
    fn onboarding_defaults_configure_idea_agent_flow() {
        let mut document = toml::Table::new();

        write_agent_up_onboarding_defaults(&mut document).expect("defaults");

        assert_eq!(
            document
                .get("runtime")
                .and_then(toml::Value::as_table)
                .and_then(|runtime| runtime.get("defaultBackend"))
                .and_then(toml::Value::as_str),
            Some("idea")
        );
        let idea_launch = document
            .get("runtime")
            .and_then(toml::Value::as_table)
            .and_then(|runtime| runtime.get("ideaLaunch"))
            .and_then(toml::Value::as_table)
            .expect("idea launch");
        assert_eq!(
            idea_launch.get("enabled").and_then(toml::Value::as_bool),
            Some(true)
        );
        assert_eq!(
            idea_launch.get("command").and_then(toml::Value::as_str),
            Some("idea")
        );
        assert_eq!(
            idea_launch
                .get("requireInstalledPlugin")
                .and_then(toml::Value::as_bool),
            Some(true)
        );
        let project_open = document
            .get("projectOpen")
            .and_then(toml::Value::as_table)
            .expect("project open");
        assert!(
            !project_open.contains_key("agentHarness"),
            "onboarding must not force a harness-specific setup path"
        );
        assert_eq!(
            project_open
                .get("profileAutoInit")
                .and_then(toml::Value::as_bool),
            Some(true)
        );
        assert_eq!(
            document
                .get("onboarding")
                .and_then(toml::Value::as_table)
                .and_then(|onboarding| onboarding.get("agentUpCompleted"))
                .and_then(toml::Value::as_bool),
            Some(true)
        );
    }
}
