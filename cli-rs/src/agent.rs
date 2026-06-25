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
    AgentWorkflowSymbolArgs, AgentWorkflowWriteMode, AgentWorkflowWriteValidateArgs,
    AgentWorkspaceFilesArgs, AgentWorkspaceSearchArgs, AgentWorkspaceSymbolArgs, RpcArgs,
};
use crate::error::{CliError, Result};
use crate::{catalog_schema, manifest, output, runtime, self_mgmt, validate};
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
            method_argument: "<method>",
            params_file_flag: "--params-file",
            workspace_root_flag: "--workspace-root",
        },
        tools,
        schema_version: SCHEMA_VERSION,
    })
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
    let description = tool
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
        description: description.to_string(),
        default_args: tool.get("defaultArgs").cloned(),
        parameters,
        mutates: agent_tool_mutates(method),
    })
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
                AgentWorkflowStep {
                    name: "health",
                    method: "health",
                    params: json!({}),
                    mutates: false,
                },
                AgentWorkflowStep {
                    name: "runtime-status",
                    method: "runtime/status",
                    params: json!({}),
                    mutates: false,
                },
                AgentWorkflowStep {
                    name: "capabilities",
                    method: "capabilities",
                    params: json!({}),
                    mutates: false,
                },
            ],
        )),
        AgentWorkflowCommand::Symbol(args) => symbol_workflow_steps(args),
        AgentWorkflowCommand::Impact(args) => Ok((
            "impact".to_string(),
            args.common,
            vec![AgentWorkflowStep {
                name: "impact",
                method: "database/metrics",
                params: json!({
                    "metric": "impact",
                    "symbol": args.symbol,
                    "depth": args.depth,
                    "limit": args.limit,
                }),
                mutates: false,
            }],
        )),
        AgentWorkflowCommand::Diagnostics(args) => diagnostics_workflow_steps(args),
        AgentWorkflowCommand::RenamePlan(args) => Ok((
            "rename-plan".to_string(),
            args.common,
            vec![AgentWorkflowStep {
                name: "rename-plan",
                method: "raw/rename",
                params: json!({
                    "position": {
                        "filePath": args.file_path,
                        "offset": args.offset,
                    },
                    "newName": args.new_name,
                    "dryRun": true,
                }),
                mutates: false,
            }],
        )),
        AgentWorkflowCommand::WriteValidate(args) => write_validate_workflow_steps(args),
        AgentWorkflowCommand::PackageVerify(args) => Ok((
            "package-verify".to_string(),
            args.common,
            vec![AgentWorkflowStep {
                name: "ready",
                method: "package/verify",
                params: json!({}),
                mutates: false,
            }],
        )),
    }
}

fn symbol_workflow_steps(
    args: AgentWorkflowSymbolArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    let mut steps = vec![
        AgentWorkflowStep {
            name: "symbol-query",
            method: "symbol/query",
            params: json!({
                "query": args.symbol,
                "modes": ["exact", "lexical"],
                "filters": {},
                "limit": args.query_limit,
                "includeEvidence": true,
                "includeNextRequests": true,
            }),
            mutates: false,
        },
        AgentWorkflowStep {
            name: "symbol-resolve",
            method: "symbol/resolve",
            params: drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "includeDeclarationScope": true,
                "includeDocumentation": true,
                "surroundingLines": 3,
                "includeSurroundingMembers": true,
            })),
            mutates: false,
        },
    ];
    if args.references {
        steps.push(AgentWorkflowStep {
            name: "symbol-references",
            method: "symbol/references",
            params: drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "includeDeclaration": args.include_declaration,
            })),
            mutates: false,
        });
    }
    if let Some(direction) = args.callers {
        steps.push(AgentWorkflowStep {
            name: "symbol-callers",
            method: "symbol/callers",
            params: drop_nulls(json!({
                "symbol": args.symbol,
                "kind": args.kind.map(|kind| kind.canonical()),
                "fileHint": args.file_hint,
                "containingType": args.containing_type,
                "direction": direction.canonical(),
                "depth": args.caller_depth,
            })),
            mutates: false,
        });
    }
    Ok(("symbol".to_string(), args.common, steps))
}

fn diagnostics_workflow_steps(
    args: AgentWorkflowDiagnosticsArgs,
) -> std::result::Result<(String, AgentWorkflowCommonArgs, Vec<AgentWorkflowStep>), AgentError> {
    let mut steps = Vec::new();
    if !args.skip_refresh {
        steps.push(AgentWorkflowStep {
            name: "workspace-refresh",
            method: "raw/workspace-refresh",
            params: json!({ "filePaths": args.file_paths }),
            mutates: false,
        });
    }
    steps.push(AgentWorkflowStep {
        name: "diagnostics",
        method: "raw/diagnostics",
        params: json!({ "filePaths": args.file_paths }),
        mutates: false,
    });
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
        vec![AgentWorkflowStep {
            name: "write-and-validate",
            method: "symbol/write-and-validate",
            params,
            mutates: true,
        }],
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
        let summary = run_workflow_step(&out_dir, common, &step)?;
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
    step: &AgentWorkflowStep,
) -> Result<AgentWorkflowStepSummary> {
    let step_dir = out_dir.join(step.name);
    fs::create_dir_all(&step_dir)?;
    let params_file = step_dir.join("input.json");
    let stdout_file = step_dir.join("stdout.json");
    let stderr_file = step_dir.join("stderr.txt");
    write_json_file(&params_file, &step.params)?;
    let (exit_code, summary) = if common.dry_run {
        (
            0,
            json!({
                "ok": true,
                "dryRun": true,
                "method": step.method,
                "mutates": step.mutates,
                "nextRequest": json_rpc_request(step.method, step.params.clone()),
                "schemaVersion": SCHEMA_VERSION,
            }),
        )
    } else if step.method == "package/verify" {
        let result = self_mgmt::doctor(false, ReadyTarget::Agent)?;
        let exit_code = if result.ok { 0 } else { 1 };
        (exit_code, serde_json::to_value(result)?)
    } else {
        let envelope = execute_request(AgentRequest {
            method: step.method.to_string(),
            request: json_rpc_request(step.method, step.params.clone()),
            runtime: common.runtime.clone(),
        });
        let exit_code = if envelope.ok { 0 } else { 1 };
        (exit_code, serde_json::to_value(envelope)?)
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
