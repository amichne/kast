use crate::SCHEMA_VERSION;
use crate::error::{CliError, CliErrorResponse};
use crate::install::{AgentGuidanceSetupPlan, InstallResult};
use crate::runtime::WorkspaceEnsureResult;
use serde::Serialize;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentUpResult {
    #[serde(rename = "type")]
    pub result_type: &'static str,
    pub ok: bool,
    pub stage: AgentUpStage,
    pub dry_run: bool,
    pub setup: AgentGuidanceSetupPlan,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install: Option<InstallResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub runtime: Option<WorkspaceEnsureResult>,
    pub runtime_command: Vec<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub next_actions: Vec<AgentUpNextAction>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub manual_steps: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<CliErrorResponse>,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentUpStage {
    Onboarding,
    DryRun,
    SetupDone,
    RuntimeReady,
    RuntimeBlocked,
    RepairRequired,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentUpNextAction {
    pub label: String,
    pub argv: Vec<String>,
    pub reason: String,
    pub destructive: bool,
}

impl AgentUpResult {
    pub fn dry_run(setup: AgentGuidanceSetupPlan, runtime_command: Vec<String>) -> Self {
        let next_actions = if is_root_setup_command(&runtime_command) {
            vec![AgentUpNextAction {
                label: "Run setup".to_string(),
                argv: runtime_command.clone(),
                reason: "Installs repository agent resources and warms the workspace runtime."
                    .to_string(),
                destructive: false,
            }]
        } else {
            vec![
                AgentUpNextAction {
                    label: "Run repository bring-up".to_string(),
                    argv: runtime_command_for_agent_up(&setup.install_command, &runtime_command),
                    reason: "Installs the selected agent resource and warms the workspace runtime."
                        .to_string(),
                    destructive: false,
                },
                AgentUpNextAction {
                    label: "Install only the selected agent resource".to_string(),
                    argv: setup.install_command.clone(),
                    reason: "Use this when runtime warmup should happen separately.".to_string(),
                    destructive: false,
                },
            ]
        };
        Self {
            result_type: "AGENT_UP",
            ok: true,
            stage: AgentUpStage::DryRun,
            dry_run: true,
            setup,
            install: None,
            runtime: None,
            runtime_command,
            next_actions,
            manual_steps: vec![],
            error: None,
            schema_version: SCHEMA_VERSION,
        }
    }

    pub fn success(
        setup: AgentGuidanceSetupPlan,
        install: InstallResult,
        runtime: WorkspaceEnsureResult,
        runtime_command: Vec<String>,
    ) -> Self {
        Self {
            result_type: "AGENT_UP",
            ok: true,
            stage: AgentUpStage::RuntimeReady,
            dry_run: false,
            setup,
            install: Some(install),
            runtime: Some(runtime),
            runtime_command,
            next_actions: vec![],
            manual_steps: vec![
                "Run semantic requests with `kast agent call <method> --workspace-root <repo>`."
                    .to_string(),
            ],
            error: None,
            schema_version: SCHEMA_VERSION,
        }
    }

    pub fn failure(
        setup: AgentGuidanceSetupPlan,
        install: Option<InstallResult>,
        runtime: Option<WorkspaceEnsureResult>,
        runtime_command: Vec<String>,
        error: CliError,
    ) -> Self {
        let stage = failure_stage(error.code);
        let (next_actions, manual_steps) = failure_guidance(&error, &setup, &runtime_command);
        Self {
            result_type: "AGENT_UP",
            ok: false,
            stage,
            dry_run: false,
            setup,
            install,
            runtime,
            runtime_command,
            next_actions,
            manual_steps,
            error: Some(error.to_response()),
            schema_version: SCHEMA_VERSION,
        }
    }

    pub fn with_onboarding_stage(mut self) -> Self {
        self.stage = AgentUpStage::Onboarding;
        self
    }

    pub fn with_manual_step(mut self, step: String) -> Self {
        self.manual_steps.push(step);
        self
    }
}

fn runtime_command_for_agent_up(
    install_command: &[String],
    runtime_command: &[String],
) -> Vec<String> {
    let executable = install_command
        .first()
        .or_else(|| runtime_command.first())
        .cloned()
        .unwrap_or_else(|| "kast".to_string());
    let mut command = vec![executable, "agent".to_string(), "up".to_string()];
    append_setup_args(&mut command, install_command);
    append_workspace_and_backend_args(&mut command, runtime_command);
    command
}

fn is_root_setup_command(command: &[String]) -> bool {
    command.get(1).is_some_and(|arg| arg == "setup")
}

fn append_setup_args(command: &mut Vec<String>, install_command: &[String]) {
    let mut index = 0;
    while index < install_command.len() {
        match install_command[index].as_str() {
            "--agents-md" => {
                if let Some(value) = install_command.get(index + 1) {
                    command.push("--agents-md".to_string());
                    command.push(value.clone());
                    index += 2;
                    continue;
                }
            }
            "--force" | "--no-auto-exclude-git" => {
                command.push(install_command[index].clone());
            }
            _ => {}
        }
        index += 1;
    }
}

fn append_workspace_and_backend_args(command: &mut Vec<String>, runtime_command: &[String]) {
    let mut index = 0;
    while index < runtime_command.len() {
        let arg = &runtime_command[index];
        if matches!(arg.as_str(), "--workspace-root" | "--backend")
            && let Some(value) = runtime_command.get(index + 1)
        {
            command.push(arg.clone());
            command.push(value.clone());
            index += 2;
            continue;
        }
        index += 1;
    }
}

fn failure_stage(code: &'static str) -> AgentUpStage {
    match code {
        "IDEA_PLUGIN_NOT_INSTALLED" => AgentUpStage::SetupDone,
        "IDEA_NOT_RUNNING" | "NO_BACKEND_AVAILABLE" | "RUNTIME_TIMEOUT" | "IDEA_LAUNCH_FAILED" => {
            AgentUpStage::RuntimeBlocked
        }
        _ => AgentUpStage::RepairRequired,
    }
}

fn failure_guidance(
    error: &CliError,
    setup: &AgentGuidanceSetupPlan,
    runtime_command: &[String],
) -> (Vec<AgentUpNextAction>, Vec<String>) {
    if let Some(command) = error.details.get("installCommand") {
        return (
            vec![AgentUpNextAction {
                label: "Install or repair the IDEA plugin".to_string(),
                argv: command.split_whitespace().map(str::to_string).collect(),
                reason:
                    "The IDEA backend cannot launch until a JetBrains profile has the Kast plugin."
                        .to_string(),
                destructive: false,
            }],
            vec![],
        );
    }
    match error.code {
        "IDEA_NOT_RUNNING" | "NO_BACKEND_AVAILABLE" | "RUNTIME_TIMEOUT" | "IDEA_LAUNCH_FAILED" => (
            vec![AgentUpNextAction {
                label: "Check runtime status".to_string(),
                argv: runtime_status_command(runtime_command),
                reason: "Shows whether IDEA registered a Kast backend for this workspace."
                    .to_string(),
                destructive: false,
            }],
            vec![
                "Open this repository in IntelliJ IDEA or Android Studio with the Kast plugin installed, then rerun the runtime command.".to_string(),
            ],
        ),
        _ => (
            vec![
                AgentUpNextAction {
                    label: "Repair managed install state".to_string(),
                    argv: vec!["kast".to_string(), "ready".to_string(), "--fix".to_string()],
                    reason: "Repairs manifest-backed machine state before retrying agent bring-up."
                        .to_string(),
                    destructive: false,
                },
                AgentUpNextAction {
                    label: "Refresh selected agent resource".to_string(),
                    argv: forced_setup_command(setup),
                    reason: "Rewrites the selected repository agent resource from the active binary."
                        .to_string(),
                    destructive: false,
                },
            ],
            vec![],
        ),
    }
}

fn forced_setup_command(setup: &AgentGuidanceSetupPlan) -> Vec<String> {
    let mut argv = setup.install_command.clone();
    if !argv.iter().any(|arg| arg == "--force" || arg == "-f") {
        argv.push("--force".to_string());
    }
    argv
}

fn runtime_status_command(runtime_command: &[String]) -> Vec<String> {
    if is_root_setup_command(runtime_command) {
        let executable = runtime_command
            .first()
            .cloned()
            .unwrap_or_else(|| "kast".to_string());
        let mut command = vec![executable, "status".to_string()];
        append_workspace_and_backend_args(&mut command, runtime_command);
        return command;
    }
    let mut command = runtime_command.to_vec();
    if command.len() >= 3 {
        command[2] = "status".to_string();
    }
    command
}
