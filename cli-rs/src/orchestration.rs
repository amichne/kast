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
    pub dry_run: bool,
    pub setup: AgentGuidanceSetupPlan,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install: Option<InstallResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub runtime: Option<WorkspaceEnsureResult>,
    pub runtime_command: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<CliErrorResponse>,
    pub schema_version: u32,
}

impl AgentUpResult {
    pub fn dry_run(setup: AgentGuidanceSetupPlan, runtime_command: Vec<String>) -> Self {
        Self {
            result_type: "AGENT_UP",
            ok: true,
            dry_run: true,
            setup,
            install: None,
            runtime: None,
            runtime_command,
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
            dry_run: false,
            setup,
            install: Some(install),
            runtime: Some(runtime),
            runtime_command,
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
        Self {
            result_type: "AGENT_UP",
            ok: false,
            dry_run: false,
            setup,
            install,
            runtime,
            runtime_command,
            error: Some(error.to_response()),
            schema_version: SCHEMA_VERSION,
        }
    }
}
