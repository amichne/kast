use std::io::{IsTerminal as AgentTaskIsTerminal, Write as AgentTaskWrite};

const AGENT_TASK_SCHEMA_VERSION: u32 = 1;
const AGENT_TASK_RECEIPT_TYPE: &str = "KAST_AGENT_TASK";
const AGENT_TASK_HOME_TYPE: &str = "KAST_AGENT_HOME";
const AGENT_TASK_MODEL_SCHEMA_VERSION: u32 = 1;
const AGENT_TASK_GRADLE_RECEIPT_SCHEMA_VERSION: u32 = 1;
const AGENT_TASK_INIT_SCRIPT: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/agent-task/gradle-receipt.init.gradle"
));

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum AgentTaskState {
    Active,
    Validating,
    Complete,
    Blocked,
    Aborted,
}

impl AgentTaskState {
    fn owns_lease(self) -> bool {
        matches!(self, Self::Active | Self::Validating | Self::Blocked)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "kind",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum AgentTaskOwnerIdentity {
    Session {
        provider: String,
        session_sha256: String,
    },
    Process {
        pid: u64,
        started_at: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGenerationIdentity {
    authority: String,
    generation: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskLeaseIdentity {
    lease_id: String,
    owner: AgentTaskOwnerIdentity,
    acquired_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum AgentTaskContentIdentity {
    Present { sha256: String },
    Deleted,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskSnapshot {
    #[serde(skip_serializing_if = "Option::is_none")]
    git_head: Option<String>,
    files: BTreeMap<String, AgentTaskContentIdentity>,
    sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskDiagnosticsProof {
    files: BTreeMap<String, String>,
    error_count: usize,
    semantic_outcome: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentTaskGradleOutcome {
    Success,
    UpToDate,
    FromCache,
    NoSource,
    Skipped,
    Failed,
}

impl AgentTaskGradleOutcome {
    fn is_valid_proof(self) -> bool {
        matches!(self, Self::Success | Self::UpToDate | Self::FromCache)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleTaskProof {
    path: String,
    outcome: AgentTaskGradleOutcome,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleInvocationProof {
    build_root: String,
    input_sha256: String,
    build_tasks: Vec<String>,
    test_tasks: Vec<String>,
    observed_tasks: Vec<AgentTaskGradleTaskProof>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskTestReportProof {
    path: String,
    sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskValidationIdentity {
    policy_sha256: String,
    plan: Vec<AgentTaskValidationTarget>,
    input_sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentTaskBlocker {
    pub(crate) code: String,
    pub(crate) message: String,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub(crate) details: BTreeMap<String, String>,
}

impl AgentTaskBlocker {
    fn new(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            details: BTreeMap::new(),
        }
    }

    fn detail(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.details.insert(key.into(), value.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "kind",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum AgentTaskCompletionProof {
    NoRelevantChanges { input_sha256: String },
    Validated { input_sha256: String },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentTaskReceipt {
    #[serde(rename = "type")]
    receipt_type: String,
    pub(crate) state: AgentTaskState,
    pub(crate) task_id: String,
    owner: AgentTaskOwnerIdentity,
    workspace_root: String,
    generation: AgentTaskGenerationIdentity,
    lease: AgentTaskLeaseIdentity,
    baseline: AgentTaskSnapshot,
    current: AgentTaskSnapshot,
    gradle_model: AgentTaskGradleModel,
    gradle_model_sha256: String,
    diagnostics: Vec<AgentTaskDiagnosticsProof>,
    gradle: Vec<AgentTaskGradleInvocationProof>,
    test_reports: Vec<AgentTaskTestReportProof>,
    validation: Option<AgentTaskValidationIdentity>,
    pub(crate) blockers: Vec<AgentTaskBlocker>,
    #[serde(skip_serializing_if = "Option::is_none")]
    completion: Option<AgentTaskCompletionProof>,
    started_at: String,
    updated_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    finished_at: Option<String>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTaskHome {
    #[serde(rename = "type")]
    home_type: &'static str,
    workspace_root: String,
    readiness: AgentTaskHomeReadiness,
    #[serde(skip_serializing_if = "Option::is_none")]
    active_task: Option<AgentTaskHomeTask>,
    next_commands: Vec<String>,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTaskHomeTask {
    task_id: String,
    state: AgentTaskState,
    workspace_root: String,
    generation: AgentTaskGenerationIdentity,
    current_sha256: String,
    blocker_codes: Vec<String>,
}

impl From<&AgentTaskReceipt> for AgentTaskHomeTask {
    fn from(receipt: &AgentTaskReceipt) -> Self {
        Self {
            task_id: receipt.task_id.clone(),
            state: receipt.state,
            workspace_root: receipt.workspace_root.clone(),
            generation: receipt.generation.clone(),
            current_sha256: receipt.current.sha256.clone(),
            blocker_codes: receipt
                .blockers
                .iter()
                .map(|blocker| blocker.code.clone())
                .collect(),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentTaskHomeReadiness {
    state: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    generation: Option<AgentTaskGenerationIdentity>,
    #[serde(skip_serializing_if = "Option::is_none")]
    blocker: Option<AgentTaskBlocker>,
}

struct AgentTaskExecution {
    receipt: AgentTaskReceipt,
    ok: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AgentTaskHookOperation {
    Begin,
    Status,
    Finish,
    Abort,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AgentTaskHookResult {
    pub(crate) ok: bool,
    pub(crate) receipt: AgentTaskReceipt,
}

impl AgentTaskHookResult {
    pub(crate) fn state(&self) -> AgentTaskState {
        self.receipt.state
    }

    pub(crate) fn task_id(&self) -> &str {
        &self.receipt.task_id
    }

    pub(crate) fn blocker(&self) -> Option<&AgentTaskBlocker> {
        self.receipt.blockers.first()
    }

    pub(crate) fn to_toon(&self) -> Result<String> {
        toon_format::encode_default(self).map_err(|error| {
            CliError::new(
                "AGENT_TASK_OUTPUT_INVALID",
                format!("Cannot encode agent task hook receipt as TOON: {error}"),
            )
        })
    }
}

struct AgentTaskPaths {
    directory: PathBuf,
    receipt: PathBuf,
    lock: PathBuf,
    init_script: PathBuf,
}

impl AgentTaskPaths {
    fn resolve(workspace_root: &Path) -> Result<Self> {
        let directory =
            crate::config::workspace_data_directory(workspace_root)?.join("agent-tasks");
        Ok(Self {
            receipt: directory.join("current.json"),
            lock: directory.join("task.lock"),
            init_script: directory.join("gradle-receipt.init.gradle"),
            directory,
        })
    }

    fn gradle_model_receipt(&self, task_id: &str) -> PathBuf {
        self.directory.join(format!("{task_id}-gradle-model.json"))
    }

    fn gradle_outcome_receipt(&self, task_id: &str, ordinal: usize) -> PathBuf {
        self.directory
            .join(format!("{task_id}-gradle-outcome-{ordinal}.json"))
    }

    fn completed_receipt(&self, task_id: &str) -> PathBuf {
        self.directory.join(format!("{task_id}.complete.json"))
    }
}

pub(crate) fn run_agent_home(output_format: OutputFormat) -> Result<i32> {
    let workspace_root = resolve_agent_task_workspace(AgentTaskWorkspaceArgs::default())?;
    let owner = current_agent_task_owner();
    let generation = agent_task_effective_generation();
    let paths = AgentTaskPaths::resolve(&workspace_root)?;
    let active_task =
        read_agent_task_receipt(&paths.receipt)?.filter(|receipt| receipt.state.owns_lease());
    let owner_conflict = match owner {
        Ok(owner) => active_task
            .as_ref()
            .filter(|receipt| receipt.owner != owner)
            .map(|receipt| {
                AgentTaskBlocker::new(
                    "AGENT_TASK_OWNER_CONFLICT",
                    "The exact workspace task lease belongs to another agent session.",
                )
                .detail("taskId", receipt.task_id.clone())
            }),
        Err(error) => Some(AgentTaskBlocker::new(error.code, error.message)),
    };
    let task_generation_conflict = generation.as_ref().ok().and_then(|generation| {
        active_task
            .as_ref()
            .filter(|receipt| receipt.generation != *generation)
            .map(|receipt| agent_task_generation_blocker(&receipt.generation, generation))
    });
    let admission_blocker = owner_conflict.or(task_generation_conflict);
    let (state, generation, blocker) = match (generation, admission_blocker) {
        (Ok(generation), Some(blocker)) => ("BLOCKED", Some(generation), Some(blocker)),
        (Err(error), Some(owner_blocker)) => (
            "BLOCKED",
            None,
            Some(owner_blocker.detail(
                "generationError",
                format!("{}: {}", error.code, error.message),
            )),
        ),
        (Ok(generation), None) => ("READY", Some(generation), None),
        (Err(error), None) => (
            "BLOCKED",
            None,
            Some(
                AgentTaskBlocker::new(error.code, error.message)
                    .detail("workspaceRoot", workspace_root.display().to_string()),
            ),
        ),
    };
    let home = AgentTaskHome {
        home_type: AGENT_TASK_HOME_TYPE,
        workspace_root: workspace_root.display().to_string(),
        readiness: AgentTaskHomeReadiness {
            state,
            generation,
            blocker,
        },
        active_task: active_task.as_ref().map(AgentTaskHomeTask::from),
        next_commands: vec![
            "kast agent task begin".to_string(),
            "kast agent task status".to_string(),
            "kast agent task finish".to_string(),
            "kast agent task abort".to_string(),
            "kast agent --help".to_string(),
        ],
        schema_version: AGENT_TASK_SCHEMA_VERSION,
    };
    output::print_structured(&home, output_format)?;
    Ok(0)
}

fn execute_agent_task(args: AgentTaskArgs) -> AgentEnvelope {
    let (method, result) = match args.command {
        AgentTaskCommand::Begin(args) => ("agent/task/begin", begin_agent_task(args)),
        AgentTaskCommand::Status(args) => ("agent/task/status", status_agent_task(args)),
        AgentTaskCommand::Finish(args) => ("agent/task/finish", finish_agent_task(args)),
        AgentTaskCommand::Abort(args) => ("agent/task/abort", abort_agent_task(args)),
    };
    match result {
        Ok(execution) => {
            let error = (!execution.ok).then(|| {
                let blocker = execution
                    .receipt
                    .blockers
                    .first()
                    .cloned()
                    .unwrap_or_else(|| {
                        AgentTaskBlocker::new("AGENT_TASK_BLOCKED", "Agent task is blocked.")
                    });
                AgentError {
                    code: blocker.code,
                    message: blocker.message,
                    details: blocker
                        .details
                        .into_iter()
                        .map(|(key, value)| (key, json!(value)))
                        .collect(),
                }
            });
            AgentEnvelope {
                ok: execution.ok,
                method: method.to_string(),
                request: None,
                response: None,
                result: Some(json!(execution.receipt)),
                raw_response: None,
                error,
                schema_version: SCHEMA_VERSION,
            }
        }
        Err(error) => error_envelope(method.to_string(), None, AgentError::from_cli_error(error)),
    }
}

pub(crate) fn run_agent_task_hook(
    operation: AgentTaskHookOperation,
    workspace_start: &Path,
    provider: &str,
    session_id: &str,
) -> Result<AgentTaskHookResult> {
    let workspace_root = resolve_agent_task_start_path(workspace_start)?;
    let owner = agent_task_session_owner(provider, session_id)?;
    let execution = match operation {
        AgentTaskHookOperation::Begin => begin_agent_task_core(workspace_root, owner),
        AgentTaskHookOperation::Status => status_agent_task_core(workspace_root, owner),
        AgentTaskHookOperation::Finish => finish_agent_task_core(workspace_root, owner),
        AgentTaskHookOperation::Abort => abort_agent_task_core(workspace_root, owner),
    }?;
    Ok(AgentTaskHookResult {
        ok: execution.ok,
        receipt: execution.receipt,
    })
}

fn begin_agent_task(args: AgentTaskWorkspaceArgs) -> Result<AgentTaskExecution> {
    let workspace_root = resolve_agent_task_workspace(args)?;
    let owner = current_agent_task_owner()?;
    begin_agent_task_core(workspace_root, owner)
}

fn begin_agent_task_core(
    workspace_root: PathBuf,
    owner: AgentTaskOwnerIdentity,
) -> Result<AgentTaskExecution> {
    let generation = agent_task_effective_generation()?;
    let paths = AgentTaskPaths::resolve(&workspace_root)?;
    with_agent_task_lock(&paths, || {
        if let Some(receipt) = read_agent_task_receipt(&paths.receipt)?
            && receipt.state.owns_lease()
        {
            if receipt.owner != owner && agent_task_owner_is_dead_process(&receipt.owner) {
                let mut recovered = receipt;
                recovered.owner = owner.clone();
                recovered.lease = AgentTaskLeaseIdentity {
                    lease_id: format!("ktl1.{}", uuid::Uuid::new_v4()),
                    owner,
                    acquired_at: crate::manifest::current_timestamp(),
                };
                observe_agent_task_current(&mut recovered, &workspace_root)?;
                recovered.state = AgentTaskState::Blocked;
                recovered.blockers = vec![AgentTaskBlocker::new(
                    "AGENT_TASK_OWNER_RECOVERED",
                    "A dead process-owned task was recovered without inferring completion; retry finish.",
                )];
                recovered.completion = None;
                recovered.finished_at = None;
                recovered.updated_at = crate::manifest::current_timestamp();
                write_agent_task_receipt(&paths.receipt, &recovered)?;
                return Ok(AgentTaskExecution {
                    receipt: recovered,
                    ok: true,
                });
            }
            require_agent_task_owner(&receipt, &owner)?;
            require_agent_task_generation(&receipt, &generation)?;
            let mut observed = receipt;
            observe_agent_task_current(&mut observed, &workspace_root)?;
            if observed.state == AgentTaskState::Validating {
                observed.state = AgentTaskState::Blocked;
                observed.blockers = vec![AgentTaskBlocker::new(
                    "AGENT_TASK_VALIDATION_INTERRUPTED",
                    "The preceding validation did not reach an atomic terminal receipt; retry finish.",
                )];
            }
            observed.updated_at = crate::manifest::current_timestamp();
            write_agent_task_receipt(&paths.receipt, &observed)?;
            return Ok(AgentTaskExecution {
                receipt: observed,
                ok: true,
            });
        }

        let task_id = uuid::Uuid::new_v4().to_string();
        materialize_agent_task_init_script(&paths)?;
        let baseline = capture_agent_task_snapshot(&workspace_root)?;
        let gradle_model =
            resolve_agent_task_gradle_model(&workspace_root, &paths, &task_id, &baseline)?;
        let gradle_model_sha256 = digest_serializable(&gradle_model)?;
        let now = crate::manifest::current_timestamp();
        let receipt = AgentTaskReceipt {
            receipt_type: AGENT_TASK_RECEIPT_TYPE.to_string(),
            state: AgentTaskState::Active,
            task_id,
            owner: owner.clone(),
            workspace_root: workspace_root.display().to_string(),
            generation,
            lease: AgentTaskLeaseIdentity {
                lease_id: format!("ktl1.{}", uuid::Uuid::new_v4()),
                owner,
                acquired_at: now.clone(),
            },
            current: baseline.clone(),
            baseline,
            gradle_model,
            gradle_model_sha256,
            diagnostics: Vec::new(),
            gradle: Vec::new(),
            test_reports: Vec::new(),
            validation: None,
            blockers: Vec::new(),
            completion: None,
            started_at: now.clone(),
            updated_at: now,
            finished_at: None,
            schema_version: AGENT_TASK_SCHEMA_VERSION,
        };
        write_agent_task_receipt(&paths.receipt, &receipt)?;
        Ok(AgentTaskExecution { receipt, ok: true })
    })
}

fn status_agent_task(args: AgentTaskWorkspaceArgs) -> Result<AgentTaskExecution> {
    let workspace_root = resolve_agent_task_workspace(args)?;
    let owner = current_agent_task_owner()?;
    status_agent_task_core(workspace_root, owner)
}

fn status_agent_task_core(
    workspace_root: PathBuf,
    owner: AgentTaskOwnerIdentity,
) -> Result<AgentTaskExecution> {
    let paths = AgentTaskPaths::resolve(&workspace_root)?;
    let mut receipt = with_agent_task_read_lock(&paths, || {
        required_agent_task_receipt(&paths.receipt, &workspace_root)
    })?;
    require_agent_task_owner(&receipt, &owner)?;
    if matches!(
        receipt.state,
        AgentTaskState::Complete | AgentTaskState::Aborted
    ) {
        return Ok(AgentTaskExecution { receipt, ok: true });
    }
    observe_agent_task_current(&mut receipt, &workspace_root)?;
    match agent_task_effective_generation() {
        Ok(generation) if generation == receipt.generation => {}
        Ok(generation) => {
            receipt.state = AgentTaskState::Blocked;
            receipt.blockers = vec![agent_task_generation_blocker(
                &receipt.generation,
                &generation,
            )];
        }
        Err(error) => {
            receipt.state = AgentTaskState::Blocked;
            receipt.blockers = vec![AgentTaskBlocker::new(error.code, error.message)];
        }
    }
    if receipt.state == AgentTaskState::Validating {
        receipt.state = AgentTaskState::Blocked;
        receipt.blockers = vec![AgentTaskBlocker::new(
            "AGENT_TASK_VALIDATION_INTERRUPTED",
            "The preceding validation did not reach an atomic terminal receipt; retry finish.",
        )];
    }
    Ok(AgentTaskExecution { receipt, ok: true })
}

fn abort_agent_task(args: AgentTaskWorkspaceArgs) -> Result<AgentTaskExecution> {
    let workspace_root = resolve_agent_task_workspace(args)?;
    let owner = current_agent_task_owner()?;
    abort_agent_task_core(workspace_root, owner)
}

fn abort_agent_task_core(
    workspace_root: PathBuf,
    owner: AgentTaskOwnerIdentity,
) -> Result<AgentTaskExecution> {
    let paths = AgentTaskPaths::resolve(&workspace_root)?;
    with_agent_task_lock(&paths, || {
        let mut receipt = required_agent_task_receipt(&paths.receipt, &workspace_root)?;
        require_agent_task_owner(&receipt, &owner)?;
        if receipt.state == AgentTaskState::Aborted {
            return Ok(AgentTaskExecution { receipt, ok: true });
        }
        if receipt.state == AgentTaskState::Complete {
            return Err(CliError::new(
                "AGENT_TASK_ALREADY_COMPLETE",
                "A completed task cannot be reclassified as aborted.",
            ));
        }
        receipt.current = capture_agent_task_snapshot(&workspace_root)?;
        receipt.state = AgentTaskState::Aborted;
        receipt.blockers.clear();
        receipt.completion = None;
        receipt.validation = None;
        let now = crate::manifest::current_timestamp();
        receipt.updated_at = now.clone();
        receipt.finished_at = Some(now);
        write_agent_task_receipt(&paths.receipt, &receipt)?;
        Ok(AgentTaskExecution { receipt, ok: true })
    })
}

fn finish_agent_task(args: AgentTaskWorkspaceArgs) -> Result<AgentTaskExecution> {
    let workspace_root = resolve_agent_task_workspace(args)?;
    let owner = current_agent_task_owner()?;
    finish_agent_task_core(workspace_root, owner)
}

fn finish_agent_task_core(
    workspace_root: PathBuf,
    owner: AgentTaskOwnerIdentity,
) -> Result<AgentTaskExecution> {
    let paths = AgentTaskPaths::resolve(&workspace_root)?;
    with_agent_task_lock(&paths, || {
        let mut receipt = required_agent_task_receipt(&paths.receipt, &workspace_root)?;
        require_agent_task_owner(&receipt, &owner)?;
        let completed_path = paths.completed_receipt(&receipt.task_id);
        if let Some(completed) = read_agent_task_receipt(&completed_path)? {
            require_agent_task_owner(&completed, &owner)?;
            write_agent_task_receipt(&paths.receipt, &completed)?;
            return Ok(AgentTaskExecution {
                receipt: completed,
                ok: true,
            });
        }
        if receipt.state == AgentTaskState::Complete {
            return Ok(AgentTaskExecution { receipt, ok: true });
        }
        if receipt.state == AgentTaskState::Aborted {
            return Err(CliError::new(
                "AGENT_TASK_ABORTED",
                "An aborted task cannot claim completion; begin a new task.",
            ));
        }
        let generation = agent_task_effective_generation()?;
        if generation != receipt.generation {
            let blocker = agent_task_generation_blocker(&receipt.generation, &generation);
            return persist_blocked_agent_task(&paths, receipt, vec![blocker]);
        }

        receipt.state = AgentTaskState::Validating;
        receipt.current = capture_agent_task_snapshot(&workspace_root)?;
        receipt.diagnostics.clear();
        receipt.gradle.clear();
        receipt.test_reports.clear();
        receipt.validation = None;
        receipt.blockers.clear();
        receipt.completion = None;
        receipt.finished_at = None;
        receipt.updated_at = crate::manifest::current_timestamp();
        write_agent_task_receipt(&paths.receipt, &receipt)?;

        let changed = agent_task_validation_paths(&receipt.baseline, &receipt.current);
        if changed.is_empty() {
            let input_sha256 = receipt.current.sha256.clone();
            receipt.state = AgentTaskState::Complete;
            receipt.completion = Some(AgentTaskCompletionProof::NoRelevantChanges { input_sha256 });
            let now = crate::manifest::current_timestamp();
            receipt.updated_at = now.clone();
            receipt.finished_at = Some(now);
            write_completed_agent_task_receipt(&completed_path, &receipt)?;
            write_agent_task_receipt(&paths.receipt, &receipt)?;
            return Ok(AgentTaskExecution { receipt, ok: true });
        }

        let kotlin_paths = changed
            .iter()
            .filter(|path| {
                matches!(
                    Path::new(path).extension().and_then(|value| value.to_str()),
                    Some("kt" | "kts")
                ) && matches!(
                    receipt.current.files.get(*path),
                    Some(AgentTaskContentIdentity::Present { .. })
                )
            })
            .cloned()
            .collect::<Vec<_>>();
        if !kotlin_paths.is_empty() {
            match collect_agent_task_diagnostics(&workspace_root, &receipt.current, &kotlin_paths) {
                Ok(proof) => receipt.diagnostics.push(proof),
                Err(blocker) => {
                    return persist_blocked_agent_task(&paths, receipt, vec![blocker]);
                }
            }
        }

        let workflow = match read_agent_task_workflow(&workspace_root) {
            Ok(workflow) => workflow,
            Err(blocker) => {
                return persist_blocked_agent_task(&paths, receipt, vec![blocker]);
            }
        };
        let plan = match plan_agent_task_gradle_validation(
            &workspace_root,
            &receipt.gradle_model,
            &changed,
            workflow.as_ref(),
        ) {
            Ok(plan) => plan,
            Err(blocker) => {
                return persist_blocked_agent_task(&paths, receipt, vec![blocker]);
            }
        };
        let policy_sha256 = agent_task_policy_sha256(&receipt.current);
        let validation_input_sha256 = agent_task_validation_input_sha256(
            &receipt.generation,
            &receipt.gradle_model_sha256,
            &receipt.current,
            &policy_sha256,
            &plan,
        )?;
        receipt.validation = Some(AgentTaskValidationIdentity {
            policy_sha256,
            plan: plan.clone(),
            input_sha256: validation_input_sha256.clone(),
        });
        write_agent_task_receipt(&paths.receipt, &receipt)?;
        match execute_agent_task_gradle_plan(
            &workspace_root,
            &paths,
            &receipt.task_id,
            &validation_input_sha256,
            &receipt.gradle_model,
            &plan,
            &receipt.current,
        ) {
            Ok((gradle, test_reports)) => {
                receipt.gradle = gradle;
                receipt.test_reports = test_reports;
            }
            Err(blocker) => {
                return persist_blocked_agent_task(&paths, receipt, vec![blocker]);
            }
        }

        let final_snapshot = capture_agent_task_snapshot(&workspace_root)?;
        if final_snapshot != receipt.current {
            let before_sha256 = receipt.current.sha256.clone();
            return persist_blocked_agent_task(
                &paths,
                receipt,
                vec![AgentTaskBlocker::new(
                    "WORKSPACE_CHANGED_DURING_VALIDATION",
                    "Relevant workspace inputs changed while diagnostics or Gradle validation ran.",
                )
                .detail("beforeSha256", before_sha256)
                .detail("afterSha256", final_snapshot.sha256)],
            );
        }
        receipt.current = final_snapshot;
        receipt.state = AgentTaskState::Complete;
        receipt.completion = Some(AgentTaskCompletionProof::Validated {
            input_sha256: validation_input_sha256,
        });
        let now = crate::manifest::current_timestamp();
        receipt.updated_at = now.clone();
        receipt.finished_at = Some(now);
        write_completed_agent_task_receipt(&completed_path, &receipt)?;
        write_agent_task_receipt(&paths.receipt, &receipt)?;
        Ok(AgentTaskExecution { receipt, ok: true })
    })
}

fn persist_blocked_agent_task(
    paths: &AgentTaskPaths,
    mut receipt: AgentTaskReceipt,
    blockers: Vec<AgentTaskBlocker>,
) -> Result<AgentTaskExecution> {
    receipt.state = AgentTaskState::Blocked;
    receipt.blockers = blockers;
    receipt.completion = None;
    receipt.finished_at = None;
    receipt.updated_at = crate::manifest::current_timestamp();
    write_agent_task_receipt(&paths.receipt, &receipt)?;
    Ok(AgentTaskExecution { receipt, ok: false })
}

fn observe_agent_task_current(
    receipt: &mut AgentTaskReceipt,
    workspace_root: &Path,
) -> Result<bool> {
    let observed = capture_agent_task_snapshot(workspace_root)?;
    if observed == receipt.current {
        return Ok(false);
    }
    receipt.current = observed;
    receipt.diagnostics.clear();
    receipt.gradle.clear();
    receipt.test_reports.clear();
    receipt.validation = None;
    if receipt.state == AgentTaskState::Blocked {
        receipt.blockers = vec![AgentTaskBlocker::new(
            "AGENT_TASK_PROOF_STALE",
            "Relevant workspace inputs changed after the recorded validation attempt; retry finish.",
        )];
    }
    Ok(true)
}

fn resolve_agent_task_workspace(args: AgentTaskWorkspaceArgs) -> Result<PathBuf> {
    match args.workspace_root {
        Some(declared) => validate_agent_task_workspace(&declared),
        None => resolve_agent_task_start_path(&std::env::current_dir()?),
    }
}

fn validate_agent_task_workspace(declared: &Path) -> Result<PathBuf> {
    let declared = crate::config::resolve_workspace_root(Some(declared.to_path_buf()))?;
    let canonical = std::fs::canonicalize(&declared).map_err(|error| {
        CliError::new(
            "AGENT_TASK_WORKSPACE_INVALID",
            format!(
                "Cannot canonicalize agent task workspace {}: {error}",
                declared.display()
            ),
        )
    })?;
    if !canonical.is_dir() {
        return Err(CliError::new(
            "AGENT_TASK_WORKSPACE_INVALID",
            format!(
                "Agent task workspace is not a directory: {}",
                canonical.display()
            ),
        ));
    }
    if ![
        "settings.gradle.kts",
        "settings.gradle",
        "build.gradle.kts",
        "build.gradle",
        "gradlew",
    ]
    .iter()
    .any(|marker| canonical.join(marker).exists())
    {
        return Err(CliError::new(
            "AGENT_TASK_WORKSPACE_UNSUPPORTED",
            format!(
                "Agent task lifecycle requires an exact Gradle workspace root: {}",
                canonical.display()
            ),
        ));
    }
    Ok(canonical)
}

pub(crate) fn resolve_agent_task_start_path(start: &Path) -> Result<PathBuf> {
    let canonical = std::fs::canonicalize(start).map_err(|error| {
        CliError::new(
            "AGENT_TASK_WORKSPACE_INVALID",
            format!(
                "Cannot canonicalize agent task start path {}: {error}",
                start.display()
            ),
        )
    })?;
    if !canonical.is_dir() {
        return Err(CliError::new(
            "AGENT_TASK_WORKSPACE_INVALID",
            format!(
                "Agent task start path is not a directory: {}",
                canonical.display()
            ),
        ));
    }
    let wrapper_root = canonical.ancestors().find(|candidate| {
        ["gradlew", "gradlew.bat"]
            .iter()
            .any(|launcher| candidate.join(launcher).is_file())
            && validate_agent_task_workspace(candidate).is_ok()
    });
    let discovered = wrapper_root.or_else(|| {
        canonical
            .ancestors()
            .find(|candidate| validate_agent_task_workspace(candidate).is_ok())
    });
    discovered
        .map(Path::to_path_buf)
        .ok_or_else(|| {
            CliError::new(
                "AGENT_TASK_WORKSPACE_UNSUPPORTED",
                format!(
                    "Agent task lifecycle could not find a Gradle workspace from {}.",
                    canonical.display()
                ),
            )
        })
}

fn current_agent_task_owner() -> Result<AgentTaskOwnerIdentity> {
    const SESSION_ENVIRONMENTS: &[(&str, &str)] = &[
        ("KAST_AGENT_SESSION_ID", "kast"),
        ("CODEX_THREAD_ID", "codex"),
    ];
    if let Some((provider, session)) = SESSION_ENVIRONMENTS.iter().find_map(|(name, provider)| {
        std::env::var(name)
            .ok()
            .filter(|value| !value.trim().is_empty())
            .map(|value| ((*provider).to_string(), value))
    }) {
        return agent_task_session_owner(&provider, &session);
    }
    if !std::io::stdin().is_terminal() {
        return Err(CliError::new(
            "AGENT_TASK_OWNER_UNAVAILABLE",
            "Non-interactive task callers must set KAST_AGENT_SESSION_ID or CODEX_THREAD_ID.",
        ));
    }
    #[cfg(unix)]
    let direct_parent = u64::try_from(unsafe { libc::getppid() }).map_err(|_| {
        CliError::new(
            "AGENT_TASK_OWNER_INVALID",
            "The caller process id could not be represented.",
        )
    })?;
    #[cfg(not(unix))]
    let direct_parent = u64::from(std::process::id());
    let pid = agent_task_parent_process(direct_parent)
        .filter(|(_, command)| agent_task_transient_shell(command))
        .map_or(direct_parent, |(parent, _)| parent);
    let started_at = agent_task_process_started_at(pid)?;
    Ok(AgentTaskOwnerIdentity::Process { pid, started_at })
}

fn agent_task_session_owner(provider: &str, session_id: &str) -> Result<AgentTaskOwnerIdentity> {
    if provider.is_empty()
        || provider.trim() != provider
        || provider.chars().any(char::is_control)
        || session_id.is_empty()
        || session_id.trim() != session_id
        || session_id.chars().any(char::is_control)
    {
        return Err(CliError::new(
            "AGENT_TASK_OWNER_INVALID",
            "Agent task provider and session identities must be non-blank stable values.",
        ));
    }
    Ok(AgentTaskOwnerIdentity::Session {
        provider: provider.to_string(),
        session_sha256: crate::manifest::sha256_bytes(session_id.as_bytes()),
    })
}

fn agent_task_parent_process(pid: u64) -> Option<(u64, String)> {
    let output = std::process::Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "ppid=,comm=", "-p", &pid.to_string()])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let line = String::from_utf8_lossy(&output.stdout);
    let mut fields = line.split_whitespace();
    let parent = fields.next()?.parse().ok()?;
    let command = fields.collect::<Vec<_>>().join(" ");
    (!command.is_empty()).then_some((parent, command))
}

fn agent_task_transient_shell(command: &str) -> bool {
    Path::new(command)
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| matches!(name, "sh" | "bash" | "dash" | "fish" | "zsh"))
}

fn agent_task_process_started_at(pid: u64) -> Result<String> {
    let output = std::process::Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .output()?;
    let started_at = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !output.status.success() || started_at.is_empty() {
        return Err(CliError::new(
            "AGENT_TASK_OWNER_UNAVAILABLE",
            format!("Could not prove process-start identity for PID {pid}."),
        ));
    }
    Ok(started_at)
}

fn agent_task_effective_generation() -> Result<AgentTaskGenerationIdentity> {
    if let Some(generation) = crate::machine::active_machine_identity()? {
        return Ok(AgentTaskGenerationIdentity {
            authority: "machine".to_string(),
            generation,
        });
    }
    #[cfg(target_os = "macos")]
    if let Some(receipt) = crate::install::read_macos_homebrew_receipt()? {
        let running = std::fs::canonicalize(std::env::current_exe()?)?;
        let installed = std::fs::canonicalize(&receipt.cli.binary)?;
        if running == installed {
            let launcher = receipt
                .cli
                .binary
                .parent()
                .ok_or_else(|| {
                    CliError::new(
                        "AGENT_TASK_GENERATION_UNAVAILABLE",
                        "The Homebrew CLI path has no sibling launcher directory.",
                    )
                })?
                .join("kast-agent-task");
            return agent_task_installed_generation(
                "macos-homebrew",
                &receipt.cli.version,
                &running,
                &launcher,
            );
        }
    }
    if let Some(manifest) = crate::manifest::read_install_manifest()? {
        let running = std::fs::canonicalize(std::env::current_exe()?)?;
        let installed = std::fs::canonicalize(&manifest.entrypoints.active_binary)?;
        if running == installed && !manifest.install_id.trim().is_empty() {
            if manifest.entrypoints.task_launcher.trim().is_empty() {
                return Err(CliError::new(
                    "AGENT_TASK_GENERATION_UNAVAILABLE",
                    "The selected install manifest does not attest the agent-task launcher.",
                ));
            }
            return agent_task_installed_generation(
                "managed-local",
                &manifest.install_id,
                &running,
                Path::new(&manifest.entrypoints.task_launcher),
            );
        }
    }
    Err(CliError::new(
        "AGENT_TASK_GENERATION_UNAVAILABLE",
        "Agent task lifecycle requires the running CLI to belong to one effective installed generation.",
    ))
}

fn agent_task_installed_generation(
    authority: &str,
    install_identity: &str,
    binary: &Path,
    launcher: &Path,
) -> Result<AgentTaskGenerationIdentity> {
    if !launcher.is_file() {
        return Err(CliError::new(
            "AGENT_TASK_GENERATION_UNAVAILABLE",
            format!(
                "The selected Kast generation is missing its attested task launcher: {}",
                launcher.display()
            ),
        ));
    }
    Ok(AgentTaskGenerationIdentity {
        authority: authority.to_string(),
        generation: digest_serializable(&(
            install_identity,
            crate::manifest::sha256_file(binary)?,
            crate::manifest::sha256_file(launcher)?,
        ))?,
    })
}

fn require_agent_task_owner(
    receipt: &AgentTaskReceipt,
    owner: &AgentTaskOwnerIdentity,
) -> Result<()> {
    if receipt.owner == *owner && receipt.lease.owner == *owner {
        return Ok(());
    }
    Err(CliError::new(
        "AGENT_TASK_OWNER_CONFLICT",
        "The exact workspace task lease belongs to another agent session.",
    ))
}

fn agent_task_owner_is_dead_process(owner: &AgentTaskOwnerIdentity) -> bool {
    let AgentTaskOwnerIdentity::Process { pid, started_at } = owner else {
        return false;
    };
    match agent_task_process_started_at(*pid) {
        Ok(actual) => actual != *started_at,
        Err(_) => true,
    }
}

fn require_agent_task_generation(
    receipt: &AgentTaskReceipt,
    generation: &AgentTaskGenerationIdentity,
) -> Result<()> {
    if receipt.generation == *generation {
        Ok(())
    } else {
        let blocker = agent_task_generation_blocker(&receipt.generation, generation);
        let mut error = CliError::new("AGENT_TASK_STALE_GENERATION", blocker.message);
        error.details = blocker.details;
        Err(error)
    }
}

fn agent_task_generation_blocker(
    expected: &AgentTaskGenerationIdentity,
    actual: &AgentTaskGenerationIdentity,
) -> AgentTaskBlocker {
    AgentTaskBlocker::new(
        "AGENT_TASK_STALE_GENERATION",
        "The task receipt belongs to a different effective Kast generation.",
    )
    .detail("expectedAuthority", expected.authority.clone())
    .detail("expectedGeneration", expected.generation.clone())
    .detail("actualAuthority", actual.authority.clone())
    .detail("actualGeneration", actual.generation.clone())
}

fn with_agent_task_lock<T>(
    paths: &AgentTaskPaths,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    prepare_agent_task_directory(paths)?;
    let file = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&paths.lock)?;
    agent_task_flock(&file, libc::LOCK_EX)?;
    let result = action();
    let unlock = agent_task_flock(&file, libc::LOCK_UN);
    match (result, unlock) {
        (Err(error), _) => Err(error),
        (Ok(value), Ok(())) => Ok(value),
        (Ok(_), Err(error)) => Err(error),
    }
}

fn with_agent_task_read_lock<T>(
    paths: &AgentTaskPaths,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    if !paths.receipt.is_file() || !paths.lock.is_file() {
        return action();
    }
    let file = std::fs::OpenOptions::new().read(true).open(&paths.lock)?;
    agent_task_flock(&file, libc::LOCK_SH)?;
    let result = action();
    let unlock = agent_task_flock(&file, libc::LOCK_UN);
    match (result, unlock) {
        (Err(error), _) => Err(error),
        (Ok(value), Ok(())) => Ok(value),
        (Ok(_), Err(error)) => Err(error),
    }
}

#[cfg(unix)]
fn agent_task_flock(file: &std::fs::File, operation: libc::c_int) -> Result<()> {
    use std::os::fd::AsRawFd;
    if unsafe { libc::flock(file.as_raw_fd(), operation) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn agent_task_flock(_file: &std::fs::File, _operation: libc::c_int) -> Result<()> {
    Ok(())
}

fn prepare_agent_task_directory(paths: &AgentTaskPaths) -> Result<()> {
    std::fs::create_dir_all(&paths.directory)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&paths.directory, std::fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

fn read_agent_task_receipt(path: &Path) -> Result<Option<AgentTaskReceipt>> {
    let bytes = match std::fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let receipt: AgentTaskReceipt = serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            format!("Persisted agent task receipt is invalid: {error}"),
        )
    })?;
    validate_agent_task_receipt(receipt).map(Some)
}

fn required_agent_task_receipt(path: &Path, workspace_root: &Path) -> Result<AgentTaskReceipt> {
    let receipt = read_agent_task_receipt(path)?.ok_or_else(|| {
        CliError::new(
            "AGENT_TASK_NOT_FOUND",
            "No agent task receipt exists for this exact workspace root; run task begin.",
        )
    })?;
    if receipt.workspace_root != workspace_root.display().to_string() {
        return Err(CliError::new(
            "AGENT_TASK_WORKSPACE_MISMATCH",
            "Persisted agent task evidence belongs to another exact workspace root.",
        ));
    }
    Ok(receipt)
}

fn validate_agent_task_receipt(receipt: AgentTaskReceipt) -> Result<AgentTaskReceipt> {
    if receipt.receipt_type != AGENT_TASK_RECEIPT_TYPE
        || receipt.schema_version != AGENT_TASK_SCHEMA_VERSION
        || uuid::Uuid::parse_str(&receipt.task_id).is_err()
        || receipt
            .lease
            .lease_id
            .strip_prefix("ktl1.")
            .is_none_or(|value| uuid::Uuid::parse_str(value).is_err())
        || receipt.lease.owner != receipt.owner
        || receipt.workspace_root.trim().is_empty()
        || !matches!(
            receipt.generation.authority.as_str(),
            "machine" | "macos-homebrew" | "managed-local"
        )
        || !agent_task_sha256(&receipt.generation.generation)
        || receipt.gradle_model_sha256 != digest_serializable(&receipt.gradle_model)?
        || !agent_task_receipt_state_valid(&receipt)
        || !agent_task_owner_valid(&receipt.owner)
        || !Path::new(&receipt.workspace_root).is_absolute()
        || !agent_task_timestamp(&receipt.lease.acquired_at)
        || !agent_task_timestamp(&receipt.started_at)
        || !agent_task_timestamp(&receipt.updated_at)
        || receipt
            .finished_at
            .as_deref()
            .is_some_and(|timestamp| !agent_task_timestamp(timestamp))
        || receipt.blockers.iter().any(|blocker| {
            blocker.code.is_empty()
                || !blocker
                    .code
                    .bytes()
                    .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
                || blocker.message.trim().is_empty()
                || blocker.details.iter().any(|(key, value)| {
                    key.trim().is_empty()
                        || key.chars().any(char::is_control)
                        || value.chars().any(|character| {
                            character.is_control() && !matches!(character, '\n' | '\t')
                        })
                })
        })
    {
        return Err(CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            "Persisted agent task receipt failed its strict identity contract.",
        ));
    }
    validate_agent_task_snapshot(&receipt.baseline)?;
    validate_agent_task_snapshot(&receipt.current)?;
    let normalized_model = validate_agent_task_gradle_model(
        Path::new(&receipt.workspace_root),
        receipt.gradle_model.clone(),
    )?;
    if normalized_model != receipt.gradle_model
        || !agent_task_validation_identity_valid(&receipt)?
        || !agent_task_completion_coherent(&receipt)
        || !agent_task_diagnostics_coverage_valid(&receipt)
    {
        return Err(CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            "Persisted agent task receipt contains incoherent completion proof.",
        ));
    }
    if receipt.diagnostics.iter().any(|proof| {
        proof.semantic_outcome != "COMPLETE"
            || proof.error_count != 0
            || proof.files.is_empty()
            || proof.files.iter().any(|(path, hash)| {
                !agent_task_relative_evidence_path(path)
                    || !matches!(
                        receipt.current.files.get(path),
                        Some(AgentTaskContentIdentity::Present { sha256 }) if sha256 == hash
                    )
            })
    }) || receipt.gradle.iter().any(|proof| {
        !agent_task_sha256(&proof.input_sha256)
            || validate_agent_task_relative_directory(&proof.build_root, "buildRoot").is_err()
            || proof.build_tasks.is_empty()
            || proof.test_tasks.is_empty()
            || !agent_task_selected_gradle_tasks_valid(proof)
            || proof
                .observed_tasks
                .iter()
                .any(|task| validate_agent_task_gradle_task_path(&task.path).is_err())
    }) || receipt.test_reports.iter().any(|proof| {
        !agent_task_relative_evidence_path(&proof.path)
            || !agent_task_sha256(&proof.sha256)
            || !agent_task_report_path_in_model(&receipt.gradle_model, &proof.path)
    }) {
        return Err(CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            "Persisted agent task receipt contains malformed validation evidence.",
        ));
    }
    Ok(receipt)
}

fn agent_task_receipt_state_valid(receipt: &AgentTaskReceipt) -> bool {
    match receipt.state {
        AgentTaskState::Active => {
            receipt.blockers.is_empty()
                && receipt.completion.is_none()
                && receipt.validation.is_none()
                && receipt.diagnostics.is_empty()
                && receipt.gradle.is_empty()
                && receipt.test_reports.is_empty()
                && receipt.finished_at.is_none()
        }
        AgentTaskState::Validating => {
            receipt.blockers.is_empty()
                && receipt.completion.is_none()
                && receipt.finished_at.is_none()
        }
        AgentTaskState::Blocked => {
            !receipt.blockers.is_empty()
                && receipt.completion.is_none()
                && receipt.finished_at.is_none()
        }
        AgentTaskState::Complete => {
            receipt.blockers.is_empty()
                && receipt.completion.is_some()
                && receipt.finished_at.is_some()
        }
        AgentTaskState::Aborted => {
            receipt.blockers.is_empty()
                && receipt.completion.is_none()
                && receipt.validation.is_none()
                && receipt.finished_at.is_some()
        }
    }
}

fn agent_task_completion_coherent(receipt: &AgentTaskReceipt) -> bool {
    match &receipt.completion {
        None => receipt.state != AgentTaskState::Complete,
        Some(AgentTaskCompletionProof::NoRelevantChanges { input_sha256 }) => {
            receipt.state == AgentTaskState::Complete
                && agent_task_validation_paths(&receipt.baseline, &receipt.current).is_empty()
                && input_sha256 == &receipt.current.sha256
                && receipt.diagnostics.is_empty()
                && receipt.gradle.is_empty()
                && receipt.test_reports.is_empty()
                && receipt.validation.is_none()
        }
        Some(AgentTaskCompletionProof::Validated { input_sha256 }) => {
            receipt.state == AgentTaskState::Complete
                && receipt.baseline != receipt.current
                && agent_task_sha256(input_sha256)
                && !receipt.gradle.is_empty()
                && !receipt.test_reports.is_empty()
                && receipt
                    .validation
                    .as_ref()
                    .is_some_and(|validation| validation.input_sha256 == *input_sha256)
                && receipt
                    .gradle
                    .iter()
                    .all(|proof| proof.input_sha256 == *input_sha256)
                && agent_task_validation_paths(&receipt.baseline, &receipt.current)
                    .into_iter()
                    .filter(|path| {
                        matches!(
                            Path::new(path).extension().and_then(|value| value.to_str()),
                            Some("kt" | "kts")
                        ) && matches!(
                            receipt.current.files.get(path),
                            Some(AgentTaskContentIdentity::Present { .. })
                        )
                    })
                    .all(|path| {
                        receipt
                            .diagnostics
                            .iter()
                            .any(|proof| proof.files.contains_key(&path))
                    })
        }
    }
}

fn agent_task_validation_identity_valid(receipt: &AgentTaskReceipt) -> Result<bool> {
    let Some(validation) = &receipt.validation else {
        return Ok(!matches!(
            receipt.completion,
            Some(AgentTaskCompletionProof::Validated { .. })
        ));
    };
    if validation.policy_sha256 != agent_task_policy_sha256(&receipt.current)
        || validation.plan.is_empty()
        || validation.input_sha256
            != agent_task_validation_input_sha256(
                &receipt.generation,
                &receipt.gradle_model_sha256,
                &receipt.current,
                &validation.policy_sha256,
                &validation.plan,
            )?
    {
        return Ok(false);
    }
    let normalized = validation
        .plan
        .iter()
        .cloned()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect::<Vec<_>>();
    if normalized != validation.plan {
        return Ok(false);
    }
    for target in &validation.plan {
        let policy = AgentTaskWorkflowValidation {
            build_root: target.build_root.clone(),
            project_path: target.project_path.clone(),
            source_sets: target.source_set.clone().map(|source_set| vec![source_set]),
            build_tasks: target.build_tasks.clone(),
            test_tasks: target.test_tasks.clone(),
        };
        if validate_agent_task_policy_against_model(&receipt.gradle_model, &policy).is_err() {
            return Ok(false);
        }
    }
    if receipt.gradle.is_empty() {
        return Ok(true);
    }
    let expected = group_agent_task_validation_plan(&validation.plan);
    let actual = receipt
        .gradle
        .iter()
        .map(|proof| {
            (
                proof.build_root.clone(),
                (proof.build_tasks.clone(), proof.test_tasks.clone()),
            )
        })
        .collect::<BTreeMap<_, _>>();
    if actual.len() != receipt.gradle.len() || actual != expected {
        return Ok(false);
    }
    Ok(validation.plan.iter().all(|target| {
        target.test_tasks.iter().all(|task| {
            let directories =
                agent_task_report_directories(&receipt.gradle_model, &target.build_root, task);
            !directories.is_empty()
                && receipt.test_reports.iter().any(|report| {
                    directories
                        .iter()
                        .any(|directory| agent_task_path_is_within(&report.path, directory))
                })
        })
    }))
}

fn agent_task_diagnostics_coverage_valid(receipt: &AgentTaskReceipt) -> bool {
    let expected = agent_task_validation_paths(&receipt.baseline, &receipt.current)
        .into_iter()
        .filter(|path| {
            matches!(
                Path::new(path).extension().and_then(|value| value.to_str()),
                Some("kt" | "kts")
            ) && matches!(
                receipt.current.files.get(path),
                Some(AgentTaskContentIdentity::Present { .. })
            )
        })
        .collect::<BTreeSet<_>>();
    let actual = receipt
        .diagnostics
        .iter()
        .flat_map(|proof| proof.files.keys().cloned())
        .collect::<BTreeSet<_>>();
    let actual_count = receipt
        .diagnostics
        .iter()
        .map(|proof| proof.files.len())
        .sum::<usize>();
    (actual.is_empty() && receipt.state != AgentTaskState::Complete)
        || (actual == expected && actual_count == actual.len())
}

fn agent_task_selected_gradle_tasks_valid(proof: &AgentTaskGradleInvocationProof) -> bool {
    let observed_paths = proof
        .observed_tasks
        .iter()
        .map(|task| task.path.as_str())
        .collect::<BTreeSet<_>>();
    let selected_paths = proof
        .build_tasks
        .iter()
        .chain(&proof.test_tasks)
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    if observed_paths.len() != proof.observed_tasks.len()
        || selected_paths.len() != proof.build_tasks.len() + proof.test_tasks.len()
    {
        return false;
    }
    let observed = proof
        .observed_tasks
        .iter()
        .map(|task| (task.path.as_str(), task.outcome))
        .collect::<BTreeMap<_, _>>();
    proof
        .build_tasks
        .iter()
        .chain(&proof.test_tasks)
        .all(|path| {
            validate_agent_task_gradle_task_path(path).is_ok()
                && observed
                    .get(path.as_str())
                    .is_some_and(|outcome| outcome.is_valid_proof())
        })
}

fn agent_task_report_path_in_model(model: &AgentTaskGradleModel, path: &str) -> bool {
    model
        .builds
        .iter()
        .flat_map(|build| &build.projects)
        .flat_map(|project| &project.tasks)
        .filter(|task| task.kind == AgentTaskGradleTaskKind::Test)
        .flat_map(|task| &task.test_report_directories)
        .any(|directory| agent_task_path_is_within(path, directory))
}

fn agent_task_owner_valid(owner: &AgentTaskOwnerIdentity) -> bool {
    match owner {
        AgentTaskOwnerIdentity::Session {
            provider,
            session_sha256,
        } => {
            !provider.trim().is_empty()
                && provider.trim() == provider
                && !provider.chars().any(char::is_control)
                && agent_task_sha256(session_sha256)
        }
        AgentTaskOwnerIdentity::Process { pid, started_at } => {
            *pid > 0 && !started_at.trim().is_empty()
        }
    }
}

fn agent_task_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn agent_task_timestamp(value: &str) -> bool {
    value.strip_prefix("unix:").is_some_and(|seconds| {
        !seconds.is_empty() && seconds.bytes().all(|byte| byte.is_ascii_digit())
    })
}

fn agent_task_relative_evidence_path(value: &str) -> bool {
    !value.is_empty()
        && value.trim() == value
        && !value.contains('\\')
        && !value.chars().any(char::is_control)
        && Path::new(value)
            .components()
            .all(|component| matches!(component, std::path::Component::Normal(_)))
}

fn write_agent_task_receipt(path: &Path, receipt: &AgentTaskReceipt) -> Result<()> {
    validate_agent_task_receipt(receipt.clone())?;
    let parent = path.parent().ok_or_else(|| {
        CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            "Agent task receipt path has no parent.",
        )
    })?;
    std::fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".{}.{}.tmp", receipt.task_id, uuid::Uuid::new_v4()));
    let result = (|| {
        let mut options = std::fs::OpenOptions::new();
        options.create_new(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary)?;
        serde_json::to_writer_pretty(&mut file, receipt)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        std::fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temporary);
    }
    result
}

fn write_completed_agent_task_receipt(path: &Path, receipt: &AgentTaskReceipt) -> Result<()> {
    if receipt.state != AgentTaskState::Complete {
        return Err(CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            "Only a completed task may be written to immutable completion storage.",
        ));
    }
    validate_agent_task_receipt(receipt.clone())?;
    if let Some(existing) = read_agent_task_receipt(path)? {
        return if existing == *receipt {
            Ok(())
        } else {
            Err(CliError::new(
                "AGENT_TASK_COMPLETION_IMMUTABLE",
                "A completed task receipt already exists with different proof.",
            ))
        };
    }
    let parent = path.parent().ok_or_else(|| {
        CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            "Completed agent task receipt path has no parent.",
        )
    })?;
    let temporary = parent.join(format!(
        ".{}.{}.complete.tmp",
        receipt.task_id,
        uuid::Uuid::new_v4()
    ));
    let result = (|| {
        let mut options = std::fs::OpenOptions::new();
        options.create_new(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary)?;
        serde_json::to_writer_pretty(&mut file, receipt)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        match std::fs::hard_link(&temporary, path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                let existing = read_agent_task_receipt(path)?.ok_or_else(|| {
                    CliError::new(
                        "AGENT_TASK_COMPLETION_IMMUTABLE",
                        "Completed task storage raced without readable proof.",
                    )
                })?;
                if existing == *receipt {
                    Ok(())
                } else {
                    Err(CliError::new(
                        "AGENT_TASK_COMPLETION_IMMUTABLE",
                        "A completed task receipt already exists with different proof.",
                    ))
                }
            }
            Err(error) => Err(error.into()),
        }
    })();
    let _ = std::fs::remove_file(&temporary);
    result
}

fn materialize_agent_task_init_script(paths: &AgentTaskPaths) -> Result<()> {
    prepare_agent_task_directory(paths)?;
    let expected = AGENT_TASK_INIT_SCRIPT.as_bytes();
    if std::fs::read(&paths.init_script).is_ok_and(|actual| actual == expected) {
        return Ok(());
    }
    let temporary = paths
        .directory
        .join(format!(".gradle-receipt.{}.tmp", uuid::Uuid::new_v4()));
    let mut options = std::fs::OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&temporary)?;
    file.write_all(expected)?;
    file.sync_all()?;
    std::fs::rename(temporary, &paths.init_script)?;
    Ok(())
}

fn verify_agent_task_init_script(paths: &AgentTaskPaths) -> Result<()> {
    if std::fs::read(&paths.init_script)
        .is_ok_and(|actual| actual == AGENT_TASK_INIT_SCRIPT.as_bytes())
    {
        Ok(())
    } else {
        Err(CliError::new(
            "GRADLE_INIT_SCRIPT_CHANGED",
            "The generation-attested Gradle init script changed after task admission; abort and begin again.",
        ))
    }
}

fn capture_agent_task_snapshot(workspace_root: &Path) -> Result<AgentTaskSnapshot> {
    let git_head = agent_task_git_value(workspace_root, &["rev-parse", "HEAD"]);
    let mut files = BTreeMap::new();
    if let Some(paths) = agent_task_git_paths(workspace_root) {
        for relative in paths {
            if agent_task_relevant_path(&relative) {
                files.insert(
                    relative.clone(),
                    agent_task_content_identity(&workspace_root.join(&relative))?,
                );
            }
        }
    } else {
        collect_agent_task_filesystem_paths(workspace_root, workspace_root, &mut files)?;
    }
    let sha256 = digest_serializable(&(&git_head, &files))?;
    Ok(AgentTaskSnapshot {
        git_head,
        files,
        sha256,
    })
}

fn validate_agent_task_snapshot(snapshot: &AgentTaskSnapshot) -> Result<()> {
    if snapshot.sha256 != digest_serializable(&(&snapshot.git_head, &snapshot.files))?
        || snapshot
            .files
            .keys()
            .any(|path| !agent_task_relative_evidence_path(path) || !agent_task_relevant_path(path))
        || snapshot.files.values().any(|identity| {
            matches!(identity, AgentTaskContentIdentity::Present { sha256 } if !agent_task_sha256(sha256))
        })
    {
        return Err(CliError::new(
            "AGENT_TASK_RECEIPT_INVALID",
            "Agent task snapshot digest does not match its file evidence.",
        ));
    }
    Ok(())
}

fn agent_task_git_paths(workspace_root: &Path) -> Option<BTreeSet<String>> {
    let output = std::process::Command::new("git")
        .args([
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
        ])
        .current_dir(workspace_root)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let mut paths = BTreeSet::new();
    for raw in output.stdout.split(|byte| *byte == 0) {
        if raw.is_empty() {
            continue;
        }
        let path = std::str::from_utf8(raw).ok()?;
        paths.insert(path.to_string());
    }
    Some(paths)
}

fn agent_task_git_value(workspace_root: &Path, args: &[&str]) -> Option<String> {
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workspace_root)
        .output()
        .ok()?;
    output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).trim().to_string())
        .filter(|value| !value.is_empty())
}

fn collect_agent_task_filesystem_paths(
    workspace_root: &Path,
    directory: &Path,
    files: &mut BTreeMap<String, AgentTaskContentIdentity>,
) -> Result<()> {
    let mut entries = std::fs::read_dir(directory)?.collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(std::fs::DirEntry::file_name);
    for entry in entries {
        let file_type = entry.file_type()?;
        if file_type.is_symlink() {
            continue;
        }
        let path = entry.path();
        if file_type.is_dir() {
            let name = entry.file_name();
            if matches!(
                name.to_str(),
                Some(".git" | ".gradle" | ".idea" | "build" | "out" | "target")
            ) {
                continue;
            }
            collect_agent_task_filesystem_paths(workspace_root, &path, files)?;
            continue;
        }
        if !file_type.is_file() {
            continue;
        }
        let relative = path.strip_prefix(workspace_root).map_err(|_| {
            CliError::new(
                "AGENT_TASK_WORKSPACE_INVALID",
                "Discovered file escaped the exact task workspace root.",
            )
        })?;
        let relative = agent_task_forward_slash_path(relative)?;
        if agent_task_relevant_path(&relative) {
            files.insert(relative, agent_task_content_identity(&path)?);
        }
    }
    Ok(())
}

fn agent_task_content_identity(path: &Path) -> Result<AgentTaskContentIdentity> {
    match std::fs::read(path) {
        Ok(bytes) => Ok(AgentTaskContentIdentity::Present {
            sha256: crate::manifest::sha256_bytes(&bytes),
        }),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            Ok(AgentTaskContentIdentity::Deleted)
        }
        Err(error) => Err(error.into()),
    }
}

fn agent_task_relevant_path(path: &str) -> bool {
    let path = path.trim_start_matches("./");
    let file = Path::new(path);
    let file_name = file.file_name().and_then(|value| value.to_str());
    let extension = file.extension().and_then(|value| value.to_str());
    matches!(extension, Some("kt" | "kts" | "java"))
        || matches!(
            file_name,
            Some(
                "settings.gradle"
                    | "settings.gradle.kts"
                    | "build.gradle"
                    | "build.gradle.kts"
                    | "gradle.properties"
                    | "gradlew"
                    | "gradlew.bat"
            )
        )
        || file_name.is_some_and(|name| name.ends_with(".versions.toml"))
        || path == ".kast/workflow.toml"
        || path.starts_with("gradle/")
        || path.starts_with("build-logic/")
        || path.contains("/gradle/")
        || path.contains("/build-logic/")
}

fn changed_agent_task_paths(
    baseline: &AgentTaskSnapshot,
    current: &AgentTaskSnapshot,
) -> Vec<String> {
    baseline
        .files
        .keys()
        .chain(current.files.keys())
        .cloned()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .filter(|path| baseline.files.get(path) != current.files.get(path))
        .collect()
}

fn agent_task_validation_paths(
    baseline: &AgentTaskSnapshot,
    current: &AgentTaskSnapshot,
) -> Vec<String> {
    changed_agent_task_paths(baseline, current)
        .into_iter()
        .filter(|path| path != ".kast/workflow.toml")
        .collect()
}

fn agent_task_forward_slash_path(path: &Path) -> Result<String> {
    let mut segments = Vec::new();
    for component in path.components() {
        match component {
            std::path::Component::Normal(segment) => segments.push(
                segment
                    .to_str()
                    .ok_or_else(|| {
                        CliError::new(
                            "AGENT_TASK_PATH_INVALID",
                            "Agent task paths must be valid UTF-8.",
                        )
                    })?
                    .to_string(),
            ),
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir
            | std::path::Component::RootDir
            | std::path::Component::Prefix(_) => {
                return Err(CliError::new(
                    "AGENT_TASK_PATH_INVALID",
                    "Agent task evidence paths must stay relative to the exact workspace root.",
                ));
            }
        }
    }
    Ok(if segments.is_empty() {
        ".".to_string()
    } else {
        segments.join("/")
    })
}

fn digest_serializable(value: &impl Serialize) -> Result<String> {
    Ok(crate::manifest::sha256_bytes(&serde_json::to_vec(value)?))
}

fn agent_task_validation_input_sha256(
    generation: &AgentTaskGenerationIdentity,
    gradle_model_sha256: &str,
    current: &AgentTaskSnapshot,
    policy_sha256: &str,
    plan: &[AgentTaskValidationTarget],
) -> Result<String> {
    digest_serializable(&(
        generation,
        gradle_model_sha256,
        current,
        policy_sha256,
        plan,
    ))
}

fn agent_task_policy_sha256(snapshot: &AgentTaskSnapshot) -> String {
    match snapshot.files.get(".kast/workflow.toml") {
        Some(AgentTaskContentIdentity::Present { sha256 }) => sha256.clone(),
        Some(AgentTaskContentIdentity::Deleted) | None => {
            crate::manifest::sha256_bytes(b"KAST_AGENT_TASK_NO_POLICY_V1")
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleModel {
    schema_version: u32,
    workspace_root: String,
    builds: Vec<AgentTaskGradleBuildModel>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleBuildModel {
    build_root: String,
    projects: Vec<AgentTaskGradleProjectModel>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleProjectModel {
    project_path: String,
    project_directory: String,
    source_sets: Vec<AgentTaskGradleSourceSetModel>,
    tasks: Vec<AgentTaskGradleTaskModel>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleSourceSetModel {
    name: String,
    source_directories: Vec<String>,
    build_tasks: Vec<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentTaskGradleTaskKind {
    Build,
    Test,
    Other,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleTaskModel {
    path: String,
    kind: AgentTaskGradleTaskKind,
    test_report_directories: Vec<String>,
}

fn resolve_agent_task_gradle_model(
    workspace_root: &Path,
    paths: &AgentTaskPaths,
    task_id: &str,
    snapshot: &AgentTaskSnapshot,
) -> Result<AgentTaskGradleModel> {
    verify_agent_task_init_script(paths)?;
    let wrapper = agent_task_gradle_wrapper(workspace_root)?;
    verify_agent_task_gradle_wrapper(workspace_root, &wrapper, snapshot)?;
    let receipt_path = paths.gradle_model_receipt(task_id);
    let _ = std::fs::remove_file(&receipt_path);
    let output = std::process::Command::new(&wrapper)
        .args(["--init-script"])
        .arg(&paths.init_script)
        .arg("--no-configuration-cache")
        .arg("help")
        .current_dir(workspace_root)
        .env("KAST_AGENT_TASK_GRADLE_MODEL_RECEIPT", &receipt_path)
        .env("KAST_AGENT_TASK_WORKSPACE_ROOT", workspace_root)
        .output()
        .map_err(|error| {
            CliError::new(
                "GRADLE_MODEL_UNAVAILABLE",
                format!("Could not execute exact-root Gradle wrapper: {error}"),
            )
        })?;
    verify_agent_task_init_script(paths)?;
    verify_agent_task_gradle_wrapper(workspace_root, &wrapper, snapshot)?;
    if !output.status.success() {
        let mut error = CliError::new(
            "GRADLE_MODEL_UNAVAILABLE",
            "The exact-root Gradle wrapper could not produce the typed project model.",
        );
        error.details.insert(
            "exitCode".to_string(),
            output.status.code().unwrap_or(-1).to_string(),
        );
        return Err(error);
    }
    let bytes = std::fs::read(&receipt_path).map_err(|error| {
        CliError::new(
            "GRADLE_MODEL_UNAVAILABLE",
            format!("Gradle did not write its typed model receipt: {error}"),
        )
    })?;
    let model: AgentTaskGradleModel = serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "GRADLE_MODEL_INVALID",
            format!("Gradle project model receipt is invalid: {error}"),
        )
    })?;
    validate_agent_task_gradle_model(workspace_root, model)
}

fn validate_agent_task_gradle_model(
    workspace_root: &Path,
    mut model: AgentTaskGradleModel,
) -> Result<AgentTaskGradleModel> {
    if model.schema_version != AGENT_TASK_MODEL_SCHEMA_VERSION
        || model.workspace_root != workspace_root.display().to_string()
        || model.builds.is_empty()
    {
        return Err(CliError::new(
            "GRADLE_MODEL_INVALID",
            "Gradle project model does not bind the exact workspace and supported schema.",
        ));
    }
    model
        .builds
        .sort_by(|left, right| left.build_root.cmp(&right.build_root));
    let mut build_roots = BTreeSet::new();
    for build in &mut model.builds {
        let build_root = validate_agent_task_relative_directory(&build.build_root, "buildRoot")?;
        if !build_roots.insert(build_root.clone()) || build.projects.is_empty() {
            return Err(CliError::new(
                "GRADLE_MODEL_INVALID",
                "Gradle model build roots must be unique and contain projects.",
            ));
        }
        build.build_root = build_root;
        build
            .projects
            .sort_by(|left, right| left.project_path.cmp(&right.project_path));
        let mut project_paths = BTreeSet::new();
        for project in &mut build.projects {
            validate_agent_task_gradle_project_path(&project.project_path)?;
            project.project_directory = validate_agent_task_relative_directory(
                &project.project_directory,
                "projectDirectory",
            )?;
            if !project_paths.insert(project.project_path.clone()) {
                return Err(CliError::new(
                    "GRADLE_MODEL_INVALID",
                    "Gradle project paths must be unique within one build.",
                ));
            }
            project
                .source_sets
                .sort_by(|left, right| left.name.cmp(&right.name));
            let mut source_set_names = BTreeSet::new();
            for source_set in &mut project.source_sets {
                validate_agent_task_source_set_name(&source_set.name)?;
                if !source_set_names.insert(source_set.name.clone()) {
                    return Err(CliError::new(
                        "GRADLE_MODEL_INVALID",
                        "Gradle source-set names must be unique within one project.",
                    ));
                }
                normalize_agent_task_string_set(&mut source_set.source_directories, |path| {
                    validate_agent_task_relative_directory(path, "sourceDirectory")
                })?;
                normalize_agent_task_string_set(&mut source_set.build_tasks, |task| {
                    validate_agent_task_gradle_task_path(task).map(str::to_string)
                })?;
            }
            project
                .tasks
                .sort_by(|left, right| left.path.cmp(&right.path));
            let mut task_paths = BTreeSet::new();
            for task in &mut project.tasks {
                validate_agent_task_gradle_task_path(&task.path)?;
                if !task_paths.insert(task.path.clone()) {
                    return Err(CliError::new(
                        "GRADLE_MODEL_INVALID",
                        "Gradle task paths must be unique within one project.",
                    ));
                }
                normalize_agent_task_string_set(&mut task.test_report_directories, |path| {
                    validate_agent_task_relative_directory(path, "testReportDirectory")
                })?;
                if task.kind != AgentTaskGradleTaskKind::Test
                    && !task.test_report_directories.is_empty()
                {
                    return Err(CliError::new(
                        "GRADLE_MODEL_INVALID",
                        "Only Gradle test tasks may own test-report directories.",
                    ));
                }
            }
            for source_set in &project.source_sets {
                if source_set
                    .build_tasks
                    .iter()
                    .any(|task| !task_paths.contains(task))
                {
                    return Err(CliError::new(
                        "GRADLE_MODEL_INVALID",
                        "A source-set build task is absent from its project's Gradle task model.",
                    ));
                }
            }
        }
    }
    Ok(model)
}

fn normalize_agent_task_string_set(
    values: &mut Vec<String>,
    normalize: impl Fn(&str) -> Result<String>,
) -> Result<()> {
    let normalized = values
        .iter()
        .map(|value| normalize(value))
        .collect::<Result<BTreeSet<_>>>()?;
    if normalized.len() != values.len() {
        return Err(CliError::new(
            "GRADLE_MODEL_INVALID",
            "Gradle model collections must not contain duplicate values.",
        ));
    }
    *values = normalized.into_iter().collect();
    Ok(())
}

fn agent_task_gradle_wrapper(workspace_root: &Path) -> Result<PathBuf> {
    let wrapper = if cfg!(windows) {
        workspace_root.join("gradlew.bat")
    } else {
        workspace_root.join("gradlew")
    };
    if !wrapper.is_file() {
        return Err(CliError::new(
            "GRADLE_WRAPPER_REQUIRED",
            format!(
                "Agent task proof requires the exact-root Gradle wrapper at {}.",
                wrapper.display()
            ),
        ));
    }
    Ok(wrapper)
}

fn verify_agent_task_gradle_wrapper(
    workspace_root: &Path,
    wrapper: &Path,
    snapshot: &AgentTaskSnapshot,
) -> Result<()> {
    let relative =
        agent_task_forward_slash_path(wrapper.strip_prefix(workspace_root).map_err(|_| {
            CliError::new(
                "GRADLE_WRAPPER_CHANGED",
                "The exact-root Gradle wrapper escaped the admitted workspace.",
            )
        })?)?;
    let expected = match snapshot.files.get(&relative) {
        Some(AgentTaskContentIdentity::Present { sha256 }) => sha256,
        Some(AgentTaskContentIdentity::Deleted) | None => {
            return Err(CliError::new(
                "GRADLE_WRAPPER_CHANGED",
                "The exact-root Gradle wrapper is absent from the admitted input snapshot.",
            ));
        }
    };
    let actual = crate::manifest::sha256_file(wrapper)?;
    if actual == *expected {
        Ok(())
    } else {
        Err(CliError::new(
            "GRADLE_WRAPPER_CHANGED",
            "The exact-root Gradle wrapper changed during task validation.",
        ))
    }
}

fn validate_agent_task_relative_directory(value: &str, label: &str) -> Result<String> {
    if value == "." {
        return Ok(value.to_string());
    }
    if value.is_empty()
        || value.trim() != value
        || value.starts_with(['/', '\\'])
        || value.contains('\\')
        || value.chars().any(char::is_control)
    {
        return Err(CliError::new(
            "GRADLE_MODEL_INVALID",
            format!("{label} must be a canonical workspace-relative path."),
        ));
    }
    let path = Path::new(value);
    if path.components().any(|component| {
        !matches!(component, std::path::Component::Normal(_))
            || matches!(component, std::path::Component::ParentDir)
    }) {
        return Err(CliError::new(
            "GRADLE_MODEL_INVALID",
            format!("{label} cannot escape or normalize outside the workspace."),
        ));
    }
    Ok(value.to_string())
}

fn validate_agent_task_gradle_project_path(value: &str) -> Result<&str> {
    if value == ":" {
        return Ok(value);
    }
    validate_agent_task_gradle_path(value, "project")
}

fn validate_agent_task_gradle_task_path(value: &str) -> Result<&str> {
    if value == ":" {
        return Err(CliError::new(
            "GRADLE_MODEL_INVALID",
            "Gradle task paths must name a task below a project path.",
        ));
    }
    validate_agent_task_gradle_path(value, "task")
}

fn validate_agent_task_gradle_path<'a>(value: &'a str, kind: &str) -> Result<&'a str> {
    if !value.starts_with(':')
        || value.contains(['/', '\\', '#'])
        || value.chars().any(char::is_control)
        || value.split(':').skip(1).any(|segment| {
            segment.is_empty()
                || !segment
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
        })
    {
        return Err(CliError::new(
            "GRADLE_MODEL_INVALID",
            format!("Gradle path is not a fully-qualified {kind} path: {value}"),
        ));
    }
    Ok(value)
}

fn validate_agent_task_source_set_name(value: &str) -> Result<&str> {
    if value.is_empty()
        || value.trim() != value
        || value.contains(['/', '\\', ':', '#'])
        || value.chars().any(char::is_control)
    {
        return Err(CliError::new(
            "GRADLE_MODEL_INVALID",
            format!("Gradle source-set name is invalid: {value}"),
        ));
    }
    Ok(value)
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AgentTaskWorkflow {
    schema_version: u32,
    gradle: AgentTaskWorkflowGradle,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AgentTaskWorkflowGradle {
    validation: Vec<AgentTaskWorkflowValidation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AgentTaskWorkflowValidation {
    build_root: String,
    project_path: String,
    #[serde(default)]
    source_sets: Option<Vec<String>>,
    build_tasks: Vec<String>,
    test_tasks: Vec<String>,
}

fn read_agent_task_workflow(
    workspace_root: &Path,
) -> std::result::Result<Option<AgentTaskWorkflow>, AgentTaskBlocker> {
    let path = workspace_root.join(".kast/workflow.toml");
    let contents = match std::fs::read_to_string(&path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(AgentTaskBlocker::new(
                "GRADLE_VALIDATION_POLICY_INVALID",
                format!("Cannot read .kast/workflow.toml: {error}"),
            ));
        }
    };
    let mut workflow: AgentTaskWorkflow = toml::from_str(&contents).map_err(|error| {
        AgentTaskBlocker::new(
            "GRADLE_VALIDATION_POLICY_INVALID",
            format!(".kast/workflow.toml is not valid strict policy: {error}"),
        )
    })?;
    if workflow.schema_version != 1 || workflow.gradle.validation.is_empty() {
        return Err(AgentTaskBlocker::new(
            "GRADLE_VALIDATION_POLICY_INVALID",
            ".kast/workflow.toml must use schema_version = 1 and contain gradle.validation entries.",
        ));
    }
    let mut identities = BTreeSet::new();
    for policy in &mut workflow.gradle.validation {
        policy.build_root =
            validate_agent_task_relative_directory(&policy.build_root, "build_root")
                .map_err(agent_task_policy_error)?;
        validate_agent_task_gradle_project_path(&policy.project_path)
            .map_err(agent_task_policy_error)?;
        if policy.build_tasks.is_empty() || policy.test_tasks.is_empty() {
            return Err(AgentTaskBlocker::new(
                "GRADLE_VALIDATION_POLICY_INVALID",
                "Every validation override requires non-empty build_tasks and test_tasks.",
            ));
        }
        normalize_agent_task_policy_tasks(&mut policy.build_tasks)?;
        normalize_agent_task_policy_tasks(&mut policy.test_tasks)?;
        if let Some(source_sets) = &mut policy.source_sets {
            if source_sets.is_empty() {
                return Err(AgentTaskBlocker::new(
                    "GRADLE_VALIDATION_POLICY_INVALID",
                    "source_sets must be omitted or contain at least one source-set name.",
                ));
            }
            let normalized = source_sets
                .iter()
                .map(|source_set| {
                    validate_agent_task_source_set_name(source_set)
                        .map(str::to_string)
                        .map_err(agent_task_policy_error)
                })
                .collect::<std::result::Result<BTreeSet<_>, _>>()?;
            if normalized.len() != source_sets.len() {
                return Err(AgentTaskBlocker::new(
                    "GRADLE_VALIDATION_POLICY_INVALID",
                    "source_sets must not contain duplicates.",
                ));
            }
            *source_sets = normalized.into_iter().collect();
        }
        let identity = (
            policy.build_root.clone(),
            policy.project_path.clone(),
            policy.source_sets.clone(),
        );
        if !identities.insert(identity) {
            return Err(AgentTaskBlocker::new(
                "GRADLE_VALIDATION_POLICY_INVALID",
                "Duplicate equally-specific Gradle validation overrides are not allowed.",
            ));
        }
    }
    Ok(Some(workflow))
}

fn agent_task_policy_error(error: CliError) -> AgentTaskBlocker {
    AgentTaskBlocker::new("GRADLE_VALIDATION_POLICY_INVALID", error.message)
}

fn normalize_agent_task_policy_tasks(
    tasks: &mut Vec<String>,
) -> std::result::Result<(), AgentTaskBlocker> {
    let normalized = tasks
        .iter()
        .map(|task| {
            validate_agent_task_gradle_task_path(task)
                .map(str::to_string)
                .map_err(agent_task_policy_error)
        })
        .collect::<std::result::Result<BTreeSet<_>, _>>()?;
    if normalized.len() != tasks.len() {
        return Err(AgentTaskBlocker::new(
            "GRADLE_VALIDATION_POLICY_INVALID",
            "Gradle validation task lists must not contain duplicates.",
        ));
    }
    *tasks = normalized.into_iter().collect();
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskValidationTarget {
    build_root: String,
    project_path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_set: Option<String>,
    build_tasks: Vec<String>,
    test_tasks: Vec<String>,
}

#[derive(Clone, Copy)]
struct AgentTaskModelOwner<'a> {
    build: &'a AgentTaskGradleBuildModel,
    project: &'a AgentTaskGradleProjectModel,
    source_set: Option<&'a AgentTaskGradleSourceSetModel>,
    configuration: bool,
}

fn plan_agent_task_gradle_validation(
    _workspace_root: &Path,
    model: &AgentTaskGradleModel,
    changed_paths: &[String],
    workflow: Option<&AgentTaskWorkflow>,
) -> std::result::Result<Vec<AgentTaskValidationTarget>, AgentTaskBlocker> {
    let mut targets = BTreeSet::new();
    for path in changed_paths {
        let owners = agent_task_model_owners(model, path);
        let selected = select_agent_task_model_owner(path, owners, workflow)?;
        let target = agent_task_validation_target(model, selected, workflow)?;
        targets.insert(target);
    }
    if targets.is_empty() {
        return Err(agent_task_policy_required(
            changed_paths.first().map_or("<unknown>", String::as_str),
            None,
        ));
    }
    Ok(targets.into_iter().collect())
}

fn agent_task_model_owners<'a>(
    model: &'a AgentTaskGradleModel,
    path: &str,
) -> Vec<AgentTaskModelOwner<'a>> {
    let mut source_owners = Vec::new();
    for build in &model.builds {
        for project in &build.projects {
            for source_set in &project.source_sets {
                if source_set
                    .source_directories
                    .iter()
                    .any(|source| agent_task_path_is_within(path, source))
                {
                    source_owners.push(AgentTaskModelOwner {
                        build,
                        project,
                        source_set: Some(source_set),
                        configuration: false,
                    });
                }
            }
        }
    }
    if !source_owners.is_empty() {
        return source_owners;
    }

    let mut project_owners = Vec::new();
    for build in &model.builds {
        for project in &build.projects {
            let project_build_files = [
                agent_task_join_relative(&project.project_directory, "build.gradle"),
                agent_task_join_relative(&project.project_directory, "build.gradle.kts"),
            ];
            if project_build_files
                .iter()
                .any(|candidate| candidate == path)
            {
                project_owners.push(AgentTaskModelOwner {
                    build,
                    project,
                    source_set: None,
                    configuration: true,
                });
            }
        }
    }
    if !project_owners.is_empty() {
        return project_owners;
    }

    let build = model
        .builds
        .iter()
        .filter(|build| {
            build.build_root == "." || agent_task_path_is_within(path, &build.build_root)
        })
        .max_by_key(|build| {
            if build.build_root == "." {
                0
            } else {
                build.build_root.len()
            }
        });
    let Some(build) = build else {
        return Vec::new();
    };
    let root_project = build
        .projects
        .iter()
        .find(|project| project.project_path == ":")
        .or_else(|| build.projects.first());
    root_project
        .map(|project| {
            vec![AgentTaskModelOwner {
                build,
                project,
                source_set: None,
                configuration: true,
            }]
        })
        .unwrap_or_default()
}

fn select_agent_task_model_owner<'a>(
    path: &str,
    owners: Vec<AgentTaskModelOwner<'a>>,
    workflow: Option<&AgentTaskWorkflow>,
) -> std::result::Result<AgentTaskModelOwner<'a>, AgentTaskBlocker> {
    if owners.len() == 1 {
        return Ok(owners[0]);
    }
    let Some(workflow) = workflow else {
        return Err(agent_task_policy_required(path, owners.first().copied()));
    };
    let matching = owners
        .iter()
        .copied()
        .filter(|owner| {
            workflow
                .gradle
                .validation
                .iter()
                .any(|policy| agent_task_policy_matches_owner(policy, *owner).is_some())
        })
        .collect::<Vec<_>>();
    if matching.len() == 1 {
        Ok(matching[0])
    } else {
        Err(agent_task_policy_required(path, owners.first().copied()))
    }
}

fn agent_task_validation_target(
    model: &AgentTaskGradleModel,
    owner: AgentTaskModelOwner<'_>,
    workflow: Option<&AgentTaskWorkflow>,
) -> std::result::Result<AgentTaskValidationTarget, AgentTaskBlocker> {
    if let Some(policy) = matching_agent_task_policy(owner, workflow)? {
        validate_agent_task_policy_against_model(model, policy)?;
        return Ok(AgentTaskValidationTarget {
            build_root: policy.build_root.clone(),
            project_path: policy.project_path.clone(),
            source_set: owner.source_set.map(|source_set| source_set.name.clone()),
            build_tasks: policy.build_tasks.clone(),
            test_tasks: policy.test_tasks.clone(),
        });
    }
    let build_tasks = if let Some(source_set) = owner.source_set {
        source_set.build_tasks.clone()
    } else {
        preferred_agent_task_build_tasks(owner.project)
    };
    let test_tasks = inferred_agent_task_test_tasks(owner);
    if build_tasks.is_empty() || test_tasks.is_empty() {
        return Err(agent_task_policy_required(
            owner
                .source_set
                .and_then(|source_set| source_set.source_directories.first())
                .map_or(&owner.project.project_directory, |path| path),
            Some(owner),
        ));
    }
    Ok(AgentTaskValidationTarget {
        build_root: owner.build.build_root.clone(),
        project_path: owner.project.project_path.clone(),
        source_set: owner.source_set.map(|source_set| source_set.name.clone()),
        build_tasks,
        test_tasks,
    })
}

fn preferred_agent_task_build_tasks(project: &AgentTaskGradleProjectModel) -> Vec<String> {
    for preferred in ["build", "check"] {
        let matches = project
            .tasks
            .iter()
            .filter(|task| {
                task.kind == AgentTaskGradleTaskKind::Build
                    && task.path.rsplit(':').next() == Some(preferred)
            })
            .map(|task| task.path.clone())
            .collect::<Vec<_>>();
        if !matches.is_empty() {
            return matches;
        }
    }
    Vec::new()
}

fn inferred_agent_task_test_tasks(owner: AgentTaskModelOwner<'_>) -> Vec<String> {
    let tests = owner
        .project
        .tasks
        .iter()
        .filter(|task| task.kind == AgentTaskGradleTaskKind::Test)
        .collect::<Vec<_>>();
    let Some(source_set) = owner.source_set else {
        return if tests.len() == 1 {
            vec![tests[0].path.clone()]
        } else {
            Vec::new()
        };
    };
    let source_name = source_set.name.to_ascii_lowercase();
    if source_name.contains("test") {
        let matching = tests
            .iter()
            .filter(|task| {
                let task_name = task.path.rsplit(':').next().unwrap_or_default();
                task_name.eq_ignore_ascii_case(&source_set.name)
                    || task_name.to_ascii_lowercase().contains(&source_name)
            })
            .map(|task| task.path.clone())
            .collect::<Vec<_>>();
        if matching.len() == 1 {
            return matching;
        }
        if tests.len() == 1 {
            return vec![tests[0].path.clone()];
        }
        return Vec::new();
    }
    if tests.len() == 1 {
        vec![tests[0].path.clone()]
    } else {
        Vec::new()
    }
}

fn matching_agent_task_policy<'a>(
    owner: AgentTaskModelOwner<'_>,
    workflow: Option<&'a AgentTaskWorkflow>,
) -> std::result::Result<Option<&'a AgentTaskWorkflowValidation>, AgentTaskBlocker> {
    let Some(workflow) = workflow else {
        return Ok(None);
    };
    let mut matches = workflow
        .gradle
        .validation
        .iter()
        .filter_map(|policy| {
            agent_task_policy_matches_owner(policy, owner).map(|rank| (rank, policy))
        })
        .collect::<Vec<_>>();
    matches.sort_by_key(|(rank, _)| std::cmp::Reverse(*rank));
    let Some((best_rank, best)) = matches.first().copied() else {
        return Ok(None);
    };
    if matches.iter().skip(1).any(|(rank, _)| *rank == best_rank) {
        return Err(AgentTaskBlocker::new(
            "GRADLE_VALIDATION_POLICY_INVALID",
            "Multiple equally-specific Gradle validation policies match one model owner.",
        ));
    }
    Ok(Some(best))
}

fn agent_task_policy_matches_owner(
    policy: &AgentTaskWorkflowValidation,
    owner: AgentTaskModelOwner<'_>,
) -> Option<u8> {
    if policy.build_root != owner.build.build_root
        || policy.project_path != owner.project.project_path
    {
        return None;
    }
    match (&policy.source_sets, owner.source_set) {
        (Some(source_sets), Some(source_set)) if source_sets.contains(&source_set.name) => Some(2),
        (Some(_), _) => None,
        (None, _) => Some(1),
    }
}

fn validate_agent_task_policy_against_model(
    model: &AgentTaskGradleModel,
    policy: &AgentTaskWorkflowValidation,
) -> std::result::Result<(), AgentTaskBlocker> {
    let project = model
        .builds
        .iter()
        .find(|build| build.build_root == policy.build_root)
        .and_then(|build| {
            build
                .projects
                .iter()
                .find(|project| project.project_path == policy.project_path)
        })
        .ok_or_else(|| {
            AgentTaskBlocker::new(
                "GRADLE_VALIDATION_POLICY_INVALID",
                "Gradle validation policy names a build root or project absent from the model.",
            )
        })?;
    if let Some(source_sets) = &policy.source_sets
        && source_sets.iter().any(|source_set| {
            !project
                .source_sets
                .iter()
                .any(|model| model.name == *source_set)
        })
    {
        return Err(AgentTaskBlocker::new(
            "GRADLE_VALIDATION_POLICY_INVALID",
            "Gradle validation policy names a source set absent from the model.",
        ));
    }
    for task in &policy.build_tasks {
        if !project.tasks.iter().any(|model| model.path == *task) {
            return Err(AgentTaskBlocker::new(
                "GRADLE_VALIDATION_POLICY_INVALID",
                format!("Configured build task is absent from the Gradle model: {task}"),
            ));
        }
    }
    for task in &policy.test_tasks {
        if !project
            .tasks
            .iter()
            .any(|model| model.path == *task && model.kind == AgentTaskGradleTaskKind::Test)
        {
            return Err(AgentTaskBlocker::new(
                "GRADLE_VALIDATION_POLICY_INVALID",
                format!("Configured test task is absent or is not a Gradle Test task: {task}"),
            ));
        }
    }
    Ok(())
}

fn agent_task_policy_required(
    path: &str,
    owner: Option<AgentTaskModelOwner<'_>>,
) -> AgentTaskBlocker {
    let build_root = owner.map_or(".", |owner| owner.build.build_root.as_str());
    let project_path = owner.map_or(":", |owner| owner.project.project_path.as_str());
    let source_sets = owner
        .and_then(|owner| owner.source_set)
        .map(|source_set| format!("source_sets = [\"{}\"]\n", source_set.name))
        .unwrap_or_default();
    let stanza = format!(
        "schema_version = 1\n\n[[gradle.validation]]\nbuild_root = \"{build_root}\"\nproject_path = \"{project_path}\"\n{source_sets}build_tasks = [\"{project_path}:classes\"]\ntest_tasks = [\"{project_path}:test\"]"
    )
    .replace("::", ":");
    AgentTaskBlocker::new(
        "GRADLE_VALIDATION_POLICY_REQUIRED",
        "The Gradle project model cannot prove one unambiguous build-and-test policy.",
    )
    .detail("path", path.to_string())
    .detail("workflowPath", ".kast/workflow.toml")
    .detail("stanza", stanza)
}

fn agent_task_path_is_within(path: &str, directory: &str) -> bool {
    directory == "."
        || path == directory
        || path
            .strip_prefix(directory)
            .is_some_and(|suffix| suffix.starts_with('/'))
}

fn agent_task_join_relative(parent: &str, child: &str) -> String {
    if parent == "." {
        child.to_string()
    } else {
        format!("{parent}/{child}")
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentTaskGradleOutcomeReceipt {
    schema_version: u32,
    input_sha256: String,
    build_failed: bool,
    tasks: Vec<AgentTaskGradleTaskProof>,
}

fn execute_agent_task_gradle_plan(
    workspace_root: &Path,
    paths: &AgentTaskPaths,
    task_id: &str,
    input_sha256: &str,
    model: &AgentTaskGradleModel,
    plan: &[AgentTaskValidationTarget],
    snapshot: &AgentTaskSnapshot,
) -> std::result::Result<
    (
        Vec<AgentTaskGradleInvocationProof>,
        Vec<AgentTaskTestReportProof>,
    ),
    AgentTaskBlocker,
> {
    let wrapper = agent_task_gradle_wrapper(workspace_root).map_err(|error| {
        AgentTaskBlocker::new(error.code, error.message)
            .detail("workspaceRoot", workspace_root.display().to_string())
    })?;
    verify_agent_task_gradle_wrapper(workspace_root, &wrapper, snapshot)
        .map_err(|error| AgentTaskBlocker::new(error.code, error.message))?;
    verify_agent_task_init_script(paths)
        .map_err(|error| AgentTaskBlocker::new(error.code, error.message))?;
    let grouped = group_agent_task_validation_plan(plan);
    let mut invocations = Vec::new();
    let mut report_directories = BTreeSet::new();
    let mut required_test_reports = Vec::new();
    for (ordinal, (build_root, (build_tasks, test_tasks))) in grouped.into_iter().enumerate() {
        let build_directory = if build_root == "." {
            workspace_root.to_path_buf()
        } else {
            workspace_root.join(&build_root)
        };
        if !build_directory.is_dir() {
            return Err(AgentTaskBlocker::new(
                "GRADLE_MODEL_STALE",
                "A Gradle build root from the task receipt is no longer available.",
            )
            .detail("buildRoot", build_root));
        }
        let receipt_path = paths.gradle_outcome_receipt(task_id, ordinal);
        let _ = std::fs::remove_file(&receipt_path);
        let requested = build_tasks
            .iter()
            .chain(&test_tasks)
            .cloned()
            .collect::<BTreeSet<_>>();
        let output = std::process::Command::new(&wrapper)
            .args(["--init-script"])
            .arg(&paths.init_script)
            .arg("--no-configuration-cache")
            .arg("-p")
            .arg(&build_directory)
            .args(&requested)
            .current_dir(workspace_root)
            .env("KAST_AGENT_TASK_GRADLE_RECEIPT", &receipt_path)
            .env("KAST_AGENT_TASK_INPUT_SHA256", input_sha256)
            .output()
            .map_err(|error| {
                AgentTaskBlocker::new(
                    "GRADLE_VALIDATION_FAILED",
                    format!("Could not execute the exact-root Gradle wrapper: {error}"),
                )
                .detail("buildRoot", build_root.clone())
            })?;
        verify_agent_task_init_script(paths)
            .map_err(|error| AgentTaskBlocker::new(error.code, error.message))?;
        verify_agent_task_gradle_wrapper(workspace_root, &wrapper, snapshot)
            .map_err(|error| AgentTaskBlocker::new(error.code, error.message))?;
        let receipt = read_agent_task_gradle_outcome_receipt(&receipt_path, input_sha256)?;
        if !output.status.success() || receipt.build_failed {
            return Err(AgentTaskBlocker::new(
                "GRADLE_VALIDATION_FAILED",
                "Gradle validation did not complete successfully.",
            )
            .detail("buildRoot", build_root)
            .detail("exitCode", output.status.code().unwrap_or(-1).to_string()));
        }
        let observed = receipt
            .tasks
            .iter()
            .map(|task| (task.path.as_str(), task.outcome))
            .collect::<BTreeMap<_, _>>();
        for task in &build_tasks {
            let Some(outcome) = observed.get(task.as_str()).copied() else {
                return Err(agent_task_unobserved_gradle_task(task, &build_root, false));
            };
            if !outcome.is_valid_proof() {
                return Err(agent_task_invalid_gradle_task(
                    task,
                    &build_root,
                    outcome,
                    false,
                ));
            }
        }
        for task in &test_tasks {
            let Some(outcome) = observed.get(task.as_str()).copied() else {
                return Err(agent_task_unobserved_gradle_task(task, &build_root, true));
            };
            if !outcome.is_valid_proof() {
                return Err(agent_task_invalid_gradle_task(
                    task,
                    &build_root,
                    outcome,
                    true,
                ));
            }
            let directories = agent_task_report_directories(model, &build_root, task);
            if directories.is_empty() {
                return Err(agent_task_test_report_required(
                    task,
                    &build_root,
                    "The Gradle model did not prove a report directory for the selected test task.",
                ));
            }
            for directory in &directories {
                report_directories.insert(directory.clone());
            }
            required_test_reports.push((build_root.clone(), task.clone(), directories));
        }
        invocations.push(AgentTaskGradleInvocationProof {
            build_root,
            input_sha256: input_sha256.to_string(),
            build_tasks,
            test_tasks,
            observed_tasks: receipt.tasks,
        });
    }
    if invocations.iter().all(|proof| proof.test_tasks.is_empty()) {
        return Err(AgentTaskBlocker::new(
            "GRADLE_TEST_PROOF_REQUIRED",
            "Agent task completion requires at least one observed valid Gradle test task.",
        ));
    }
    let reports = collect_agent_task_test_reports(workspace_root, &report_directories)?;
    for (build_root, task, directories) in required_test_reports {
        if !reports.iter().any(|report| {
            directories
                .iter()
                .any(|directory| agent_task_path_is_within(&report.path, directory))
        }) {
            return Err(agent_task_test_report_required(
                &task,
                &build_root,
                "The selected test task produced no deterministic XML report digest.",
            ));
        }
    }
    Ok((invocations, reports))
}

fn agent_task_test_report_required(
    task: &str,
    build_root: &str,
    message: &str,
) -> AgentTaskBlocker {
    AgentTaskBlocker::new("GRADLE_TEST_REPORT_REQUIRED", message)
        .detail("buildRoot", build_root.to_string())
        .detail("task", task.to_string())
}

fn group_agent_task_validation_plan(
    plan: &[AgentTaskValidationTarget],
) -> BTreeMap<String, (Vec<String>, Vec<String>)> {
    let mut grouped: BTreeMap<String, (BTreeSet<String>, BTreeSet<String>)> = BTreeMap::new();
    for target in plan {
        let group = grouped.entry(target.build_root.clone()).or_default();
        group.0.extend(target.build_tasks.iter().cloned());
        group.1.extend(target.test_tasks.iter().cloned());
    }
    grouped
        .into_iter()
        .map(|(root, (build, test))| {
            (
                root,
                (build.into_iter().collect(), test.into_iter().collect()),
            )
        })
        .collect()
}

fn read_agent_task_gradle_outcome_receipt(
    path: &Path,
    input_sha256: &str,
) -> std::result::Result<AgentTaskGradleOutcomeReceipt, AgentTaskBlocker> {
    let bytes = std::fs::read(path).map_err(|error| {
        AgentTaskBlocker::new(
            "GRADLE_RECEIPT_MISSING",
            format!("Gradle did not write its structured task outcome receipt: {error}"),
        )
    })?;
    let mut receipt: AgentTaskGradleOutcomeReceipt =
        serde_json::from_slice(&bytes).map_err(|error| {
            AgentTaskBlocker::new(
                "GRADLE_RECEIPT_INVALID",
                format!("Gradle task outcome receipt is invalid: {error}"),
            )
        })?;
    if receipt.schema_version != AGENT_TASK_GRADLE_RECEIPT_SCHEMA_VERSION
        || receipt.input_sha256 != input_sha256
    {
        return Err(AgentTaskBlocker::new(
            "GRADLE_RECEIPT_INVALID",
            "Gradle task outcome receipt does not bind the current validation input.",
        ));
    }
    receipt
        .tasks
        .sort_by(|left, right| left.path.cmp(&right.path));
    let mut paths = BTreeSet::new();
    for task in &receipt.tasks {
        validate_agent_task_gradle_task_path(&task.path)
            .map_err(|error| AgentTaskBlocker::new("GRADLE_RECEIPT_INVALID", error.message))?;
        if !paths.insert(task.path.clone()) {
            return Err(AgentTaskBlocker::new(
                "GRADLE_RECEIPT_INVALID",
                "Gradle task outcome receipt contains duplicate task paths.",
            ));
        }
    }
    Ok(receipt)
}

fn agent_task_unobserved_gradle_task(task: &str, build_root: &str, test: bool) -> AgentTaskBlocker {
    AgentTaskBlocker::new(
        if test {
            "GRADLE_TEST_TASK_UNOBSERVED"
        } else {
            "GRADLE_BUILD_TASK_UNOBSERVED"
        },
        "A required Gradle task was not observed in the executed task graph.",
    )
    .detail("buildRoot", build_root.to_string())
    .detail("task", task.to_string())
}

fn agent_task_invalid_gradle_task(
    task: &str,
    build_root: &str,
    outcome: AgentTaskGradleOutcome,
    test: bool,
) -> AgentTaskBlocker {
    AgentTaskBlocker::new(
        if test {
            "GRADLE_TEST_TASK_INVALID"
        } else {
            "GRADLE_BUILD_TASK_INVALID"
        },
        "A required Gradle task did not produce SUCCESS, UP_TO_DATE, or FROM_CACHE proof.",
    )
    .detail("buildRoot", build_root.to_string())
    .detail("task", task.to_string())
    .detail("outcome", format!("{outcome:?}").to_ascii_uppercase())
}

fn agent_task_report_directories(
    model: &AgentTaskGradleModel,
    build_root: &str,
    task_path: &str,
) -> Vec<String> {
    model
        .builds
        .iter()
        .find(|build| build.build_root == build_root)
        .into_iter()
        .flat_map(|build| &build.projects)
        .flat_map(|project| &project.tasks)
        .filter(|task| task.path == task_path && task.kind == AgentTaskGradleTaskKind::Test)
        .flat_map(|task| task.test_report_directories.iter().cloned())
        .collect()
}

fn collect_agent_task_test_reports(
    workspace_root: &Path,
    directories: &BTreeSet<String>,
) -> std::result::Result<Vec<AgentTaskTestReportProof>, AgentTaskBlocker> {
    let mut reports = Vec::new();
    for relative in directories {
        let directory = if relative == "." {
            workspace_root.to_path_buf()
        } else {
            workspace_root.join(relative)
        };
        if !directory.exists() {
            continue;
        }
        collect_agent_task_test_report_directory(workspace_root, &directory, &mut reports)?;
    }
    reports.sort_by(|left, right| left.path.cmp(&right.path));
    Ok(reports)
}

fn collect_agent_task_test_report_directory(
    workspace_root: &Path,
    directory: &Path,
    reports: &mut Vec<AgentTaskTestReportProof>,
) -> std::result::Result<(), AgentTaskBlocker> {
    let mut entries = std::fs::read_dir(directory)
        .map_err(|error| {
            AgentTaskBlocker::new(
                "GRADLE_TEST_REPORT_UNAVAILABLE",
                format!("Cannot read Gradle test report directory: {error}"),
            )
        })?
        .collect::<std::io::Result<Vec<_>>>()
        .map_err(|error| {
            AgentTaskBlocker::new(
                "GRADLE_TEST_REPORT_UNAVAILABLE",
                format!("Cannot enumerate Gradle test reports: {error}"),
            )
        })?;
    entries.sort_by_key(std::fs::DirEntry::file_name);
    for entry in entries {
        let entry_path = entry.path();
        let file_type = entry.file_type().map_err(|error| {
            AgentTaskBlocker::new(
                "GRADLE_TEST_REPORT_UNAVAILABLE",
                format!("Cannot inspect Gradle test report: {error}"),
            )
        })?;
        if file_type.is_symlink() {
            continue;
        }
        if file_type.is_dir() {
            collect_agent_task_test_report_directory(workspace_root, &entry_path, reports)?;
        } else if file_type.is_file()
            && entry_path.extension().and_then(|value| value.to_str()) == Some("xml")
        {
            let relative = entry_path.strip_prefix(workspace_root).map_err(|_| {
                AgentTaskBlocker::new(
                    "GRADLE_TEST_REPORT_INVALID",
                    "Gradle test report escaped the exact workspace root.",
                )
            })?;
            let path = agent_task_forward_slash_path(relative).map_err(|error| {
                AgentTaskBlocker::new("GRADLE_TEST_REPORT_INVALID", error.message)
            })?;
            let sha256 = crate::manifest::sha256_file(&entry_path).map_err(|error| {
                AgentTaskBlocker::new(
                    "GRADLE_TEST_REPORT_UNAVAILABLE",
                    format!("Cannot hash Gradle test report: {error}"),
                )
            })?;
            reports.push(AgentTaskTestReportProof { path, sha256 });
        }
    }
    Ok(())
}

fn collect_agent_task_diagnostics(
    workspace_root: &Path,
    snapshot: &AgentTaskSnapshot,
    paths: &[String],
) -> std::result::Result<AgentTaskDiagnosticsProof, AgentTaskBlocker> {
    let envelope = execute_agent_diagnostics(AgentDiagnosticsArgs {
        runtime: AgentRuntimeArgs {
            workspace_root: Some(workspace_root.to_path_buf()),
            backend_name: None,
            lease_id: None,
        },
        file_paths: paths.to_vec(),
        skip_refresh: false,
        limit: 500,
        page_token: None,
        view: AgentDiagnosticsViewArgs {
            verbose: true,
            ..AgentDiagnosticsViewArgs::default()
        },
    });
    if !envelope.ok {
        let error = envelope.error.unwrap_or_else(|| {
            agent_error(
                "AGENT_TASK_DIAGNOSTICS_FAILED",
                "Compiler-backed diagnostics failed without typed evidence.",
            )
        });
        return Err(AgentTaskBlocker {
            code: error.code,
            message: error.message,
            details: error
                .details
                .into_iter()
                .map(|(key, value)| (key, value.to_string()))
                .collect(),
        });
    }
    let result = envelope.result.ok_or_else(|| {
        AgentTaskBlocker::new(
            "AGENT_TASK_DIAGNOSTICS_INVALID",
            "Compiler-backed diagnostics returned no validated result.",
        )
    })?;
    let diagnostics = result
        .get("steps")
        .and_then(Value::as_array)
        .and_then(|steps| {
            steps
                .iter()
                .find(|step| step.get("method").and_then(Value::as_str) == Some("raw/diagnostics"))
        })
        .and_then(|step| step.get("result"))
        .ok_or_else(|| {
            AgentTaskBlocker::new(
                "AGENT_TASK_DIAGNOSTICS_INVALID",
                "Compiler-backed diagnostics omitted its typed diagnostic result.",
            )
        })?;
    let error_count = diagnostics
        .pointer("/severityCounts/error")
        .and_then(Value::as_u64)
        .and_then(|value| usize::try_from(value).ok())
        .ok_or_else(|| {
            AgentTaskBlocker::new(
                "AGENT_TASK_DIAGNOSTICS_INVALID",
                "Compiler-backed diagnostics omitted a valid error count.",
            )
        })?;
    let semantic_outcome = diagnostics
        .get("semanticOutcome")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if semantic_outcome != "COMPLETE" || error_count != 0 {
        return Err(AgentTaskBlocker::new(
            "KOTLIN_DIAGNOSTICS_REQUIRED",
            "Changed Kotlin files require complete current diagnostics with zero errors.",
        )
        .detail("semanticOutcome", semantic_outcome.to_string())
        .detail("errorCount", error_count.to_string()));
    }
    let diagnostic_hashes = diagnostics
        .get("fileHashes")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            AgentTaskBlocker::new(
                "AGENT_TASK_DIAGNOSTICS_INVALID",
                "Compiler-backed diagnostics omitted file hashes from its read epoch.",
            )
        })?;
    let mut files = BTreeMap::new();
    for path in paths {
        let expected = match snapshot.files.get(path) {
            Some(AgentTaskContentIdentity::Present { sha256 }) => sha256,
            _ => {
                return Err(AgentTaskBlocker::new(
                    "KOTLIN_DIAGNOSTICS_REQUIRED",
                    "Deleted Kotlin files cannot produce current compiler diagnostics.",
                )
                .detail("path", path.clone()));
            }
        };
        let absolute = workspace_root.join(path).display().to_string();
        let actual = diagnostic_hashes.iter().find_map(|entry| {
            let file_path = entry.get("filePath")?.as_str()?;
            (file_path == absolute || file_path == path)
                .then(|| entry.get("hash")?.as_str().map(str::to_string))
                .flatten()
        });
        if actual.as_deref() != Some(expected) {
            return Err(AgentTaskBlocker::new(
                "KOTLIN_DIAGNOSTICS_STALE",
                "Compiler-backed diagnostics do not bind the current Kotlin content hash.",
            )
            .detail("path", path.clone())
            .detail("expectedSha256", expected.clone())
            .detail(
                "actualSha256",
                actual.unwrap_or_else(|| "missing".to_string()),
            ));
        }
        files.insert(path.clone(), expected.clone());
    }
    Ok(AgentTaskDiagnosticsProof {
        files,
        error_count,
        semantic_outcome: semantic_outcome.to_string(),
    })
}

#[cfg(test)]
mod agent_task_core_tests {
    use super::*;

    fn model(test_tasks: &[&str]) -> AgentTaskGradleModel {
        AgentTaskGradleModel {
            schema_version: 1,
            workspace_root: "/workspace".to_string(),
            builds: vec![AgentTaskGradleBuildModel {
                build_root: ".".to_string(),
                projects: vec![AgentTaskGradleProjectModel {
                    project_path: ":".to_string(),
                    project_directory: ".".to_string(),
                    source_sets: vec![AgentTaskGradleSourceSetModel {
                        name: "main".to_string(),
                        source_directories: vec!["src/main/java".to_string()],
                        build_tasks: vec![":classes".to_string()],
                    }],
                    tasks: std::iter::once(AgentTaskGradleTaskModel {
                        path: ":classes".to_string(),
                        kind: AgentTaskGradleTaskKind::Build,
                        test_report_directories: Vec::new(),
                    })
                    .chain(test_tasks.iter().map(|path| AgentTaskGradleTaskModel {
                        path: (*path).to_string(),
                        kind: AgentTaskGradleTaskKind::Test,
                        test_report_directories: vec![format!(
                            "build/test-results/{}",
                            path.trim_start_matches(':')
                        )],
                    }))
                    .collect(),
                }],
            }],
        }
    }

    #[test]
    fn root_project_is_valid_but_root_task_is_not() {
        assert_eq!(
            validate_agent_task_gradle_project_path(":").expect("root project"),
            ":"
        );
        assert!(validate_agent_task_gradle_task_path(":").is_err());
        assert!(
            validate_agent_task_gradle_model(Path::new("/workspace"), model(&[":test"])).is_ok()
        );
    }

    #[test]
    fn provider_start_paths_resolve_through_the_core_to_the_wrapper_root() {
        let temp = tempfile::tempdir().expect("tempdir");
        let root = temp.path().join("workspace");
        let included = root.join("build-logic");
        let source = included.join("src/main/kotlin");
        let build_file = ["build", "gradle"].join(".");
        std::fs::create_dir_all(&source).expect("source directory");
        std::fs::write(root.join("gradlew"), "").expect("root wrapper");
        std::fs::write(included.join(build_file), "").expect("included build");

        assert_eq!(
            resolve_agent_task_start_path(&source).expect("provider workspace"),
            root.canonicalize().expect("canonical root")
        );
        assert_eq!(
            resolve_agent_task_workspace(AgentTaskWorkspaceArgs {
                workspace_root: Some(included.clone()),
            })
            .expect("explicit exact workspace"),
            included.canonicalize().expect("canonical included build")
        );
    }

    #[test]
    fn automatic_inference_requires_one_unambiguous_test_task() {
        let simple = model(&[":test"]);
        let simple = plan_agent_task_gradle_validation(
            Path::new("/workspace"),
            &simple,
            &["src/main/java/Example.java".to_string()],
            None,
        )
        .expect("simple JVM policy");
        assert_eq!(simple[0].build_tasks, vec![":classes"]);
        assert_eq!(simple[0].test_tasks, vec![":test"]);

        let custom_suite = model(&[":test", ":integrationTest"]);
        let blocker = plan_agent_task_gradle_validation(
            Path::new("/workspace"),
            &custom_suite,
            &["src/main/java/Example.java".to_string()],
            None,
        )
        .expect_err("custom suites require explicit policy");
        assert_eq!(blocker.code, "GRADLE_VALIDATION_POLICY_REQUIRED");
        assert!(blocker.details["stanza"].contains("[[gradle.validation]]"));
        let workflow = AgentTaskWorkflow {
            schema_version: 1,
            gradle: AgentTaskWorkflowGradle {
                validation: vec![AgentTaskWorkflowValidation {
                    build_root: ".".to_string(),
                    project_path: ":".to_string(),
                    source_sets: Some(vec!["main".to_string()]),
                    build_tasks: vec![":classes".to_string()],
                    test_tasks: vec![":integrationTest".to_string()],
                }],
            },
        };
        let selected = plan_agent_task_gradle_validation(
            Path::new("/workspace"),
            &custom_suite,
            &["src/main/java/Example.java".to_string()],
            Some(&workflow),
        )
        .expect("explicit custom suite policy");
        assert_eq!(selected[0].test_tasks, vec![":integrationTest"]);

        let mut ambiguous_test_source = model(&[":test", ":integrationTest"]);
        let source_set = &mut ambiguous_test_source.builds[0].projects[0].source_sets[0];
        source_set.name = "test".to_string();
        source_set.source_directories = vec!["src/test/java".to_string()];
        let blocker = plan_agent_task_gradle_validation(
            Path::new("/workspace"),
            &ambiguous_test_source,
            &["src/test/java/ExampleTest.java".to_string()],
            None,
        )
        .expect_err("multiple matching test tasks require policy");
        assert_eq!(blocker.code, "GRADLE_VALIDATION_POLICY_REQUIRED");
    }

    #[test]
    fn included_build_configuration_uses_the_most_specific_build_root() {
        let mut composite = model(&[":test"]);
        let mut included = composite.builds[0].clone();
        included.build_root = "included".to_string();
        included.projects[0].project_directory = "included".to_string();
        included.projects[0].source_sets[0].source_directories =
            vec!["included/src/main/java".to_string()];
        included.projects[0].source_sets[0].build_tasks = vec![":build".to_string()];
        included.projects[0].tasks[0].path = ":build".to_string();
        composite.builds.push(included);
        let plan = plan_agent_task_gradle_validation(
            Path::new("/workspace"),
            &composite,
            &["included/settings.gradle".to_string()],
            None,
        )
        .expect("included build policy");
        assert_eq!(plan[0].build_root, "included");
        assert_eq!(plan[0].build_tasks, vec![":build"]);
        assert_eq!(plan[0].test_tasks, vec![":test"]);
    }

    #[test]
    fn workflow_policy_is_strict_and_cannot_escape_the_workspace() {
        let temp = tempfile::tempdir().expect("tempdir");
        let policy_dir = temp.path().join(".kast");
        std::fs::create_dir_all(&policy_dir).expect("policy dir");
        let policy = policy_dir.join("workflow.toml");
        std::fs::write(
            &policy,
            "schema_version = 1\n\n[[gradle.validation]]\nbuild_root = \".\"\nproject_path = \":\"\nsource_sets = [\"main\"]\nbuild_tasks = [\":classes\"]\ntest_tasks = [\":test\"]\n",
        )
        .expect("valid policy");
        assert!(
            read_agent_task_workflow(temp.path())
                .expect("policy")
                .is_some()
        );

        std::fs::write(
            &policy,
            "schema_version = 1\nunknown = true\n\n[[gradle.validation]]\nbuild_root = \".\"\nproject_path = \":\"\nbuild_tasks = [\":classes\"]\ntest_tasks = [\":test\"]\n",
        )
        .expect("unknown policy field");
        assert_eq!(
            read_agent_task_workflow(temp.path())
                .expect_err("unknown fields fail")
                .code,
            "GRADLE_VALIDATION_POLICY_INVALID",
        );

        std::fs::write(
            &policy,
            "schema_version = 1\n\n[[gradle.validation]]\nbuild_root = \"../other\"\nproject_path = \":\"\nbuild_tasks = [\":classes\"]\ntest_tasks = [\":test\"]\n",
        )
        .expect("escaping policy");
        assert_eq!(
            read_agent_task_workflow(temp.path())
                .expect_err("escaping roots fail")
                .code,
            "GRADLE_VALIDATION_POLICY_INVALID",
        );

        let stanza = "[[gradle.validation]]\nbuild_root = \".\"\nproject_path = \":\"\nsource_sets = [\"main\"]\nbuild_tasks = [\":classes\"]\ntest_tasks = [\":test\"]\n";
        std::fs::write(&policy, format!("schema_version = 1\n\n{stanza}\n{stanza}"))
            .expect("duplicate policy");
        assert_eq!(
            read_agent_task_workflow(temp.path())
                .expect_err("duplicate policies fail")
                .code,
            "GRADLE_VALIDATION_POLICY_INVALID",
        );
    }

    #[test]
    fn only_successful_observed_outcomes_are_completion_proof() {
        for accepted in [
            AgentTaskGradleOutcome::Success,
            AgentTaskGradleOutcome::UpToDate,
            AgentTaskGradleOutcome::FromCache,
        ] {
            assert!(accepted.is_valid_proof());
        }
        for rejected in [
            AgentTaskGradleOutcome::NoSource,
            AgentTaskGradleOutcome::Skipped,
            AgentTaskGradleOutcome::Failed,
        ] {
            assert!(!rejected.is_valid_proof());
        }
    }

    #[test]
    fn relevant_input_filter_ignores_unowned_files() {
        for relevant in [
            "src/main/java/Example.java",
            "gradlew",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/libs.versions.toml",
            "build-logic/src/main/java/Plugin.java",
        ] {
            assert!(agent_task_relevant_path(relevant), "{relevant}");
        }
        for unrelated in ["README.md", "target/output.bin", "docs/guide.md"] {
            assert!(!agent_task_relevant_path(unrelated), "{unrelated}");
        }
        let baseline = AgentTaskSnapshot {
            git_head: None,
            files: BTreeMap::from([(
                ".kast/workflow.toml".to_string(),
                AgentTaskContentIdentity::Present {
                    sha256: "0".repeat(64),
                },
            )]),
            sha256: String::new(),
        };
        let current = AgentTaskSnapshot {
            git_head: None,
            files: BTreeMap::from([(
                ".kast/workflow.toml".to_string(),
                AgentTaskContentIdentity::Present {
                    sha256: "1".repeat(64),
                },
            )]),
            sha256: String::new(),
        };
        assert!(agent_task_validation_paths(&baseline, &current).is_empty());
    }
}
