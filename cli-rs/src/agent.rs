use crate::SCHEMA_VERSION;
use crate::cli::ReadyTarget;
use crate::cli::{
    AgentArgs, AgentCallArgs, AgentCommand, AgentDiscoverArgs, AgentFileOutlineArgs,
    AgentFilePathsArgs, AgentMetricsArgs, AgentOptionalFilePathsArgs, AgentPositionArgs,
    AgentRawCallHierarchyArgs, AgentRawCodeActionsArgs, AgentRawCompletionsArgs,
    AgentRawImplementationsArgs, AgentRawReferencesArgs, AgentRawRenameArgs, AgentRawResolveArgs,
    AgentRawSemanticInsertionPointArgs, AgentRawTypeHierarchyArgs, AgentRuntimeArgs,
    AgentScaffoldArgs, AgentSymbolCallersArgs, AgentSymbolReferencesArgs, AgentSymbolResolveArgs,
    AgentWorkflowCommand, AgentWorkflowCommonArgs, AgentWorkflowDiagnosticsArgs,
    AgentWorkflowPackageVerifyArgs, AgentWorkflowSymbolArgs, AgentWorkflowWriteMode,
    AgentWorkflowWriteValidateArgs, AgentWorkspaceFilesArgs, AgentWorkspaceSearchArgs,
    AgentWorkspaceSymbolArgs, RpcArgs,
};
use crate::error::{CliError, Result};
use crate::{catalog_schema, config, manifest, output, runtime, self_mgmt, validate};
use serde::Serialize;
use serde_json::{Map, Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{self, IsTerminal, Read};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const TOOL_CATEGORY_ORDER: &[&str] = &["symbol", "database", "system", "raw"];

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

pub fn run(args: AgentArgs) -> Result<i32> {
    let envelope = execute(args.command);
    let exit_code = if envelope.ok { 0 } else { 1 };
    output::print_json(&envelope)?;
    Ok(exit_code)
}

fn execute(command: AgentCommand) -> AgentEnvelope {
    if matches!(
        command,
        AgentCommand::Up(_)
            | AgentCommand::Ready(_)
            | AgentCommand::Setup(_)
            | AgentCommand::Lsp(_)
    ) {
        return error_envelope(
            "agent/operator".to_string(),
            None,
            agent_error(
                "AGENT_COMMAND_UNSUPPORTED",
                "`kast agent up`, `kast agent ready`, `kast agent setup`, and `kast agent lsp` are operator commands handled before JSON envelope dispatch.",
            ),
        );
    }
    if let AgentCommand::Workflow(args) = command {
        return execute_workflow(args.command);
    }
    if matches!(command, AgentCommand::Tools) {
        return execute_tools();
    }
    let request = match command {
        AgentCommand::Up(_)
        | AgentCommand::Ready(_)
        | AgentCommand::Setup(_)
        | AgentCommand::Lsp(_) => {
            unreachable!("operator agent commands are handled before request prep")
        }
        AgentCommand::Tools => unreachable!("agent tools is handled before request prep"),
        AgentCommand::Call(args) => prepare_call(args),
        AgentCommand::Workflow(_) => unreachable!("workflow is handled before request prep"),
        other => Ok(prepare_alias(other)),
    };
    let request = match request {
        Ok(request) => request,
        Err(error) => return error_envelope(error.method, error.request, error.error),
    };
    execute_request(request)
}

fn execute_tools() -> AgentEnvelope {
    match agent_tools_result() {
        Ok(result) => result_envelope("agent/tools".to_string(), result),
        Err(error) => error_envelope(
            "agent/tools".to_string(),
            None,
            AgentError::from_cli_error(error),
        ),
    }
}

fn agent_tools_result() -> Result<AgentToolsResult> {
    let catalog = validate::embedded_catalog()?;
    let tools = agent_tool_specs(&catalog)?;
    Ok(AgentToolsResult {
        result_type: "KAST_AGENT_TOOLS",
        catalog_sha256: manifest::sha256_bytes(validate::embedded_catalog_source().as_bytes()),
        tool_count: tools.len(),
        invocation: AgentToolInvocation {
            command: "kast agent call",
            argv: vec![
                current_executable_argument(),
                "agent".to_string(),
                "call".to_string(),
                "<method>".to_string(),
            ],
            method_argument: "<method>",
            params_file_flag: "--params-file",
            workspace_root_flag: "--workspace-root",
        },
        tools,
        schema_version: SCHEMA_VERSION,
    })
}

fn current_executable_argument() -> String {
    std::env::args_os()
        .next()
        .map(|arg| arg.to_string_lossy().into_owned())
        .filter(|arg| !arg.is_empty())
        .unwrap_or_else(|| "kast".to_string())
}

fn agent_tool_specs(catalog: &Value) -> Result<Vec<AgentToolSpec>> {
    let commands = catalog
        .get("commands")
        .and_then(Value::as_object)
        .ok_or_else(|| catalog_error("Command catalog must define a commands object."))?;
    let categories = catalog
        .get("categories")
        .and_then(Value::as_object)
        .ok_or_else(|| catalog_error("Command catalog must define a categories object."))?;
    let mut seen = BTreeSet::new();
    let mut tools = Vec::new();
    for category in TOOL_CATEGORY_ORDER {
        let Some(methods) = categories.get(*category).and_then(Value::as_array) else {
            continue;
        };
        for method in methods {
            let method = method.as_str().ok_or_else(|| {
                catalog_error(format!(
                    "Catalog category `{category}` contains a non-string method."
                ))
            })?;
            if seen.contains(method) {
                continue;
            }
            let Some(command) = commands.get(method) else {
                return Err(catalog_error(format!(
                    "Catalog category `{category}` references missing method `{method}`."
                )));
            };
            if command.get("tool").is_some() {
                tools.push(agent_tool_spec(catalog, method, command)?);
                seen.insert(method.to_string());
            }
        }
    }
    for (method, command) in commands {
        if command.get("tool").is_some() && !seen.contains(method) {
            tools.push(agent_tool_spec(catalog, method, command)?);
            seen.insert(method.clone());
        }
    }
    Ok(tools)
}

fn agent_tool_spec(catalog: &Value, method: &str, command: &Value) -> Result<AgentToolSpec> {
    let tool = command
        .get("tool")
        .and_then(Value::as_object)
        .ok_or_else(|| {
            catalog_error(format!(
                "Catalog command `{method}` must define tool metadata."
            ))
        })?;
    let name = tool.get("name").and_then(Value::as_str).ok_or_else(|| {
        catalog_error(format!(
            "Catalog command `{method}` tool.name must be a string."
        ))
    })?;
    let tool_description = tool
        .get("description")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            catalog_error(format!(
                "Catalog command `{method}` tool.description must be a string."
            ))
        })?;
    let category = command
        .get("category")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            catalog_error(format!(
                "Catalog command `{method}` category must be a string."
            ))
        })?;
    let request_schema = catalog_schema::request_schema(catalog, method)?;
    let parameters = request_schema
        .pointer("/properties/params")
        .cloned()
        .ok_or_else(|| {
            catalog_error(format!(
                "Generated schema for `{method}` is missing params."
            ))
        })?;
    Ok(AgentToolSpec {
        name: name.to_string(),
        method: method.to_string(),
        category: category.to_string(),
        description: agent_tool_description(method, command, tool_description),
        default_args: tool.get("defaultArgs").cloned(),
        parameters,
        mutates: agent_tool_mutates(method),
    })
}

fn agent_tool_description(method: &str, command: &Value, tool_description: &str) -> String {
    format!(
        "{} {}{}",
        agent_tool_policy_prefix(method),
        tool_description,
        agent_tool_variant_summary(command)
    )
}

fn agent_tool_policy_prefix(method: &str) -> &'static str {
    if method.starts_with("symbol/") {
        return "Preferred Kotlin funnel tool. Use this before raw file or offset operations when a symbol name, target type, or intended refactor is known.";
    }
    if method.starts_with("database/") {
        return "Preferred low-cost source-index tool. Use this before backend-wide traversal when index metrics can answer the question.";
    }
    if method.starts_with("raw/workspace-files") {
        return "Secondary workspace inspection tool. Use only after symbol/query, workspace symbols, or workspace search cannot identify a bounded target.";
    }
    if method.starts_with("raw/") {
        return "Bounded raw escape hatch. Use only with an exact file, offset, or explicit file list, or after the symbol-first path failed with a concrete blocker.";
    }
    "Kast system tool."
}

fn agent_tool_variant_summary(command: &Value) -> String {
    let Some(variants) = command.get("variants").and_then(Value::as_object) else {
        return String::new();
    };
    if variants.is_empty() {
        return String::new();
    }
    let variant_descriptions = variants
        .iter()
        .map(|(name, request)| {
            let required = request_required_fields(request)
                .into_iter()
                .filter(|field| field != "type")
                .collect::<Vec<_>>();
            format!(
                "{name} requires {}",
                if required.is_empty() {
                    "no extra fields".to_string()
                } else {
                    required.join(", ")
                }
            )
        })
        .collect::<Vec<_>>()
        .join("; ");
    format!(" Variant type values: {variant_descriptions}.")
}

fn request_required_fields(request: &Value) -> Vec<String> {
    if let Some(required) = request.get("required").and_then(Value::as_array) {
        return required
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_string)
            .collect();
    }
    request
        .get("fields")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
        .filter(|(_, field)| field.get("optional").and_then(Value::as_bool) != Some(true))
        .map(|(name, _)| name.clone())
        .collect()
}

fn agent_tool_mutates(method: &str) -> bool {
    matches!(method, "symbol/rename" | "symbol/write-and-validate")
}

fn catalog_error(message: impl Into<String>) -> CliError {
    CliError::new("RPC_CATALOG_INVALID", message)
}

fn execute_request(request: AgentRequest) -> AgentEnvelope {
    let validation = validate_request(&request.method, &request.request);
    if let Err(error) = validation {
        return error_envelope(request.method, Some(request.request), error);
    }
    let raw_request = match serde_json::to_string(&request.request) {
        Ok(raw_request) => raw_request,
        Err(error) => {
            return error_envelope(
                request.method,
                Some(request.request),
                AgentError::from_cli_error(CliError::from(error)),
            );
        }
    };
    let response = runtime::rpc_passthrough(RpcArgs {
        request: Some(raw_request),
        request_file: None,
        workspace_root: request.runtime.workspace_root,
        backend_name: request.runtime.backend_name,
    });
    match response {
        Ok(raw_response) => response_envelope(request.method, request.request, raw_response),
        Err(error) => error_envelope(
            request.method,
            Some(request.request),
            AgentError::from_cli_error(error),
        ),
    }
}

fn execute_workflow(command: AgentWorkflowCommand) -> AgentEnvelope {
    let method = format!("agent/workflow/{}", workflow_name(&command));
    let prepared = workflow_steps(command);
    let (workflow, common, steps) = match prepared {
        Ok(prepared) => prepared,
        Err(error) => return error_envelope(method, None, error),
    };
    match run_workflow(&workflow, &common, steps) {
        Ok(summary) => workflow_envelope(method, summary),
        Err(error) => error_envelope(method, None, AgentError::from_cli_error(error)),
    }
}

fn workflow_name(command: &AgentWorkflowCommand) -> &'static str {
    match command {
        AgentWorkflowCommand::Verify(_) => "verify",
        AgentWorkflowCommand::Symbol(_) => "symbol",
        AgentWorkflowCommand::Impact(_) => "impact",
        AgentWorkflowCommand::Diagnostics(_) => "diagnostics",
        AgentWorkflowCommand::RenamePlan(_) => "rename-plan",
        AgentWorkflowCommand::WriteValidate(_) => "write-validate",
        AgentWorkflowCommand::PackageVerify(_) => "package-verify",
    }
}

fn workflow_steps(
    command: AgentWorkflowCommand,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    match command {
        AgentWorkflowCommand::Verify(args) => Ok((
            "verify".to_string(),
            args.common,
            vec![
                AgentWorkflowStep::catalog("health", "health", json!({}), false),
                AgentWorkflowStep::catalog("runtime-status", "runtime/status", json!({}), false),
                AgentWorkflowStep::catalog("capabilities", "capabilities", json!({}), false),
            ],
        )),
        AgentWorkflowCommand::Symbol(args) => symbol_workflow_steps(args),
        AgentWorkflowCommand::Impact(args) => Ok((
            "impact".to_string(),
            args.common,
            vec![AgentWorkflowStep::catalog(
                "impact",
                "database/metrics",
                json!({
                    "metric": "impact",
                    "symbol": args.symbol,
                    "depth": args.depth,
                    "limit": args.limit,
                }),
                false,
            )],
        )),
        AgentWorkflowCommand::Diagnostics(args) => diagnostics_workflow_steps(args),
        AgentWorkflowCommand::RenamePlan(args) => Ok((
            "rename-plan".to_string(),
            args.common,
            vec![AgentWorkflowStep::catalog(
                "rename-plan",
                "raw/rename",
                json!({
                    "position": {
                        "filePath": args.file_path,
                        "offset": args.offset,
                    },
                    "newName": args.new_name,
                    "dryRun": true,
                }),
                false,
            )],
        )),
        AgentWorkflowCommand::WriteValidate(args) => write_validate_workflow_steps(args),
        AgentWorkflowCommand::PackageVerify(args) => {
            let options = AgentPackageVerifyOptions::from_args(&args);
            Ok((
                "package-verify".to_string(),
                args.common,
                vec![AgentWorkflowStep::package_verify(options)],
            ))
        }
    }
}

fn symbol_workflow_steps(
    args: AgentWorkflowSymbolArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    let mut steps = vec![
        AgentWorkflowStep::catalog(
            "symbol-query",
            "symbol/query",
            json!({
                "query": args.symbol,
                "modes": ["exact", "lexical"],
                "filters": {},
                "limit": args.query_limit,
                "includeEvidence": true,
                "includeNextRequests": true,
            }),
            false,
        ),
        AgentWorkflowStep::catalog(
            "symbol-resolve",
            "symbol/resolve",
            drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "includeDeclarationScope": true,
                "includeDocumentation": true,
                "surroundingLines": 3,
                "includeSurroundingMembers": true,
            })),
            false,
        ),
    ];
    if args.references {
        steps.push(AgentWorkflowStep::catalog(
            "symbol-references",
            "symbol/references",
            drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "includeDeclaration": args.include_declaration,
            })),
            false,
        ));
    }
    if let Some(direction) = args.callers {
        steps.push(AgentWorkflowStep::catalog(
            "symbol-callers",
            "symbol/callers",
            drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "direction": direction.canonical(),
                "depth": args.caller_depth,
            })),
            false,
        ));
    }
    Ok(("symbol".to_string(), args.common, steps))
}

fn diagnostics_workflow_steps(
    args: AgentWorkflowDiagnosticsArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    let mut steps = Vec::new();
    if !args.skip_refresh {
        steps.push(AgentWorkflowStep::catalog(
            "workspace-refresh",
            "raw/workspace-refresh",
            json!({ "filePaths": args.file_paths }),
            false,
        ));
    }
    steps.push(AgentWorkflowStep::catalog(
        "diagnostics",
        "raw/diagnostics",
        json!({ "filePaths": args.file_paths }),
        false,
    ));
    Ok(("diagnostics".to_string(), args.common, steps))
}

fn write_validate_workflow_steps(
    args: AgentWorkflowWriteValidateArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    if !args.common.dry_run && !args.allow_mutation {
        return Err(agent_error(
            "AGENT_WORKFLOW_MUTATION_REQUIRES_OPT_IN",
            "`write-validate` requires --allow-mutation unless --dry-run is set.",
        ));
    }
    if args.content.is_some() && args.content_file.is_some() {
        return Err(agent_error(
            "AGENT_WORKFLOW_INPUT_CONFLICT",
            "Use only one of --content or --content-file.",
        ));
    }
    let params = match args.mode {
        AgentWorkflowWriteMode::Create => drop_nulls(json!({
            "type": "CREATE_FILE_REQUEST",
            "filePath": args.file_path,
            "content": args.content,
            "contentFile": args.content_file,
        })),
        AgentWorkflowWriteMode::Insert => {
            let offset = args.offset.ok_or_else(|| {
                agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "write-validate --mode insert requires --offset.",
                )
            })?;
            drop_nulls(json!({
                "type": "INSERT_AT_OFFSET_REQUEST",
                "filePath": args.file_path,
                "offset": offset,
                "content": args.content,
                "contentFile": args.content_file,
            }))
        }
        AgentWorkflowWriteMode::Replace => {
            let start_offset = args.start_offset.ok_or_else(|| {
                agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "write-validate --mode replace requires --start-offset.",
                )
            })?;
            let end_offset = args.end_offset.ok_or_else(|| {
                agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "write-validate --mode replace requires --end-offset.",
                )
            })?;
            if end_offset < start_offset {
                return Err(agent_error(
                    "AGENT_WORKFLOW_INPUT_INVALID",
                    "--end-offset must be greater than or equal to --start-offset.",
                ));
            }
            drop_nulls(json!({
                "type": "REPLACE_RANGE_REQUEST",
                "filePath": args.file_path,
                "startOffset": start_offset,
                "endOffset": end_offset,
                "content": args.content,
                "contentFile": args.content_file,
            }))
        }
    };
    Ok((
        "write-validate".to_string(),
        args.common,
        vec![AgentWorkflowStep::catalog(
            "write-and-validate",
            "symbol/write-and-validate",
            params,
            true,
        )],
    ))
}

fn run_workflow(
    workflow: &str,
    common: &AgentWorkflowCommonArgs,
    steps: Vec<AgentWorkflowStep>,
) -> Result<AgentWorkflowSummary> {
    let out_dir = workflow_out_dir(workflow, common.out_dir.as_deref())?;
    fs::create_dir_all(&out_dir)?;
    let workspace_root = common
        .runtime
        .workspace_root
        .clone()
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));
    let mut step_summaries = Vec::new();
    let mut issues = Vec::new();
    for step in steps {
        let summary = run_workflow_step(&out_dir, common, &workspace_root, &step)?;
        let step_ok = summary.exit_code == 0
            && summary
                .summary
                .get("ok")
                .and_then(Value::as_bool)
                .unwrap_or(false);
        if !step_ok {
            issues.push(AgentWorkflowIssue {
                code: "AGENT_WORKFLOW_STEP_FAILED".to_string(),
                message: format!("{} failed", step.name),
                step: Some(step.name.to_string()),
            });
        }
        step_summaries.push(summary);
        if !issues.is_empty() && !common.dry_run {
            break;
        }
    }
    let summary = AgentWorkflowSummary {
        summary_type: "KAST_AGENT_WORKFLOW",
        ok: issues.is_empty(),
        workflow: workflow.to_string(),
        workspace_root: workspace_root.display().to_string(),
        out_dir: out_dir.display().to_string(),
        dry_run: common.dry_run,
        steps: step_summaries,
        issues,
        schema_version: SCHEMA_VERSION,
    };
    write_json_file(&out_dir.join("workflow.json"), &summary)?;
    Ok(summary)
}

fn run_workflow_step(
    out_dir: &Path,
    common: &AgentWorkflowCommonArgs,
    workspace_root: &Path,
    step: &AgentWorkflowStep,
) -> Result<AgentWorkflowStepSummary> {
    let step_dir = out_dir.join(step.name);
    fs::create_dir_all(&step_dir)?;
    let params_file = step_dir.join("input.json");
    let stdout_file = step_dir.join("stdout.json");
    let stderr_file = step_dir.join("stderr.txt");
    write_json_file(&params_file, &step.params)?;
    let (exit_code, summary) = if common.dry_run {
        let summary = match &step.action {
            AgentWorkflowStepAction::Catalog => json!({
                "ok": true,
                "dryRun": true,
                "method": step.method,
                "mutates": step.mutates,
                "nextRequest": json_rpc_request(step.method, step.params.clone()),
                "schemaVersion": SCHEMA_VERSION,
            }),
            AgentWorkflowStepAction::PackageVerify(options) => json!({
                "ok": true,
                "dryRun": true,
                "method": step.method,
                "mutates": step.mutates,
                "params": step.params,
                "nextCommandArgv": package_verify_command_argv(workspace_root, common, options),
                "schemaVersion": SCHEMA_VERSION,
            }),
        };
        (0, summary)
    } else {
        match &step.action {
            AgentWorkflowStepAction::PackageVerify(options) => {
                run_package_verify_step(workspace_root, options)?
            }
            AgentWorkflowStepAction::Catalog => {
                let envelope = execute_request(AgentRequest {
                    method: step.method.to_string(),
                    request: json_rpc_request(step.method, step.params.clone()),
                    runtime: common.runtime.clone(),
                });
                let exit_code = if envelope.ok { 0 } else { 1 };
                (exit_code, serde_json::to_value(envelope)?)
            }
        }
    };
    write_json_file(&stdout_file, &summary)?;
    fs::write(&stderr_file, "")?;
    Ok(AgentWorkflowStepSummary {
        name: step.name.to_string(),
        method: step.method.to_string(),
        params_file: params_file.display().to_string(),
        stdout: stdout_file.display().to_string(),
        stderr: stderr_file.display().to_string(),
        exit_code,
        summary,
    })
}

fn run_package_verify_step(
    workspace_root: &Path,
    options: &AgentPackageVerifyOptions,
) -> Result<(i32, Value)> {
    let doctor = self_mgmt::doctor(false, ReadyTarget::Agent)?;
    let required_resources = required_package_resources(&doctor, workspace_root, options)?;
    let mut summary = serde_json::to_value(&doctor)?;
    let ok = doctor.ok && required_resources.ok;
    if let Some(summary) = summary.as_object_mut() {
        append_required_resource_issues(summary, &required_resources.issues);
        summary.insert("ok".to_string(), Value::Bool(ok));
        summary.insert(
            "requiredResources".to_string(),
            serde_json::to_value(required_resources)?,
        );
    }
    let exit_code = if ok { 0 } else { 1 };
    Ok((exit_code, summary))
}

fn package_verify_command_argv(
    workspace_root: &Path,
    common: &AgentWorkflowCommonArgs,
    options: &AgentPackageVerifyOptions,
) -> Vec<String> {
    let mut argv = vec![
        current_executable_argument(),
        "--output".to_string(),
        "json".to_string(),
        "agent".to_string(),
        "workflow".to_string(),
        "package-verify".to_string(),
        "--workspace-root".to_string(),
        workspace_root.display().to_string(),
    ];
    if let Some(backend) = common.runtime.backend_name {
        argv.push("--backend".to_string());
        argv.push(backend.canonical().to_string());
    }
    if options.require_copilot {
        argv.push("--require-copilot".to_string());
    }
    if let Some(target_dir) = &options.copilot_target_dir {
        argv.push("--copilot-target-dir".to_string());
        argv.push(config::normalize(target_dir.clone()).display().to_string());
    }
    if options.require_skill {
        argv.push("--require-skill".to_string());
    }
    for target_dir in &options.skill_target_dirs {
        argv.push("--skill-target-dir".to_string());
        argv.push(config::normalize(target_dir.clone()).display().to_string());
    }
    if options.require_instructions {
        argv.push("--require-instructions".to_string());
    }
    for target_dir in &options.instructions_target_dirs {
        argv.push("--instructions-target-dir".to_string());
        argv.push(config::normalize(target_dir.clone()).display().to_string());
    }
    argv
}

fn required_package_resources(
    doctor: &self_mgmt::SelfDoctorResult,
    workspace_root: &Path,
    options: &AgentPackageVerifyOptions,
) -> Result<AgentPackageRequiredResources> {
    let copilot_package = package_resource_group(
        doctor.install.as_ref(),
        workspace_root,
        self_mgmt::ManagedResourceKind::CopilotPackage,
        options.require_copilot,
        options.copilot_target_dir.clone().into_iter().collect(),
    )?;
    let skills = package_resource_group(
        doctor.install.as_ref(),
        workspace_root,
        self_mgmt::ManagedResourceKind::Skill,
        options.require_skill,
        options.skill_target_dirs.clone(),
    )?;
    let instructions = package_resource_group(
        doctor.install.as_ref(),
        workspace_root,
        self_mgmt::ManagedResourceKind::Instructions,
        options.require_instructions,
        options.instructions_target_dirs.clone(),
    )?;
    let mut issues = Vec::new();
    issues.extend(package_resource_group_issues(&copilot_package));
    issues.extend(package_resource_group_issues(&skills));
    issues.extend(package_resource_group_issues(&instructions));
    Ok(AgentPackageRequiredResources {
        ok: issues.is_empty(),
        workspace_root: workspace_root.display().to_string(),
        copilot_package,
        skills,
        instructions,
        issues,
    })
}

fn package_resource_group(
    install: Option<&self_mgmt::InstallState>,
    workspace_root: &Path,
    kind: self_mgmt::ManagedResourceKind,
    required: bool,
    explicit_target_dirs: Vec<PathBuf>,
) -> Result<AgentPackageResourceGroup> {
    let has_explicit_targets = !explicit_target_dirs.is_empty();
    let targets = if has_explicit_targets {
        explicit_target_dirs
            .into_iter()
            .map(|target_dir| resource_target_from_target_dir(kind, target_dir))
            .collect::<Vec<_>>()
    } else if required {
        standard_resource_targets(workspace_root, kind)
    } else {
        Vec::new()
    };
    let mut checks = Vec::new();
    for target in targets {
        checks.push(package_resource_target(
            install,
            kind,
            config::normalize(target),
        )?);
    }
    Ok(AgentPackageResourceGroup {
        required,
        mode: if has_explicit_targets {
            "explicit"
        } else {
            "standard"
        },
        targets: checks,
    })
}

fn package_resource_target(
    install: Option<&self_mgmt::InstallState>,
    kind: self_mgmt::ManagedResourceKind,
    target: PathBuf,
) -> Result<AgentPackageResourceTarget> {
    let resource = managed_resource_for_target(install, kind, &target).cloned();
    let output_issues = match &resource {
        Some(resource) => manifest::verify_managed_resource_outputs(resource)?.issues,
        None => Vec::new(),
    };
    let version_matches_current = resource
        .as_ref()
        .is_some_and(|resource| resource.primitive_version == crate::cli::version());
    let current = resource.is_some() && version_matches_current && output_issues.is_empty();
    Ok(AgentPackageResourceTarget {
        kind,
        target_path: target.display().to_string(),
        exists: target.exists(),
        current,
        version_matches_current,
        manifest_resource: resource,
        output_issues,
    })
}

fn package_resource_group_issues(
    group: &AgentPackageResourceGroup,
) -> Vec<AgentPackageResourceIssue> {
    if !group.required {
        return Vec::new();
    }
    if group.mode == "explicit" {
        return group
            .targets
            .iter()
            .filter(|target| !target.current)
            .map(|target| required_resource_issue(target.kind, vec![target.target_path.clone()]))
            .collect();
    }
    if group.targets.iter().any(|target| target.current) {
        return Vec::new();
    }
    let Some(first) = group.targets.first() else {
        return Vec::new();
    };
    vec![required_resource_issue(
        first.kind,
        group
            .targets
            .iter()
            .map(|target| target.target_path.clone())
            .collect(),
    )]
}

fn required_resource_issue(
    kind: self_mgmt::ManagedResourceKind,
    target_paths: Vec<String>,
) -> AgentPackageResourceIssue {
    let label = required_resource_label(kind);
    AgentPackageResourceIssue {
        code: format!("AGENT_WORKFLOW_REQUIRED_{}_MISSING_OR_STALE", label),
        message: format!(
            "Required Kast {} resource is missing, stale, or not manifest-backed.",
            label.to_ascii_lowercase().replace('_', " ")
        ),
        kind,
        recovery_argv: required_resource_recovery_argv(kind, &target_paths),
        target_paths,
    }
}

fn required_resource_recovery_argv(
    kind: self_mgmt::ManagedResourceKind,
    target_paths: &[String],
) -> Vec<String> {
    if kind == self_mgmt::ManagedResourceKind::AgentGuidance {
        let mut argv = vec![
            current_executable_argument(),
            "agent".to_string(),
            "setup".to_string(),
        ];
        if let Some(target) = target_paths.first() {
            argv.push("--agents-md".to_string());
            argv.push(target.clone());
        }
        argv.push("--force".to_string());
        return argv;
    }
    let mut argv = vec![
        current_executable_argument(),
        "agent".to_string(),
        "setup".to_string(),
        required_resource_harness(kind).to_string(),
    ];
    if let Some(target_dir) = required_resource_recovery_target_dir(kind, target_paths) {
        argv.push("--target-dir".to_string());
        argv.push(target_dir);
    }
    argv.push("--force".to_string());
    argv
}

fn required_resource_recovery_target_dir(
    kind: self_mgmt::ManagedResourceKind,
    target_paths: &[String],
) -> Option<String> {
    let target = target_paths.first()?;
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => Some(target.clone()),
        self_mgmt::ManagedResourceKind::Skill | self_mgmt::ManagedResourceKind::Instructions => {
            Path::new(target)
                .parent()
                .map(|parent| parent.display().to_string())
        }
        self_mgmt::ManagedResourceKind::AgentGuidance => Some(target.clone()),
    }
}

fn required_resource_harness(kind: self_mgmt::ManagedResourceKind) -> &'static str {
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => "copilot",
        self_mgmt::ManagedResourceKind::Skill => "skill",
        self_mgmt::ManagedResourceKind::Instructions => "instructions",
        self_mgmt::ManagedResourceKind::AgentGuidance => "agent-guidance",
    }
}

fn required_resource_label(kind: self_mgmt::ManagedResourceKind) -> &'static str {
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => "COPILOT_PACKAGE",
        self_mgmt::ManagedResourceKind::Skill => "SKILL",
        self_mgmt::ManagedResourceKind::Instructions => "INSTRUCTIONS",
        self_mgmt::ManagedResourceKind::AgentGuidance => "AGENT_GUIDANCE",
    }
}

fn append_required_resource_issues(
    summary: &mut Map<String, Value>,
    issues: &[AgentPackageResourceIssue],
) {
    if issues.is_empty() {
        return;
    }
    let summary_issues = summary
        .entry("issues".to_string())
        .or_insert_with(|| Value::Array(Vec::new()));
    let Some(summary_issues) = summary_issues.as_array_mut() else {
        return;
    };
    for issue in issues {
        summary_issues.push(Value::String(format!("{}: {}", issue.code, issue.message)));
    }
}

fn managed_resource_for_target<'a>(
    install: Option<&'a self_mgmt::InstallState>,
    kind: self_mgmt::ManagedResourceKind,
    target: &Path,
) -> Option<&'a self_mgmt::ManagedRepoResource> {
    let normalized_target = config::normalize(target.to_path_buf());
    install.and_then(|install| {
        install.repos.iter().find_map(|repo| {
            repo.resources.iter().find(|resource| {
                resource.kind == kind
                    && config::normalize(PathBuf::from(&resource.target_path)) == normalized_target
            })
        })
    })
}

fn standard_resource_targets(
    workspace_root: &Path,
    kind: self_mgmt::ManagedResourceKind,
) -> Vec<PathBuf> {
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => vec![workspace_root.join(".github")],
        self_mgmt::ManagedResourceKind::Skill => standard_named_resource_targets(
            workspace_root,
            &[
                ".agents/skills",
                ".codex/skills",
                ".github/skills",
                ".claude/skills",
            ],
        ),
        self_mgmt::ManagedResourceKind::Instructions => standard_named_resource_targets(
            workspace_root,
            &[
                ".agents/instructions",
                ".codex/instructions",
                ".github/instructions",
                ".claude/instructions",
            ],
        ),
        self_mgmt::ManagedResourceKind::AgentGuidance => vec![workspace_root.join("AGENTS.md")],
    }
}

fn standard_named_resource_targets(workspace_root: &Path, roots: &[&str]) -> Vec<PathBuf> {
    roots
        .iter()
        .map(|root| workspace_root.join(root).join("kast"))
        .collect()
}

fn resource_target_from_target_dir(
    kind: self_mgmt::ManagedResourceKind,
    target_dir: PathBuf,
) -> PathBuf {
    let target_dir = config::normalize(target_dir);
    if kind == self_mgmt::ManagedResourceKind::CopilotPackage {
        return target_dir;
    }
    if target_dir
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == "kast")
    {
        target_dir
    } else {
        target_dir.join("kast")
    }
}

fn path_values(paths: &[PathBuf]) -> Vec<String> {
    paths
        .iter()
        .map(|path| config::normalize(path.clone()).display().to_string())
        .collect()
}

fn workflow_envelope(method: String, summary: AgentWorkflowSummary) -> AgentEnvelope {
    let result = serde_json::to_value(&summary).unwrap_or(Value::Null);
    let ok = summary.ok;
    let error = (!ok).then(|| {
        let mut error = agent_error("AGENT_WORKFLOW_FAILED", "Agent workflow failed.");
        error.details.insert(
            "issues".to_string(),
            result.get("issues").cloned().unwrap_or(Value::Null),
        );
        error
    });
    AgentEnvelope {
        ok,
        method,
        request: None,
        response: None,
        result: Some(result),
        raw_response: None,
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn result_envelope(method: String, result: impl Serialize) -> AgentEnvelope {
    AgentEnvelope {
        ok: true,
        method,
        request: None,
        response: None,
        result: Some(serde_json::to_value(result).unwrap_or(Value::Null)),
        raw_response: None,
        error: None,
        schema_version: SCHEMA_VERSION,
    }
}

fn workflow_out_dir(workflow: &str, requested: Option<&Path>) -> Result<PathBuf> {
    if let Some(path) = requested {
        return Ok(path.to_path_buf());
    }
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    Ok(std::env::temp_dir().join(format!(
        "kast-agent-workflow-{workflow}-{}-{seconds}",
        std::process::id()
    )))
}

fn write_json_file(path: &Path, value: &impl Serialize) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut bytes = serde_json::to_vec_pretty(value)?;
    bytes.push(b'\n');
    fs::write(path, bytes)?;
    Ok(())
}

fn drop_nulls(value: Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.into_iter()
                .filter_map(|(key, value)| (!value.is_null()).then_some((key, value)))
                .collect(),
        ),
        value => value,
    }
}

fn prepare_call(args: AgentCallArgs) -> std::result::Result<AgentRequest, Box<AgentFailure>> {
    let method = args.method.clone();
    let input = match load_call_input(&args) {
        Ok(input) => input,
        Err(error) => return Err(agent_failure(method, None, error)),
    };
    let request = match normalize_input(&method, input) {
        Ok(request) => request,
        Err(error) => return Err(agent_failure(method, None, error)),
    };
    Ok(AgentRequest {
        method,
        request,
        runtime: args.runtime,
    })
}

fn prepare_alias(command: AgentCommand) -> AgentRequest {
    let parts = alias_parts(command);
    AgentRequest {
        request: json_rpc_request(&parts.method, parts.params),
        method: parts.method,
        runtime: parts.runtime,
    }
}

struct AgentFailure {
    method: String,
    request: Option<Value>,
    error: AgentError,
}

fn agent_failure(method: String, request: Option<Value>, error: AgentError) -> Box<AgentFailure> {
    Box::new(AgentFailure {
        method,
        request,
        error,
    })
}

struct AliasParts {
    method: String,
    params: Value,
    runtime: AgentRuntimeArgs,
}

fn alias_parts(command: AgentCommand) -> AliasParts {
    match command {
        AgentCommand::Up(_)
        | AgentCommand::Ready(_)
        | AgentCommand::Setup(_)
        | AgentCommand::Lsp(_) => {
            unreachable!("operator agent commands are handled before alias prep")
        }
        AgentCommand::Tools => unreachable!("agent tools is handled before alias prep"),
        AgentCommand::Call(_) => unreachable!("agent call is prepared separately"),
        AgentCommand::Workflow(_) => unreachable!("agent workflow is prepared separately"),
        AgentCommand::Health(runtime) => empty_alias("health", runtime),
        AgentCommand::RuntimeStatus(runtime) => empty_alias("runtime/status", runtime),
        AgentCommand::Capabilities(runtime) => empty_alias("capabilities", runtime),
        AgentCommand::Scaffold(args) => scaffold_alias(args),
        AgentCommand::Discover(args) => discover_alias(args),
        AgentCommand::Resolve(args) => symbol_resolve_alias(args),
        AgentCommand::References(args) => symbol_references_alias(args),
        AgentCommand::Callers(args) => symbol_callers_alias(args),
        AgentCommand::RawResolve(args) => raw_resolve_alias(args),
        AgentCommand::RawReferences(args) => raw_references_alias(args),
        AgentCommand::RawCallHierarchy(args) => raw_call_hierarchy_alias(args),
        AgentCommand::RawTypeHierarchy(args) => raw_type_hierarchy_alias(args),
        AgentCommand::RawSemanticInsertionPoint(args) => raw_semantic_insertion_point_alias(args),
        AgentCommand::RawDiagnostics(args) => file_paths_alias("raw/diagnostics", args),
        AgentCommand::RawRename(args) => raw_rename_alias(args),
        AgentCommand::RawOptimizeImports(args) => file_paths_alias("raw/optimize-imports", args),
        AgentCommand::RawWorkspaceRefresh(args) => {
            optional_file_paths_alias("raw/workspace-refresh", args)
        }
        AgentCommand::FileOutline(args) => file_outline_alias(args),
        AgentCommand::WorkspaceSymbol(args) => workspace_symbol_alias(args),
        AgentCommand::WorkspaceSearch(args) => workspace_search_alias(args),
        AgentCommand::WorkspaceFiles(args) => workspace_files_alias(args),
        AgentCommand::RawImplementations(args) => raw_implementations_alias(args),
        AgentCommand::RawCodeActions(args) => raw_code_actions_alias(args),
        AgentCommand::RawCompletions(args) => raw_completions_alias(args),
        AgentCommand::Metrics(args) => metrics_alias(args),
    }
}

fn load_call_input(args: &AgentCallArgs) -> std::result::Result<Option<Value>, AgentError> {
    let explicit_sources = [
        args.params.is_some(),
        args.params_file.is_some(),
        args.request_file.is_some(),
    ]
    .into_iter()
    .filter(|present| *present)
    .count();
    if explicit_sources > 1 {
        return Err(agent_error(
            "AGENT_INPUT_CONFLICT",
            "Use only one of --params, --params-file, or --request-file.",
        ));
    }
    if let Some(raw) = &args.params {
        return parse_input(raw);
    }
    if let Some(path) = &args.params_file {
        return parse_input_file(path);
    }
    if let Some(path) = &args.request_file {
        return parse_input_file(path);
    }
    let mut stdin = io::stdin();
    if stdin.is_terminal() {
        return Ok(None);
    }
    let mut raw = String::new();
    if let Err(error) = stdin.read_to_string(&mut raw) {
        return Err(AgentError::from_cli_error(CliError::from(error)));
    }
    if raw.trim().is_empty() {
        return Ok(None);
    }
    parse_input(&raw)
}

fn parse_input(raw: &str) -> std::result::Result<Option<Value>, AgentError> {
    serde_json::from_str(raw)
        .map(Some)
        .map_err(|error| AgentError::from_cli_error(CliError::from(error)))
}

fn parse_input_file(path: &std::path::Path) -> std::result::Result<Option<Value>, AgentError> {
    let raw = fs::read_to_string(path).map_err(|error| AgentError::from_cli_error(error.into()))?;
    parse_input(&raw)
}

fn normalize_input(method: &str, input: Option<Value>) -> std::result::Result<Value, AgentError> {
    let Some(input) = input else {
        return Ok(json_rpc_request(method, json!({})));
    };
    normalize_value(method, input)
}

fn normalize_value(method: &str, value: Value) -> std::result::Result<Value, AgentError> {
    if is_full_json_rpc_request(&value) {
        ensure_method_matches(method, &value)?;
        return Ok(value);
    }
    if let Some(request) = value.get("request").filter(|request| request.is_object()) {
        return normalize_value(method, request.clone());
    }
    if let Some(request) = value
        .get("result")
        .and_then(|result| result.get("nextRequest"))
        .filter(|request| request.is_object())
    {
        return normalize_value(method, request.clone());
    }
    if let Some(request) = value
        .get("nextRequest")
        .filter(|request| request.is_object())
    {
        return normalize_value(method, request.clone());
    }
    if value.get("method").and_then(Value::as_str).is_some() {
        ensure_method_matches(method, &value)?;
        let params = value.get("params").cloned().unwrap_or_else(|| json!({}));
        return Ok(json_rpc_request(method, params));
    }
    Ok(json_rpc_request(method, value))
}

fn is_full_json_rpc_request(value: &Value) -> bool {
    value.get("jsonrpc").and_then(Value::as_str) == Some("2.0")
        && value.get("method").and_then(Value::as_str).is_some()
}

fn ensure_method_matches(method: &str, request: &Value) -> std::result::Result<(), AgentError> {
    let request_method = request
        .get("method")
        .and_then(Value::as_str)
        .ok_or_else(|| agent_error("AGENT_INPUT_INVALID", "Input method must be a string."))?;
    if request_method != method {
        let mut error = agent_error(
            "AGENT_METHOD_MISMATCH",
            format!("Input method `{request_method}` does not match CLI method `{method}`."),
        );
        error
            .details
            .insert("inputMethod".to_string(), json!(request_method));
        error.details.insert("cliMethod".to_string(), json!(method));
        return Err(error);
    }
    Ok(())
}

fn validate_request(method: &str, request: &Value) -> std::result::Result<(), AgentError> {
    let catalog = validate::embedded_catalog().map_err(AgentError::from_cli_error)?;
    let report =
        validate::validate_request(request, &catalog, None).map_err(AgentError::from_cli_error)?;
    if report.ok {
        return Ok(());
    }
    let mut error = agent_error(
        "AGENT_REQUEST_INVALID",
        format!("Request for `{method}` does not match the catalog schema."),
    );
    error.details.insert(
        "validation".to_string(),
        serde_json::to_value(report).unwrap_or(Value::Null),
    );
    Err(error)
}

fn response_envelope(method: String, request: Value, raw_response: String) -> AgentEnvelope {
    let response = match serde_json::from_str::<Value>(&raw_response) {
        Ok(response) => response,
        Err(error) => {
            let mut agent_error = AgentError::from_cli_error(CliError::from(error));
            agent_error
                .details
                .insert("rawResponse".to_string(), json!(raw_response));
            return AgentEnvelope {
                ok: false,
                method,
                request: Some(request),
                response: None,
                result: None,
                raw_response: None,
                error: Some(agent_error),
                schema_version: SCHEMA_VERSION,
            };
        }
    };
    let result = response.get("result").cloned();
    let error = response_error(&response).or_else(|| result_failure(&result));
    AgentEnvelope {
        ok: error.is_none() && result.is_some(),
        method,
        request: Some(request),
        response: Some(response),
        result,
        raw_response: None,
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn response_error(response: &Value) -> Option<AgentError> {
    let error = response.get("error")?;
    let code_value = error
        .get("data")
        .and_then(|data| data.get("code"))
        .or_else(|| error.get("code"));
    let message_value = error
        .get("data")
        .and_then(|data| data.get("message"))
        .or_else(|| error.get("message"));
    let code = code_value
        .and_then(Value::as_str)
        .map(str::to_string)
        .unwrap_or_else(|| {
            code_value
                .map(Value::to_string)
                .unwrap_or_else(|| "RPC_ERROR".to_string())
        });
    let message = message_value
        .and_then(Value::as_str)
        .map(str::to_string)
        .unwrap_or_else(|| "JSON-RPC request failed.".to_string());
    let mut agent_error = AgentError {
        code,
        message,
        details: BTreeMap::new(),
    };
    agent_error
        .details
        .insert("rpcError".to_string(), error.clone());
    Some(agent_error)
}

fn result_failure(result: &Option<Value>) -> Option<AgentError> {
    let result = result.as_ref()?;
    if result.get("ok").and_then(Value::as_bool) != Some(false) {
        return None;
    }
    let code = result
        .get("code")
        .or_else(|| result.get("type"))
        .and_then(Value::as_str)
        .unwrap_or("KAST_RESULT_NOT_OK")
        .to_string();
    let message = result
        .get("message")
        .and_then(Value::as_str)
        .unwrap_or("Kast result reported ok=false.")
        .to_string();
    let mut agent_error = AgentError {
        code,
        message,
        details: BTreeMap::new(),
    };
    agent_error
        .details
        .insert("result".to_string(), result.clone());
    Some(agent_error)
}

fn error_envelope(method: String, request: Option<Value>, error: AgentError) -> AgentEnvelope {
    AgentEnvelope {
        ok: false,
        method,
        request,
        response: None,
        result: None,
        raw_response: None,
        error: Some(error),
        schema_version: SCHEMA_VERSION,
    }
}

fn agent_error(code: &str, message: impl Into<String>) -> AgentError {
    AgentError {
        code: code.to_string(),
        message: message.into(),
        details: BTreeMap::new(),
    }
}

impl AgentError {
    fn from_cli_error(error: CliError) -> Self {
        Self {
            code: error.code.to_string(),
            message: error.message,
            details: error
                .details
                .into_iter()
                .map(|(key, value)| (key, json!(value)))
                .collect(),
        }
    }
}

fn json_rpc_request(method: &str, params: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
        "id": 1
    })
}

fn empty_alias(method: &str, runtime: AgentRuntimeArgs) -> AliasParts {
    AliasParts {
        method: method.to_string(),
        params: json!({}),
        runtime,
    }
}

fn scaffold_alias(args: AgentScaffoldArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "targetFile", args.target_file);
    put_option_string(&mut params, "targetSymbol", args.target_symbol);
    put_option_enum(
        &mut params,
        "mode",
        args.mode.map(|value| value.canonical()),
    );
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    alias("symbol/scaffold", params, args.runtime)
}

fn discover_alias(args: AgentDiscoverArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_u32(&mut params, "line", args.line);
    put_option_string(&mut params, "codeSnippet", args.code_snippet);
    put_option_string(&mut params, "containingType", args.containing_type);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    put_option_u32(&mut params, "maxResults", args.max_results);
    alias("symbol/discover", params, args.runtime)
}

fn symbol_resolve_alias(args: AgentSymbolResolveArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "containingType", args.containing_type);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    put_bool(
        &mut params,
        "includeDocumentation",
        args.include_documentation,
    );
    put_option_u32(&mut params, "surroundingLines", args.surrounding_lines);
    put_bool(
        &mut params,
        "includeSurroundingMembers",
        args.include_surrounding_members,
    );
    alias("symbol/resolve", params, args.runtime)
}

fn symbol_references_alias(args: AgentSymbolReferencesArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "containingType", args.containing_type);
    put_bool(&mut params, "includeDeclaration", args.include_declaration);
    alias("symbol/references", params, args.runtime)
}

fn symbol_callers_alias(args: AgentSymbolCallersArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "symbol", args.symbol);
    put_option_string(&mut params, "fileHint", args.file_hint);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_string(&mut params, "containingType", args.containing_type);
    put_option_enum(
        &mut params,
        "direction",
        args.direction.map(|value| value.canonical()),
    );
    put_option_u32(&mut params, "depth", args.depth);
    put_option_u32(&mut params, "maxTotalCalls", args.max_total_calls);
    put_option_u32(
        &mut params,
        "maxChildrenPerNode",
        args.max_children_per_node,
    );
    put_option_u32(&mut params, "timeoutMillis", args.timeout_millis);
    alias("symbol/callers", params, args.runtime)
}

fn raw_resolve_alias(args: AgentRawResolveArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    put_bool(
        &mut params,
        "includeDocumentation",
        args.include_documentation,
    );
    alias("raw/resolve", params, args.position.runtime)
}

fn raw_references_alias(args: AgentRawReferencesArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_bool(&mut params, "includeDeclaration", args.include_declaration);
    put_bool(
        &mut params,
        "includeUsageSiteScope",
        args.include_usage_site_scope,
    );
    alias("raw/references", params, args.position.runtime)
}

fn raw_call_hierarchy_alias(args: AgentRawCallHierarchyArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_string(
        &mut params,
        "direction",
        args.direction.canonical().to_string(),
    );
    put_option_u32(&mut params, "depth", args.depth);
    put_option_u32(&mut params, "maxTotalCalls", args.max_total_calls);
    put_option_u32(
        &mut params,
        "maxChildrenPerNode",
        args.max_children_per_node,
    );
    put_option_u32(&mut params, "timeoutMillis", args.timeout_millis);
    alias("raw/call-hierarchy", params, args.position.runtime)
}

fn raw_type_hierarchy_alias(args: AgentRawTypeHierarchyArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_enum(
        &mut params,
        "direction",
        args.direction.map(|value| value.canonical()),
    );
    put_option_u32(&mut params, "depth", args.depth);
    put_option_u32(&mut params, "maxResults", args.max_results);
    alias("raw/type-hierarchy", params, args.position.runtime)
}

fn raw_semantic_insertion_point_alias(args: AgentRawSemanticInsertionPointArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_string(&mut params, "target", args.target);
    alias(
        "raw/semantic-insertion-point",
        params,
        args.position.runtime,
    )
}

fn raw_rename_alias(args: AgentRawRenameArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_string(&mut params, "newName", args.new_name);
    put_bool(&mut params, "dryRun", args.dry_run);
    alias("raw/rename", params, args.position.runtime)
}

fn file_outline_alias(args: AgentFileOutlineArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "filePath", args.file_path);
    alias("raw/file-outline", params, args.runtime)
}

fn workspace_symbol_alias(args: AgentWorkspaceSymbolArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "pattern", args.pattern);
    put_option_enum(
        &mut params,
        "kind",
        args.kind.map(|value| value.canonical()),
    );
    put_option_u32(&mut params, "maxResults", args.max_results);
    put_bool(&mut params, "regex", args.regex);
    put_bool(
        &mut params,
        "includeDeclarationScope",
        args.include_declaration_scope,
    );
    alias("raw/workspace-symbol", params, args.runtime)
}

fn workspace_search_alias(args: AgentWorkspaceSearchArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "pattern", args.pattern);
    put_bool(&mut params, "regex", args.regex);
    put_option_u32(&mut params, "maxResults", args.max_results);
    put_option_string(&mut params, "fileGlob", args.file_glob);
    put_bool(&mut params, "caseSensitive", args.case_sensitive);
    alias("raw/workspace-search", params, args.runtime)
}

fn workspace_files_alias(args: AgentWorkspaceFilesArgs) -> AliasParts {
    let mut params = Map::new();
    put_option_string(&mut params, "moduleName", args.module_name);
    put_bool(&mut params, "includeFiles", args.include_files);
    put_option_u32(&mut params, "maxFilesPerModule", args.max_files_per_module);
    alias("raw/workspace-files", params, args.runtime)
}

fn raw_implementations_alias(args: AgentRawImplementationsArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_u32(&mut params, "maxResults", args.max_results);
    alias("raw/implementations", params, args.position.runtime)
}

fn raw_code_actions_alias(args: AgentRawCodeActionsArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_string(&mut params, "diagnosticCode", args.diagnostic_code);
    alias("raw/code-actions", params, args.position.runtime)
}

fn raw_completions_alias(args: AgentRawCompletionsArgs) -> AliasParts {
    let mut params = position_params(&args.position);
    put_option_u32(&mut params, "maxResults", args.max_results);
    if !args.kind_filter.is_empty() {
        params.insert("kindFilter".to_string(), json!(args.kind_filter));
    }
    alias("raw/completions", params, args.position.runtime)
}

fn file_paths_alias(method: &str, args: AgentFilePathsArgs) -> AliasParts {
    let mut params = Map::new();
    params.insert("filePaths".to_string(), json!(args.file_paths));
    alias(method, params, args.runtime)
}

fn optional_file_paths_alias(method: &str, args: AgentOptionalFilePathsArgs) -> AliasParts {
    let mut params = Map::new();
    if !args.file_paths.is_empty() {
        params.insert("filePaths".to_string(), json!(args.file_paths));
    }
    alias(method, params, args.runtime)
}

fn metrics_alias(args: AgentMetricsArgs) -> AliasParts {
    let mut params = Map::new();
    put_string(&mut params, "metric", args.metric.canonical().to_string());
    put_option_u32(&mut params, "limit", args.limit);
    put_option_string(&mut params, "symbol", args.symbol);
    put_option_u32(&mut params, "depth", args.depth);
    put_option_string(&mut params, "fileGlob", args.file_glob);
    put_option_string(&mut params, "folderFilter", args.folder_filter);
    alias("database/metrics", params, args.runtime)
}

fn alias(method: &str, params: Map<String, Value>, runtime: AgentRuntimeArgs) -> AliasParts {
    AliasParts {
        method: method.to_string(),
        params: Value::Object(params),
        runtime,
    }
}

fn position_params(position: &AgentPositionArgs) -> Map<String, Value> {
    let mut params = Map::new();
    params.insert(
        "position".to_string(),
        json!({
            "filePath": position.file_path,
            "offset": position.offset,
        }),
    );
    params
}

fn put_string(params: &mut Map<String, Value>, key: &str, value: String) {
    params.insert(key.to_string(), json!(value));
}

fn put_option_string(params: &mut Map<String, Value>, key: &str, value: Option<String>) {
    if let Some(value) = value {
        params.insert(key.to_string(), json!(value));
    }
}

fn put_option_u32(params: &mut Map<String, Value>, key: &str, value: Option<u32>) {
    if let Some(value) = value {
        params.insert(key.to_string(), json!(value));
    }
}

fn put_option_enum(params: &mut Map<String, Value>, key: &str, value: Option<&'static str>) {
    if let Some(value) = value {
        params.insert(key.to_string(), json!(value));
    }
}

fn put_bool(params: &mut Map<String, Value>, key: &str, value: bool) {
    if value {
        params.insert(key.to_string(), json!(true));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::{AgentRawCallDirection, AgentRawResolveArgs, AgentSymbolKind, BackendName};

    #[test]
    fn params_object_becomes_json_rpc_request() {
        let request =
            normalize_input("symbol/resolve", Some(json!({"symbol": "Widget"}))).expect("request");
        assert_eq!(request["method"], "symbol/resolve");
        assert_eq!(request["params"]["symbol"], "Widget");
        assert_eq!(request["id"], 1);
    }

    #[test]
    fn full_json_rpc_request_is_preserved() {
        let input = json!({
            "jsonrpc": "2.0",
            "method": "symbol/resolve",
            "params": { "symbol": "Widget" },
            "id": 42
        });
        let request = normalize_input("symbol/resolve", Some(input.clone())).expect("request");
        assert_eq!(request, input);
    }

    #[test]
    fn prior_agent_envelope_request_is_pipe_compatible() {
        let input = json!({
            "ok": true,
            "method": "symbol/resolve",
            "request": {
                "jsonrpc": "2.0",
                "method": "symbol/resolve",
                "params": { "symbol": "Widget" },
                "id": 1
            }
        });
        let request = normalize_input("symbol/resolve", Some(input)).expect("request");
        assert_eq!(request["params"]["symbol"], "Widget");
    }

    #[test]
    fn next_request_object_can_feed_the_selected_method() {
        let input = json!({
            "nextRequest": {
                "symbol": "Widget",
                "kind": "class"
            }
        });
        let request = normalize_input("symbol/resolve", Some(input)).expect("request");
        assert_eq!(request["method"], "symbol/resolve");
        assert_eq!(request["params"]["kind"], "class");
    }

    #[test]
    fn method_mismatch_is_rejected() {
        let input = json!({
            "jsonrpc": "2.0",
            "method": "symbol/references",
            "params": { "symbol": "Widget" },
            "id": 1
        });
        let error = normalize_input("symbol/resolve", Some(input)).expect_err("mismatch");
        assert_eq!(error.code, "AGENT_METHOD_MISMATCH");
    }

    #[test]
    fn raw_resolve_alias_builds_nested_position_params() {
        let args = AgentRawResolveArgs {
            position: AgentPositionArgs {
                runtime: AgentRuntimeArgs {
                    workspace_root: Some("/repo".into()),
                    backend_name: Some(BackendName::Idea),
                },
                file_path: "src/main.kt".to_string(),
                offset: 12,
            },
            include_declaration_scope: true,
            include_documentation: false,
        };
        let alias = raw_resolve_alias(args);
        assert_eq!(alias.method, "raw/resolve");
        assert_eq!(alias.params["position"]["filePath"], "src/main.kt");
        assert_eq!(alias.params["position"]["offset"], 12);
        assert_eq!(alias.params["includeDeclarationScope"], true);
        assert_eq!(alias.runtime.backend_name, Some(BackendName::Idea));
    }

    #[test]
    fn symbol_resolve_alias_uses_catalog_kind_values() {
        let args = AgentSymbolResolveArgs {
            runtime: AgentRuntimeArgs::default(),
            symbol: "Widget".to_string(),
            file_hint: None,
            kind: Some(AgentSymbolKind::Class),
            containing_type: None,
            include_declaration_scope: false,
            include_documentation: true,
            surrounding_lines: Some(3),
            include_surrounding_members: false,
        };
        let alias = symbol_resolve_alias(args);
        assert_eq!(alias.params["kind"], "class");
        assert_eq!(alias.params["includeDocumentation"], true);
        assert_eq!(alias.params["surroundingLines"], 3);
    }

    #[test]
    fn raw_call_hierarchy_alias_uses_backend_direction_values() {
        let args = AgentRawCallHierarchyArgs {
            position: AgentPositionArgs {
                runtime: AgentRuntimeArgs::default(),
                file_path: "src/main.kt".to_string(),
                offset: 12,
            },
            direction: AgentRawCallDirection::Incoming,
            depth: Some(2),
            max_total_calls: None,
            max_children_per_node: None,
            timeout_millis: None,
        };
        let alias = raw_call_hierarchy_alias(args);
        assert_eq!(alias.params["direction"], "INCOMING");
        assert_eq!(alias.params["depth"], 2);
    }
}
