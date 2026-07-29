use crate::agent;
use crate::cli::{
    AgentCallsArgs, AgentCommand, AgentDiagnosticsArgs, AgentDiagnosticsViewArgs,
    AgentHierarchyArgs, AgentHierarchyDirection, AgentImpactArgs, AgentImpactViewArgs,
    AgentImplementationsArgs, AgentNativeGraphArgs, AgentReferencesArgs, AgentRelationDepth,
    AgentRelationLimit, AgentRelationViewArgs, AgentReusableSymbolSelectorArgs, AgentRuntimeArgs,
    AgentSelectorHandle, AgentSymbolArgs, AgentSymbolMode, AgentSymbolViewArgs,
    AgentWorkspaceFilesArgs, AgentWorkspaceFilesField, AgentWorkspaceFilesViewArgs, KastGraphArgs,
    KastGraphCommand, KastPathsArgs, KastRefreshArgs, KastRefreshCommand, KastSymbolArgs,
    KastSymbolCommand, NativeGraphOperation, OutputFormat, WorkspaceDirtyFilter,
    WorkspaceRelativeGlob,
};
use crate::error::{CliError, Result};
use crate::runtime::{RuntimeState, RuntimeStatusResponse};
use crate::{config, output, runtime};
use serde::Serialize;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

#[derive(Debug, Serialize)]
struct ProjectedError {
    error: String,
    message: String,
    next: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpResult {
    root: String,
    ready: bool,
    runtime: &'static str,
    backend: String,
    reference_index_ready: bool,
    source_module_count: usize,
    next: Vec<&'static str>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EmptyCheckResult {
    changed_file_count: usize,
    diagnostic_count: usize,
    message: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EmptyRefreshResult {
    file_count: usize,
    message: &'static str,
}

pub(crate) fn run_up() -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    let mut args = crate::default_runtime_args();
    args.workspace_root = Some(workspace_root.clone());
    args.accept_indexing = Some(false);
    let deadline = Instant::now() + Duration::from_millis(args.wait_timeout_ms);
    let ensured = runtime::workspace_ensure(args.clone())?;
    if let Some(result) = ready_result(&workspace_root, ensured.selected.runtime_status.as_ref()) {
        return print_direct(&result);
    }

    let mut last_status = ensured.selected.runtime_status;
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        std::thread::sleep(remaining.min(Duration::from_millis(250)));
        let status = runtime::workspace_status(args.clone())?;
        last_status = status
            .selected
            .and_then(|candidate| candidate.runtime_status);
        if let Some(result) = ready_result(&workspace_root, last_status.as_ref()) {
            return print_direct(&result);
        }
    }

    let state = last_status
        .as_ref()
        .map(|status| runtime_state_name(&status.state))
        .unwrap_or("UNREACHABLE");
    let reference_index_ready = last_status
        .as_ref()
        .is_some_and(|status| status.reference_index_ready);
    let source_module_count = last_status
        .as_ref()
        .map_or(0, |status| status.source_module_names.len());
    Err(CliError::new(
        "SEMANTIC_EVIDENCE_NOT_READY",
        format!(
            "The exact workspace reached {state}, but semantic evidence did not become ready within {} ms (referenceIndexReady={reference_index_ready}, sourceModuleCount={source_module_count}). Let IDEA finish indexing, then run `kast up` again.",
            args.wait_timeout_ms
        ),
    ))
}

pub(crate) fn run_files(pattern: Option<String>) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    let mut args = workspace_files_args(workspace_root);
    args.glob = pattern
        .map(|value| {
            value
                .parse::<WorkspaceRelativeGlob>()
                .map_err(|message| CliError::new("CLI_USAGE", message))
        })
        .transpose()?;
    print_projected(AgentCommand::WorkspaceFiles(args))
}

pub(crate) fn run_symbol(args: KastSymbolArgs) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    match args.command {
        KastSymbolCommand::Find { query } => print_projected(symbol_lookup(
            workspace_root,
            query,
            AgentSymbolMode::Discovery,
        )),
        KastSymbolCommand::Show { symbol } => print_projected(symbol_lookup(
            workspace_root,
            symbol,
            AgentSymbolMode::Exact,
        )),
        KastSymbolCommand::Refs { symbol } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::References(AgentReferencesArgs {
                    runtime,
                    selector,
                    include_declaration: false,
                    limit: maximum_relation_limit(),
                    page_token: None,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Callers { symbol } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Callers(AgentCallsArgs {
                    runtime,
                    selector,
                    depth: Default::default(),
                    limit: maximum_relation_limit(),
                    page_token: None,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Callees { symbol } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Callees(AgentCallsArgs {
                    runtime,
                    selector,
                    depth: Default::default(),
                    limit: maximum_relation_limit(),
                    page_token: None,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Implementations { symbol } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Implementations(AgentImplementationsArgs {
                    runtime,
                    selector,
                    limit: maximum_relation_limit(),
                    page_token: None,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Supertypes { symbol } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Hierarchy(AgentHierarchyArgs {
                    runtime,
                    selector,
                    direction: AgentHierarchyDirection::Supertypes,
                    depth: maximum_relation_depth(),
                    limit: maximum_relation_limit(),
                    page_token: None,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
        KastSymbolCommand::Subtypes { symbol } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Hierarchy(AgentHierarchyArgs {
                    runtime,
                    selector,
                    direction: AgentHierarchyDirection::Subtypes,
                    depth: maximum_relation_depth(),
                    limit: maximum_relation_limit(),
                    page_token: None,
                    view: AgentRelationViewArgs::default(),
                })
            })
        }
    }
}

pub(crate) fn run_graph(args: KastGraphArgs) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    match args.command.unwrap_or(KastGraphCommand::Summary) {
        KastGraphCommand::Summary => {
            print_native_graph(workspace_root, NativeGraphOperation::Summary, None)
        }
        KastGraphCommand::Nodes => {
            print_native_graph(workspace_root, NativeGraphOperation::Nodes, None)
        }
        KastGraphCommand::Neighbors { symbol } => print_native_graph(
            workspace_root,
            NativeGraphOperation::Neighbors,
            Some(symbol),
        ),
        KastGraphCommand::Topology => {
            print_native_graph(workspace_root, NativeGraphOperation::Topology, None)
        }
        KastGraphCommand::Communities => {
            print_native_graph(workspace_root, NativeGraphOperation::Communities, None)
        }
        KastGraphCommand::Impact { symbol } => {
            run_symbol_relation(workspace_root, symbol, |runtime, selector| {
                AgentCommand::Impact(AgentImpactArgs {
                    runtime,
                    selector,
                    depth: Default::default(),
                    limit: maximum_relation_limit(),
                    page_token: None,
                    view: AgentImpactViewArgs::default(),
                })
            })
        }
    }
}

pub(crate) fn run_check(args: KastPathsArgs) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    let file_paths = if args.paths.is_empty() {
        match changed_kotlin_files(&workspace_root)? {
            Ok(file_paths) => file_paths,
            Err(envelope) => return print_projected_value(envelope),
        }
    } else {
        args.paths
            .into_iter()
            .map(|path| path.display().to_string())
            .collect()
    };
    if file_paths.is_empty() {
        return print_direct(&EmptyCheckResult {
            changed_file_count: 0,
            diagnostic_count: 0,
            message: "No changed Kotlin files were found.",
        });
    }
    print_projected(AgentCommand::Diagnostics(AgentDiagnosticsArgs {
        runtime: agent_runtime(workspace_root),
        file_paths,
        skip_refresh: false,
        limit: 500,
        page_token: None,
        view: AgentDiagnosticsViewArgs::default(),
    }))
}

pub(crate) fn run_refresh(args: KastRefreshArgs) -> Result<i32> {
    let workspace_root = config::resolve_workspace_root(None)?;
    if let Some(KastRefreshCommand::External { failure_ids }) = args.command {
        return run_external_refresh(workspace_root, failure_ids);
    }

    let requested_paths = if args.paths.is_empty() {
        match changed_kotlin_files(&workspace_root)? {
            Ok(file_paths) => file_paths,
            Err(envelope) => return print_projected_value(envelope),
        }
    } else {
        args.paths
            .into_iter()
            .map(|path| path.display().to_string())
            .collect()
    };
    if requested_paths.is_empty() {
        return print_direct(&EmptyRefreshResult {
            file_count: 0,
            message: "No changed Kotlin files were found.",
        });
    }
    let runtime_args = agent_runtime(workspace_root.clone());
    let file_paths = match agent::normalize_public_file_paths(&runtime_args, &requested_paths) {
        Ok(file_paths) => file_paths,
        Err(error) => return print_failure(&error.code, &error.message),
    };
    let refresh_response = raw_workspace_refresh(&workspace_root, &file_paths, &[])?;
    if let Some((code, message)) = rpc_failure(&refresh_response) {
        return print_failure(code, message);
    }
    let refresh_result = projected_result(&refresh_response)?;
    let refreshed_paths = string_array_field(refresh_result, "refreshedFiles")?;
    let removed_paths = string_array_field(refresh_result, "removedFiles")?;
    let externalizable_failures = refresh_relationship_failures(refresh_result, &refreshed_paths)?;

    let diagnostics = if refreshed_paths.is_empty() {
        json!({
            "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
            "cardinality": {"totalCount": 0, "returnedCount": 0, "truncated": false},
            "diagnostics": [],
        })
    } else {
        let envelope = projected_value(AgentCommand::Diagnostics(AgentDiagnosticsArgs {
            runtime: runtime_args,
            file_paths: refreshed_paths.clone(),
            skip_refresh: true,
            limit: 500,
            page_token: None,
            view: AgentDiagnosticsViewArgs::default(),
        }))?;
        if envelope.get("ok") != Some(&Value::Bool(true)) {
            return print_projected_value(envelope);
        }
        let result = projected_result(&envelope)?;
        json!({
            "severityCounts": required_field(result, "severityCounts")?,
            "cardinality": diagnostic_cardinality(result)?,
            "diagnostics": required_field(result, "diagnostics")?,
        })
    };

    let graph = projected_value(native_graph_command(
        workspace_root.clone(),
        NativeGraphOperation::Refresh,
        None,
        refreshed_paths.clone(),
        removed_paths.clone(),
    ))?;
    if graph.get("ok") != Some(&Value::Bool(true)) {
        return print_projected_value(graph);
    }
    let graph_result = projected_result(&graph)?;
    let next = externalizable_failures
        .iter()
        .map(|failure| {
            format!(
                "kast refresh external {}",
                failure["failureId"]
                    .as_str()
                    .expect("validated relationship failure id")
            )
        })
        .collect::<Vec<_>>();

    print_direct(&json!({
        "fileCount": file_paths.len(),
        "files": refreshed_paths,
        "removedFiles": removed_paths,
        "diagnostics": diagnostics,
        "graph": {
            "generation": required_field(graph_result, "generation")?,
            "symbolCount": required_field(graph_result, "symbolCount")?,
            "edgeOccurrenceCount": required_field(graph_result, "edgeOccurrenceCount")?,
            "coverage": required_field(graph_result, "coverage")?,
        },
        "externalizableFailures": externalizable_failures,
        "next": next,
    }))
}

pub(crate) fn print_projected(command: AgentCommand) -> Result<i32> {
    print_projected_value(projected_value(command)?)
}

fn print_native_graph(
    workspace_root: PathBuf,
    operation: NativeGraphOperation,
    symbol: Option<String>,
) -> Result<i32> {
    print_projected(native_graph_command(
        workspace_root,
        operation,
        symbol,
        Vec::new(),
        Vec::new(),
    ))
}

fn native_graph_command(
    workspace_root: PathBuf,
    operation: NativeGraphOperation,
    symbol: Option<String>,
    file_paths: Vec<String>,
    removed_file_paths: Vec<String>,
) -> AgentCommand {
    AgentCommand::Graph(AgentNativeGraphArgs {
        runtime: agent_runtime(workspace_root),
        database: None,
        scope: None,
        operation,
        file_paths,
        removed_file_paths,
        modules: Vec::new(),
        source_sets: Vec::new(),
        exclusive: false,
        symbol,
        generation: None,
        after_id: None,
        limit: (operation == NativeGraphOperation::Nodes).then_some(500),
        resolution: None,
    })
}

fn run_symbol_relation(
    workspace_root: PathBuf,
    symbol: String,
    command: impl FnOnce(AgentRuntimeArgs, AgentReusableSymbolSelectorArgs) -> AgentCommand,
) -> Result<i32> {
    let selector = match resolve_selector(&workspace_root, symbol)? {
        Ok(selector) => selector,
        Err(envelope) => return print_projected_value(envelope),
    };
    print_projected(command(
        agent_runtime(workspace_root),
        selector_args(selector),
    ))
}

fn resolve_selector(
    workspace_root: &Path,
    symbol: String,
) -> Result<std::result::Result<AgentSelectorHandle, Value>> {
    if symbol.starts_with("ksh1.") {
        return symbol
            .parse()
            .map(Ok)
            .map_err(|message| CliError::new("CLI_USAGE", message));
    }
    let envelope = projected_value(symbol_lookup(
        workspace_root.to_path_buf(),
        symbol,
        AgentSymbolMode::Exact,
    ))?;
    let selector = envelope
        .get("result")
        .and_then(|result| result.get("selectorHandle"))
        .and_then(Value::as_str);
    match selector {
        Some(selector) => selector
            .parse()
            .map(Ok)
            .map_err(|message| CliError::new("KAST_INVALID_AGENT_RESULT", message)),
        None => Ok(Err(envelope)),
    }
}

fn symbol_lookup(workspace_root: PathBuf, query: String, mode: AgentSymbolMode) -> AgentCommand {
    AgentCommand::Symbol(AgentSymbolArgs {
        runtime: agent_runtime(workspace_root),
        query,
        mode,
        kind: None,
        file_hint: None,
        containing_type: None,
        limit: 10,
        view: AgentSymbolViewArgs::default(),
    })
}

fn selector_args(selector_handle: AgentSelectorHandle) -> AgentReusableSymbolSelectorArgs {
    AgentReusableSymbolSelectorArgs {
        symbol: None,
        declaration_file: None,
        declaration_start_offset: None,
        kind: None,
        containing_type: None,
        selector_handle: Some(selector_handle),
    }
}

fn changed_kotlin_files(workspace_root: &Path) -> Result<std::result::Result<Vec<String>, Value>> {
    let mut args = workspace_files_args(workspace_root.to_path_buf());
    args.dirty = Some(WorkspaceDirtyFilter::Dirty);
    args.view = AgentWorkspaceFilesViewArgs {
        fields: vec![AgentWorkspaceFilesField::Path],
        ..Default::default()
    };
    let envelope = projected_value(AgentCommand::WorkspaceFiles(args))?;
    if envelope.get("ok") != Some(&Value::Bool(true)) {
        return Ok(Err(envelope));
    }
    let result = envelope.get("result").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Changed-file discovery completed without a result.",
        )
    })?;
    let coverage_complete = result.get("coverage").is_some_and(|coverage| {
        coverage.get("candidateInventory").and_then(Value::as_str) == Some("COMPLETE")
            && coverage.get("filterEvidence").and_then(Value::as_str) == Some("COMPLETE")
    });
    if !coverage_complete {
        return Err(CliError::new(
            "CHANGED_FILE_EVIDENCE_INCOMPLETE",
            "Kast could not prove the complete changed Kotlin file set. Pass explicit paths to `kast check`.",
        ));
    }
    let truncated = result
        .get("truncated")
        .and_then(Value::as_bool)
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Changed-file discovery returned no truncation evidence.",
            )
        })?;
    if truncated {
        return Err(CliError::new(
            "CHANGED_FILE_SET_TOO_LARGE",
            "More than 200 changed Kotlin files were found. Run `kast check <path>...` with explicit batches.",
        ));
    }
    let files = result.get("files").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "Changed-file discovery returned no file collection.",
        )
    })?;
    let mut file_paths = Vec::new();
    collect_string_fields(files, "filePath", &mut file_paths);
    file_paths.sort();
    file_paths.dedup();
    Ok(Ok(file_paths))
}

fn collect_string_fields(value: &Value, key: &str, values: &mut Vec<String>) {
    match value {
        Value::Object(fields) => {
            if let Some(value) = fields.get(key).and_then(Value::as_str) {
                values.push(value.to_string());
            }
            for value in fields.values() {
                collect_string_fields(value, key, values);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_string_fields(item, key, values);
            }
        }
        _ => {}
    }
}

pub(crate) fn projected_value(command: AgentCommand) -> Result<Value> {
    serde_json::to_value(agent::execute_projected(command)).map_err(CliError::from)
}

pub(crate) fn print_projected_value(envelope: Value) -> Result<i32> {
    let ok = envelope.get("ok").and_then(Value::as_bool).ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The typed operation returned no success state.",
        )
    })?;
    if !ok {
        let error = envelope.get("error").ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "The typed operation failed without an actionable error.",
            )
        })?;
        let code = error
            .get("code")
            .and_then(Value::as_str)
            .unwrap_or("KAST_OPERATION_FAILED");
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("The typed operation failed.");
        output::print_structured(
            &ProjectedError {
                error: code.to_string(),
                message: message.to_string(),
                next: "Run `kast --help` for valid commands and arguments.",
            },
            OutputFormat::Toon,
        )?;
        return Ok(1);
    }
    let result = envelope.get("result").cloned().ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The typed operation completed without a result.",
        )
    })?;
    print_direct(&sanitize_agent_result(result, true))
}

pub(crate) fn print_agent_result(result: Value) -> Result<i32> {
    print_direct(&sanitize_agent_result(result, true))
}

fn print_direct(value: &impl Serialize) -> Result<i32> {
    output::print_structured(value, OutputFormat::Toon)?;
    Ok(0)
}

fn sanitize_agent_result(value: Value, root: bool) -> Value {
    match value {
        Value::Object(fields) => {
            let nodes_truncated = fields.get("nextAfterId").map(|next| !next.is_null());
            let mut sanitized = fields
                .into_iter()
                .filter_map(|(key, value)| {
                    let protocol_cruft = matches!(
                        key.as_str(),
                        "ok" | "method"
                            | "schemaVersion"
                            | "pageToken"
                            | "nextPageToken"
                            | "afterId"
                            | "nextAfterId"
                    );
                    (!(protocol_cruft || root && key == "type"))
                        .then(|| (key, sanitize_agent_result(value, false)))
                })
                .collect::<serde_json::Map<_, _>>();
            if let Some(truncated) = nodes_truncated {
                sanitized.insert("truncated".to_string(), Value::Bool(truncated));
            }
            Value::Object(sanitized)
        }
        Value::Array(items) => Value::Array(
            items
                .into_iter()
                .map(|item| sanitize_agent_result(item, false))
                .collect(),
        ),
        scalar => scalar,
    }
}

pub(crate) fn agent_runtime(workspace_root: PathBuf) -> AgentRuntimeArgs {
    AgentRuntimeArgs {
        workspace_root: Some(workspace_root),
        ..Default::default()
    }
}

fn projected_result(envelope: &Value) -> Result<&Value> {
    envelope.get("result").ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "The typed operation completed without a result.",
        )
    })
}

fn required_field<'a>(result: &'a Value, field: &str) -> Result<&'a Value> {
    result.get(field).ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            format!("The typed operation returned no `{field}` field."),
        )
    })
}

fn diagnostic_cardinality(result: &Value) -> Result<Value> {
    let cardinality = required_field(result, "cardinality")?;
    Ok(json!({
        "totalCount": required_field(cardinality, "totalCount")?,
        "returnedCount": required_field(cardinality, "returnedCount")?,
        "truncated": required_field(cardinality, "truncated")?,
    }))
}

fn run_external_refresh(workspace_root: PathBuf, failure_ids: Vec<String>) -> Result<i32> {
    let response = raw_workspace_refresh(&workspace_root, &[], &failure_ids)?;
    if let Some((code, message)) = rpc_failure(&response) {
        return print_failure(code, message);
    }
    let outcomes = response
        .get("result")
        .and_then(|result| result.get("externalFailureOutcomes"))
        .and_then(Value::as_array)
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "External graph-boundary refresh returned no outcomes.",
            )
        })?;
    if outcomes.len() != failure_ids.len() {
        return Err(CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            "External graph-boundary refresh returned the wrong number of outcomes.",
        ));
    }
    let external = outcomes
        .iter()
        .zip(&failure_ids)
        .map(|(outcome, requested_id)| {
            let failure_id = outcome
                .get("failureId")
                .and_then(Value::as_str)
                .ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "External graph-boundary refresh returned an outcome without a failure id.",
                    )
                })?;
            if failure_id != requested_id {
                return Err(CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    "External graph-boundary refresh returned outcomes out of order.",
                ));
            }
            let status = outcome
                .get("status")
                .and_then(Value::as_str)
                .filter(|status| {
                    matches!(*status, "EXTERNALIZED" | "ALREADY_EXTERNAL" | "NOT_FOUND")
                })
                .ok_or_else(|| {
                    CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "External graph-boundary refresh returned an unknown status.",
                    )
                })?;
            Ok(json!({"failureId": failure_id, "status": status}))
        })
        .collect::<Result<Vec<_>>>()?;
    if external
        .iter()
        .any(|outcome| outcome["status"] == "NOT_FOUND")
    {
        output::print_structured(
            &json!({
                "external": external,
                "next": "Run `kast refresh <path>` for the affected file, then externalize the new failure ID."
            }),
            OutputFormat::Toon,
        )?;
        return Ok(1);
    }
    print_direct(&json!({"external": external}))
}

fn raw_workspace_refresh(
    workspace_root: &Path,
    file_paths: &[String],
    external_failure_ids: &[String],
) -> Result<Value> {
    let request = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "raw/workspace-refresh",
        "params": {
            "filePaths": file_paths,
            "externalFailureIds": external_failure_ids,
        }
    });
    let raw = runtime::raw_request_passthrough(
        serde_json::to_string(&request)?,
        Some(workspace_root.to_path_buf()),
        None,
    )?;
    serde_json::from_str(&raw).map_err(CliError::from)
}

fn rpc_failure(response: &Value) -> Option<(&str, &str)> {
    let error = response.get("error")?;
    let code = error
        .get("data")
        .and_then(|data| data.get("code"))
        .or_else(|| error.get("code"))
        .and_then(Value::as_str)
        .unwrap_or("RPC_ERROR");
    let message = error
        .get("data")
        .and_then(|data| data.get("message"))
        .or_else(|| error.get("message"))
        .and_then(Value::as_str)
        .unwrap_or("Workspace refresh failed.");
    Some((code, message))
}

fn print_failure(code: &str, message: &str) -> Result<i32> {
    output::print_structured(
        &ProjectedError {
            error: code.to_string(),
            message: message.to_string(),
            next: "Run `kast --help` for valid commands and arguments.",
        },
        OutputFormat::Toon,
    )?;
    Ok(1)
}

fn string_array_field(result: &Value, field: &str) -> Result<Vec<String>> {
    required_field(result, field)?
        .as_array()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                format!("The typed operation returned a non-array `{field}` field."),
            )
        })?
        .iter()
        .map(|value| {
            value.as_str().map(str::to_string).ok_or_else(|| {
                CliError::new(
                    "KAST_INVALID_AGENT_RESULT",
                    format!("The typed operation returned a non-string `{field}` entry."),
                )
            })
        })
        .collect()
}

fn refresh_relationship_failures(
    refresh_result: &Value,
    refreshed_paths: &[String],
) -> Result<Vec<Value>> {
    required_field(refresh_result, "relationshipFailures")?
        .as_array()
        .ok_or_else(|| {
            CliError::new(
                "KAST_INVALID_AGENT_RESULT",
                "Workspace refresh returned non-array relationship failure evidence.",
            )
        })?
        .iter()
        .map(|failure| {
            let failure_id = required_string(failure, "failureId")?;
            let file_path = required_string(failure, "filePath")?;
            let code = required_string(failure, "code")?;
            let valid_id = uuid::Uuid::parse_str(failure_id)
                .ok()
                .is_some_and(|id| id.hyphenated().to_string() == failure_id);
            if !valid_id || code != "PSI_UNAVAILABLE" || !refreshed_paths.iter().any(|path| path == file_path) {
                return Err(CliError::new(
                    "KAST_EXTERNAL_FAILURE_EVIDENCE_INVALID",
                    "Workspace refresh returned invalid externalizable relationship failure evidence.",
                ));
            }
            Ok(json!({"path": file_path, "failureId": failure_id, "code": code}))
        })
        .collect()
}

fn required_string<'a>(value: &'a Value, field: &str) -> Result<&'a str> {
    value.get(field).and_then(Value::as_str).ok_or_else(|| {
        CliError::new(
            "KAST_INVALID_AGENT_RESULT",
            format!("The typed operation returned no string `{field}` field."),
        )
    })
}

fn workspace_files_args(workspace_root: PathBuf) -> AgentWorkspaceFilesArgs {
    AgentWorkspaceFilesArgs {
        runtime: agent_runtime(workspace_root),
        module: None,
        source_set: None,
        kind: None,
        package_selector: None,
        dirty: None,
        drift: None,
        path_prefix: None,
        glob: None,
        limit: "200"
            .parse()
            .expect("the typed maximum workspace-file limit is valid"),
        page_token: None,
        view: Default::default(),
    }
}

fn maximum_relation_limit() -> AgentRelationLimit {
    "200"
        .parse()
        .expect("the typed maximum relationship limit is valid")
}

fn maximum_relation_depth() -> AgentRelationDepth {
    "8".parse()
        .expect("the typed maximum relationship depth is valid")
}

fn ready_result(workspace_root: &Path, status: Option<&RuntimeStatusResponse>) -> Option<UpResult> {
    let status = status?;
    semantic_status_ready(workspace_root, status).then(|| UpResult {
        root: workspace_root.display().to_string(),
        ready: true,
        runtime: "READY",
        backend: status.backend_name.clone(),
        reference_index_ready: true,
        source_module_count: status.source_module_names.len(),
        next: vec!["kast refresh", "kast files", "kast symbol find <query>"],
    })
}

fn semantic_status_ready(workspace_root: &Path, status: &RuntimeStatusResponse) -> bool {
    config::normalize(PathBuf::from(&status.workspace_root))
        == config::normalize(workspace_root.to_path_buf())
        && status.state == RuntimeState::Ready
        && status.healthy
        && status.active
        && !status.indexing
        && status.reference_index_ready
        && !status.source_module_names.is_empty()
}

fn runtime_state_name(state: &RuntimeState) -> &'static str {
    match state {
        RuntimeState::Starting => "STARTING",
        RuntimeState::Indexing => "INDEXING",
        RuntimeState::Ready => "READY",
        RuntimeState::Degraded => "DEGRADED",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn readiness_requires_exact_ready_runtime_and_semantic_index_evidence() {
        let root = Path::new("/workspace");
        let mut status = RuntimeStatusResponse {
            state: RuntimeState::Ready,
            healthy: true,
            active: true,
            indexing: false,
            backend_name: "idea".to_string(),
            backend_version: "test".to_string(),
            workspace_root: root.display().to_string(),
            message: None,
            warnings: Vec::new(),
            source_module_names: vec!["main".to_string()],
            dependent_module_names_by_source_module_name: serde_json::Map::new(),
            reference_index_ready: true,
            schema_version: crate::SCHEMA_VERSION,
        };

        assert!(semantic_status_ready(root, &status));
        status.reference_index_ready = false;
        assert!(!semantic_status_ready(root, &status));
        status.reference_index_ready = true;
        status.source_module_names.clear();
        assert!(!semantic_status_ready(root, &status));
        status.source_module_names.push("main".to_string());
        status.workspace_root = "/different".to_string();
        assert!(!semantic_status_ready(root, &status));
    }

    #[test]
    fn sanitizer_removes_protocol_cruft_but_preserves_nested_discriminants() {
        let result = sanitize_agent_result(
            json!({
                "type": "ROOT",
                "ok": true,
                "method": "agent/example",
                "schemaVersion": 1,
                "item": {
                    "type": "NESTED",
                    "ok": true,
                    "schemaVersion": 1
                }
            }),
            true,
        );

        assert_eq!(result, json!({"item": {"type": "NESTED"}}));
    }

    #[test]
    fn sanitizer_replaces_unusable_continuations_with_honest_truncation() {
        let result = sanitize_agent_result(
            json!({
                "type": "KAST_NATIVE_GRAPH_NODES",
                "afterId": 0,
                "nextAfterId": 42,
                "nextPageToken": "opaque",
                "nodes": []
            }),
            true,
        );

        assert_eq!(result, json!({"nodes": [], "truncated": true}));
    }
}
