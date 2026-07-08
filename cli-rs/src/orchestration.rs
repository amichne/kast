use crate::SCHEMA_VERSION;
use crate::error::{CliError, CliErrorResponse};
use crate::install::{AgentGuidanceSetupPlan, InstallResult};
use crate::runtime::WorkspaceEnsureResult;
use serde::Serialize;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupRuntimeResult {
    #[serde(rename = "type")]
    pub result_type: &'static str,
    pub ok: bool,
    pub stage: SetupRuntimeStage,
    pub dry_run: bool,
    pub setup: AgentGuidanceSetupPlan,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install: Option<InstallResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub runtime: Option<WorkspaceEnsureResult>,
    pub runtime_command: Vec<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub next_actions: Vec<SetupRuntimeNextAction>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub manual_steps: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<CliErrorResponse>,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SetupRuntimeStage {
    Onboarding,
    DryRun,
    SetupDone,
    RuntimeReady,
    RuntimeBlocked,
    RepairRequired,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupRuntimeNextAction {
    pub label: String,
    pub argv: Vec<String>,
    pub reason: String,
    pub destructive: bool,
}

impl SetupRuntimeResult {
    pub fn dry_run(setup: AgentGuidanceSetupPlan, runtime_command: Vec<String>) -> Self {
        let next_actions = vec![SetupRuntimeNextAction {
            label: "Run setup".to_string(),
            argv: runtime_command.clone(),
            reason: "Installs repository agent resources and warms the workspace runtime."
                .to_string(),
            destructive: false,
        }];
        Self {
            result_type: "SETUP_RUNTIME",
            ok: true,
            stage: SetupRuntimeStage::DryRun,
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
            result_type: "SETUP_RUNTIME",
            ok: true,
            stage: SetupRuntimeStage::RuntimeReady,
            dry_run: false,
            setup,
            install: Some(install),
            runtime: Some(runtime),
            runtime_command,
            next_actions: vec![],
            manual_steps: vec![
                "Run typed semantic requests such as `kast agent symbol --query <name> --workspace-root <repo>`."
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
            result_type: "SETUP_RUNTIME",
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
        self.stage = SetupRuntimeStage::Onboarding;
        self
    }

    pub fn with_manual_step(mut self, step: String) -> Self {
        self.manual_steps.push(step);
        self
    }
}

fn is_root_setup_command(command: &[String]) -> bool {
    command.get(1).is_some_and(|arg| arg == "setup")
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

fn failure_stage(code: &'static str) -> SetupRuntimeStage {
    match code {
        "IDEA_PLUGIN_NOT_INSTALLED" => SetupRuntimeStage::SetupDone,
        "IDEA_NOT_RUNNING" | "NO_BACKEND_AVAILABLE" | "RUNTIME_TIMEOUT" | "IDEA_LAUNCH_FAILED" => {
            SetupRuntimeStage::RuntimeBlocked
        }
        _ => SetupRuntimeStage::RepairRequired,
    }
}

fn failure_guidance(
    error: &CliError,
    setup: &AgentGuidanceSetupPlan,
    runtime_command: &[String],
) -> (Vec<SetupRuntimeNextAction>, Vec<String>) {
    if let Some(command) = error.details.get("installCommand") {
        return (
            vec![SetupRuntimeNextAction {
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
            vec![SetupRuntimeNextAction {
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
                SetupRuntimeNextAction {
                    label: "Repair managed install state".to_string(),
                    argv: vec![
                        "kast".to_string(),
                        "repair".to_string(),
                        "--apply".to_string(),
                    ],
                    reason: "Repairs manifest-backed machine state before retrying setup."
                        .to_string(),
                    destructive: false,
                },
                SetupRuntimeNextAction {
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
