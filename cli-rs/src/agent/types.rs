#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentEnvelope {
    pub ok: bool,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub response: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub raw_response: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<AgentError>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentError {
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub details: BTreeMap<String, Value>,
}

struct AgentRequest {
    method: String,
    request: Value,
    runtime: AgentRuntimeArgs,
    full_response: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentWorkflowSummary {
    #[serde(rename = "type")]
    summary_type: &'static str,
    ok: bool,
    workflow: String,
    workspace_root: String,
    out_dir: String,
    dry_run: bool,
    steps: Vec<AgentWorkflowStepSummary>,
    issues: Vec<AgentWorkflowIssue>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentWorkflowStepSummary {
    name: String,
    method: String,
    params_file: String,
    stdout: String,
    stderr: String,
    exit_code: i32,
    summary: Value,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentWorkflowIssue {
    code: String,
    message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    step: Option<String>,
}

struct AgentWorkflowStep {
    name: &'static str,
    method: &'static str,
    params: Value,
    mutates: bool,
    action: AgentWorkflowStepAction,
}

#[derive(Debug, Clone)]
enum AgentWorkflowStepAction {
    Catalog,
    PackageVerify(AgentPackageVerifyOptions),
}

#[derive(Debug, Clone)]
struct AgentPackageVerifyOptions {
    require_copilot: bool,
    require_skill: bool,
    require_instructions: bool,
    copilot_target_dir: Option<PathBuf>,
    skill_target_dirs: Vec<PathBuf>,
    instructions_target_dirs: Vec<PathBuf>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentPackageRequiredResources {
    ok: bool,
    workspace_root: String,
    copilot_package: AgentPackageResourceGroup,
    skills: AgentPackageResourceGroup,
    instructions: AgentPackageResourceGroup,
    issues: Vec<AgentPackageResourceIssue>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentPackageResourceGroup {
    required: bool,
    mode: &'static str,
    targets: Vec<AgentPackageResourceTarget>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentPackageResourceTarget {
    kind: self_mgmt::ManagedResourceKind,
    target_path: String,
    exists: bool,
    current: bool,
    version_matches_current: bool,
    manifest_resource: Option<self_mgmt::ManagedRepoResource>,
    output_issues: Vec<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentPackageResourceIssue {
    code: String,
    message: String,
    kind: self_mgmt::ManagedResourceKind,
    target_paths: Vec<String>,
    recovery_argv: Vec<String>,
}

impl AgentWorkflowStep {
    fn catalog(name: &'static str, method: &'static str, params: Value, mutates: bool) -> Self {
        Self {
            name,
            method,
            params,
            mutates,
            action: AgentWorkflowStepAction::Catalog,
        }
    }

    fn package_verify(options: AgentPackageVerifyOptions) -> Self {
        Self {
            name: "ready",
            method: "package/verify",
            params: options.params(),
            mutates: false,
            action: AgentWorkflowStepAction::PackageVerify(options),
        }
    }
}

impl AgentPackageVerifyOptions {
    fn from_args(args: &AgentWorkflowPackageVerifyArgs) -> Self {
        Self {
            require_copilot: args.require_copilot,
            require_skill: args.require_skill,
            require_instructions: args.require_instructions,
            copilot_target_dir: args.copilot_target_dir.clone(),
            skill_target_dirs: args.skill_target_dir.clone(),
            instructions_target_dirs: args.instructions_target_dir.clone(),
        }
    }

    fn params(&self) -> Value {
        json!({
            "requireCopilot": self.require_copilot,
            "requireSkill": self.require_skill,
            "requireInstructions": self.require_instructions,
            "copilotTargetDir": self.copilot_target_dir.as_ref().map(|path| config::normalize(path.clone()).display().to_string()),
            "skillTargetDirs": path_values(&self.skill_target_dirs),
            "instructionsTargetDirs": path_values(&self.instructions_target_dirs),
        })
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentToolsResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    catalog_sha256: String,
    tool_count: usize,
    invocation: AgentToolInvocation,
    tools: Vec<AgentToolSpec>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentToolsListResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    catalog_sha256: String,
    count: usize,
    invocation: AgentToolInvocation,
    tools: Vec<AgentToolRow>,
    help: Vec<String>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentToolInvocation {
    command: &'static str,
    argv: Vec<String>,
    method_argument: &'static str,
    params_file_flag: &'static str,
    workspace_root_flag: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentToolSpec {
    name: String,
    method: String,
    category: String,
    description: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    default_args: Option<Value>,
    parameters: Value,
    mutates: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentToolRow {
    name: String,
    method: String,
    category: String,
    mutates: bool,
}
