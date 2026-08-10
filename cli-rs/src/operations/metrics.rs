use crate::SCHEMA_VERSION;
use crate::cli::OutputFormat;
use crate::cli::{
    MetricsCommand, MetricsFilterArgs, MetricsImpactArgs, MetricsLimitArgs, MetricsScopeArgs,
    MetricsSearchArgs,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::metrics_database::{
    AgentImpactPageOffset, BoundedMetricsResult, DirectMetricsError, DirectResult, FileFilter,
    ImpactSubjectIdentity, MetricsDatabase,
};
use crate::output;
use crate::runtime;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone)]
pub(crate) struct MetricsRequest {
    workspace_root: PathBuf,
    database: PathBuf,
    published_read: Option<runtime::SemanticWorkspaceRead>,
    metric: &'static str,
    limit: usize,
    symbol: Option<String>,
    depth: usize,
    impact_subject: Option<ImpactSubjectIdentity>,
    impact_offset: AgentImpactPageOffset,
    filter: FileFilter,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricsQuery {
    workspace_root: String,
    metric: String,
    limit: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    symbol: Option<String>,
    depth: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    subject: Option<ImpactSubjectIdentity>,
    #[serde(skip_serializing_if = "Option::is_none")]
    offset: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    file_glob: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    folder_filter: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricsResponse {
    ok: bool,
    query: MetricsQuery,
    results: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    total_count: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    returned_count: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    truncated: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_offset: Option<usize>,
    log_file: String,
    schema_version: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MetricsRpcParams {
    workspace_root: Option<PathBuf>,
    metric: String,
    limit: Option<usize>,
    symbol: Option<String>,
    depth: Option<usize>,
    file_glob: Option<String>,
    folder_filter: Option<String>,
    subject: Option<ImpactSubjectIdentity>,
    offset: Option<usize>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricsRpcResponse {
    #[serde(rename = "type")]
    response_type: &'static str,
    ok: bool,
    query: MetricsQuery,
    results: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    total_count: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    returned_count: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    truncated: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_offset: Option<usize>,
    log_file: String,
    schema_version: u32,
}

pub fn run(command: MetricsCommand, output_format: OutputFormat) -> Result<i32> {
    let request = MetricsRequest::from_command(command)?;
    let result = query_direct(&request);
    match result {
        Ok(results) => print_metrics_response(&request, results, output_format),
        Err(error) => Err(error.into_cli_error()),
    }
}

struct DirectMetricsQueryResult {
    results: Value,
    total_count: Option<usize>,
    returned_count: Option<usize>,
    truncated: Option<bool>,
    next_offset: Option<AgentImpactPageOffset>,
}

impl DirectMetricsQueryResult {
    fn unbounded(results: Value) -> Self {
        Self {
            results,
            total_count: None,
            returned_count: None,
            truncated: None,
            next_offset: None,
        }
    }

    fn bounded(result: BoundedMetricsResult) -> Self {
        Self {
            results: result.results,
            total_count: Some(result.total_count),
            returned_count: Some(result.returned_count),
            truncated: Some(result.truncated),
            next_offset: result.next_offset,
        }
    }
}

fn query_direct(request: &MetricsRequest) -> DirectResult<DirectMetricsQueryResult> {
    let db = MetricsDatabase::open(request)?;
    let result = match request.metric {
        "fanIn" => db
            .fan_in(request.limit)
            .map(DirectMetricsQueryResult::unbounded),
        "fanOut" => db
            .fan_out(request.limit)
            .map(DirectMetricsQueryResult::unbounded),
        "deadCode" => db.dead_code().map(DirectMetricsQueryResult::unbounded),
        "impact" => match &request.impact_subject {
            Some(subject) => {
                db.impact_page(subject, request.depth, request.limit, request.impact_offset)
            }
            None => db.impact(
                request.symbol.as_deref().unwrap_or_default(),
                request.depth,
                request.limit,
            ),
        }
        .map(DirectMetricsQueryResult::bounded),
        "coupling" => db.coupling().map(DirectMetricsQueryResult::unbounded),
        "search" => db
            .search(request.symbol.as_deref().unwrap_or_default(), request.limit)
            .map(DirectMetricsQueryResult::unbounded),
        other => Err(DirectMetricsError::Query(CliError::new(
            "METRICS_UNSUPPORTED",
            format!("Unsupported metrics command: {other}"),
        ))),
    };
    result.and_then(|value| {
        if let Some(read) = &request.published_read {
            return read
                .revalidate()
                .map_err(DirectMetricsError::Query)
                .map(|proof| proof.finish(value));
        }
        Ok(value)
    })
}

pub(crate) fn try_handle_raw_rpc(
    raw_request: &str,
    workspace_root: &Path,
    published: &crate::published_workspace::PublishedWorkspaceDatabase,
) -> Result<Option<String>> {
    let request: Value = serde_json::from_str(raw_request)?;
    if request.get("method").and_then(Value::as_str) != Some("database/metrics") {
        return Ok(None);
    }

    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let params = request.get("params").cloned().unwrap_or_else(|| json!({}));
    let parsed = match serde_json::from_value::<MetricsRpcParams>(params) {
        Ok(params) => params,
        Err(error) => {
            return Ok(Some(serde_json::to_string(&json_rpc_success(
                id,
                json!({
                    "type": "METRICS_FAILURE",
                    "ok": false,
                    "stage": "validate",
                    "message": error.to_string(),
                    "logFile": "",
                }),
            ))?));
        }
    };
    let request = match MetricsRequest::from_rpc_params(parsed, workspace_root, published) {
        Ok(request) => request,
        Err(error) => {
            return Ok(Some(serde_json::to_string(&json_rpc_success(
                id,
                json!({
                    "type": "METRICS_FAILURE",
                    "ok": false,
                    "stage": "validate",
                    "message": error.message,
                    "logFile": "",
                    "schemaVersion": SCHEMA_VERSION,
                }),
            ))?));
        }
    };
    let result = query_direct(&request);
    let response = match result {
        Ok(result) => serde_json::to_value(MetricsRpcResponse {
            response_type: "METRICS_SUCCESS",
            ok: true,
            query: request.query(),
            results: result.results,
            total_count: result.total_count,
            returned_count: result.returned_count,
            truncated: result.truncated,
            next_offset: result.next_offset.map(AgentImpactPageOffset::get),
            log_file: String::new(),
            schema_version: SCHEMA_VERSION,
        })?,
        Err(error) => {
            let error = error.into_cli_error();
            json!({
                "type": "METRICS_FAILURE",
                "ok": false,
                "code": error.code,
                "stage": "query",
                "message": error.message,
                "query": request.query(),
                "logFile": "",
                "schemaVersion": SCHEMA_VERSION,
            })
        }
    };
    Ok(Some(serde_json::to_string(&json_rpc_success(
        id, response,
    ))?))
}

fn json_rpc_success(id: Value, result: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "result": result,
        "id": id
    })
}

fn print_metrics_response(
    request: &MetricsRequest,
    result: DirectMetricsQueryResult,
    output_format: OutputFormat,
) -> Result<i32> {
    let response = serde_json::to_value(MetricsResponse {
        ok: true,
        query: request.query(),
        results: result.results,
        total_count: result.total_count,
        returned_count: result.returned_count,
        truncated: result.truncated,
        next_offset: result.next_offset.map(AgentImpactPageOffset::get),
        log_file: String::new(),
        schema_version: SCHEMA_VERSION,
    })?;
    if output_format.is_structured() {
        output::print_structured(&response, output_format)?;
        Ok(0)
    } else {
        print_human_metrics_response(request, &response)
    }
}

fn print_human_metrics_response(request: &MetricsRequest, response: &Value) -> Result<i32> {
    let mut markdown = String::new();
    push_markdown_line(
        &mut markdown,
        format_args!("# Kast metrics {}", metric_display_name(request.metric)),
    );
    markdown.push('\n');
    push_markdown_line(
        &mut markdown,
        format_args!("- Workspace: `{}`", request.workspace_root.display()),
    );
    push_markdown_line(
        &mut markdown,
        format_args!("- Database: `{}`", request.database.display()),
    );
    push_markdown_line(&mut markdown, format_args!("- Limit: {}", request.limit));
    if let Some(symbol) = &request.symbol {
        push_markdown_line(&mut markdown, format_args!("- Symbol/query: `{symbol}`"));
    }
    if request.depth != 3 || request.metric == "impact" {
        push_markdown_line(&mut markdown, format_args!("- Depth: {}", request.depth));
    }
    if let Some(file_glob) = request.filter.file_glob() {
        push_markdown_line(&mut markdown, format_args!("- File glob: `{file_glob}`"));
    }
    if let Some(folder_filter) = request.filter.folder_filter() {
        push_markdown_line(
            &mut markdown,
            format_args!("- Folder filter: `{folder_filter}`"),
        );
    }
    markdown.push('\n');
    push_markdown_line(&mut markdown, format_args!("## Results"));
    let results = response.get("results").unwrap_or(&Value::Null);
    push_metric_results(&mut markdown, results);
    markdown.push('\n');
    push_markdown_line(
        &mut markdown,
        format_args!("Use `kast --output toon metrics ...` for the structured metrics payload."),
    );
    output::print_markdown(&markdown)?;
    Ok(0)
}

fn push_markdown_line(markdown: &mut String, args: std::fmt::Arguments<'_>) {
    use std::fmt::Write as _;
    markdown
        .write_fmt(args)
        .expect("writing to a String cannot fail");
    markdown.push('\n');
}

include!("parts/metrics/rendering.rs");
