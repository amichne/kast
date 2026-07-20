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
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone)]
pub(crate) struct MetricsRequest {
    workspace_root: PathBuf,
    database: PathBuf,
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
    match request.metric {
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
    }
}

pub(crate) fn try_handle_raw_rpc(
    raw_request: &str,
    workspace_root_arg: Option<PathBuf>,
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
    let request = match MetricsRequest::from_rpc_params(parsed, workspace_root_arg) {
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

fn push_metric_results(markdown: &mut String, results: &Value) {
    match results {
        Value::Array(items) if items.is_empty() => {
            push_markdown_line(markdown, format_args!("No rows matched."));
        }
        Value::Array(items) => {
            for item in items.iter().take(20) {
                push_markdown_line(markdown, format_args!("- {}", summarize_value(item)));
            }
            if items.len() > 20 {
                push_markdown_line(
                    markdown,
                    format_args!("- ... {} more rows", items.len() - 20),
                );
            }
        }
        Value::Object(object) => {
            if let Some(nodes) = object.get("nodes").and_then(Value::as_array) {
                push_markdown_line(markdown, format_args!("- Nodes: {}", nodes.len()));
            }
            if let Some(edges) = object.get("edges").and_then(Value::as_array) {
                push_markdown_line(markdown, format_args!("- Edges: {}", edges.len()));
            }
            let summary = summarize_value(results);
            if summary != "object" {
                push_markdown_line(markdown, format_args!("- {summary}"));
            }
        }
        Value::Null => push_markdown_line(markdown, format_args!("No results were returned.")),
        other => push_markdown_line(markdown, format_args!("- {}", summarize_value(other))),
    }
}

fn summarize_value(value: &Value) -> String {
    match value {
        Value::Object(object) => {
            let preferred = [
                "targetFqName",
                "sourceFqName",
                "fqName",
                "filePath",
                "path",
                "modulePath",
                "edgeType",
                "occurrenceCount",
                "referenceCount",
                "incomingReferences",
                "outgoingReferences",
                "focalNodeId",
            ];
            let fields: Vec<_> = preferred
                .iter()
                .filter_map(|key| {
                    object
                        .get(*key)
                        .map(|field| format!("{key}={}", summarize_scalar(field)))
                })
                .collect();
            if fields.is_empty() {
                "object".to_string()
            } else {
                fields.join(", ")
            }
        }
        other => summarize_scalar(other),
    }
}

fn summarize_scalar(value: &Value) -> String {
    match value {
        Value::String(value) => format!("`{value}`"),
        Value::Number(value) => value.to_string(),
        Value::Bool(value) => value.to_string(),
        Value::Array(values) => format!("{} item(s)", values.len()),
        Value::Object(_) => "object".to_string(),
        Value::Null => "null".to_string(),
    }
}

fn metric_display_name(metric: &str) -> &'static str {
    match metric {
        "fanIn" => "fan-in",
        "fanOut" => "fan-out",
        "deadCode" => "dead-code",
        "impact" => "impact",
        "coupling" => "coupling",
        "search" => "search",
        _ => "query",
    }
}

impl MetricsRequest {
    fn from_command(command: MetricsCommand) -> Result<Self> {
        match command {
            MetricsCommand::FanIn(args) => Self::from_limit("fanIn", args),
            MetricsCommand::FanOut(args) => Self::from_limit("fanOut", args),
            MetricsCommand::DeadCode(args) => Self::from_filter("deadCode", args, 50, None, 3),
            MetricsCommand::Impact(args) => Self::from_impact(args),
            MetricsCommand::Coupling(scope) => Self::from_scope("coupling", scope, 50, None, 3),
            MetricsCommand::Search(args) => Self::from_search(args),
        }
    }

    fn from_limit(metric: &'static str, args: MetricsLimitArgs) -> Result<Self> {
        Self::from_filter(metric, args.filter, args.limit, None, 3)
    }

    fn from_impact(args: MetricsImpactArgs) -> Result<Self> {
        Self::from_filter("impact", args.filter, 50, Some(args.symbol), args.depth)
    }

    fn from_search(args: MetricsSearchArgs) -> Result<Self> {
        Self::from_scope("search", args.scope, args.limit, Some(args.query), 3)
    }

    fn from_filter(
        metric: &'static str,
        args: MetricsFilterArgs,
        limit: usize,
        symbol: Option<String>,
        depth: usize,
    ) -> Result<Self> {
        let mut request = Self::from_scope(metric, args.scope, limit, symbol, depth)?;
        request.filter = FileFilter::new(args.file_glob, args.folder_filter)?;
        Ok(request)
    }

    fn from_scope(
        metric: &'static str,
        scope: MetricsScopeArgs,
        limit: usize,
        symbol: Option<String>,
        depth: usize,
    ) -> Result<Self> {
        let workspace_root = config::resolve_workspace_root(scope.workspace_root)?;
        let database = scope
            .database
            .map(config::normalize)
            .unwrap_or(config::workspace_database_path(&workspace_root)?);
        Ok(Self {
            workspace_root,
            database,
            metric,
            limit,
            symbol,
            depth,
            impact_subject: None,
            impact_offset: AgentImpactPageOffset::first(),
            filter: FileFilter::new(None, None)?,
        })
    }

    fn from_rpc_params(
        params: MetricsRpcParams,
        workspace_root_arg: Option<PathBuf>,
    ) -> Result<Self> {
        let metric = match params.metric.as_str() {
            "fanIn" => "fanIn",
            "fanOut" => "fanOut",
            "deadCode" => "deadCode",
            "impact" => "impact",
            "coupling" => "coupling",
            "search" => "search",
            other => {
                return Err(CliError::new(
                    "METRICS_UNSUPPORTED",
                    format!("Unsupported Rust metrics command: {other}"),
                ));
            }
        };
        let workspace_root =
            config::resolve_workspace_root(params.workspace_root.or(workspace_root_arg))?;
        let database = config::workspace_database_path(&workspace_root)?;
        let impact_offset = AgentImpactPageOffset::try_from(params.offset.unwrap_or_default())
            .map_err(|message| CliError::new("IMPACT_PAGE_TOKEN_INVALID", message))?;
        if metric != "impact" && (params.subject.is_some() || params.offset.is_some()) {
            return Err(CliError::new(
                "METRICS_REQUEST_INVALID",
                "subject and offset are valid only for impact metrics",
            ));
        }
        if metric == "impact" && params.offset.is_some() && params.subject.is_none() {
            return Err(CliError::new(
                "METRICS_REQUEST_INVALID",
                "an impact offset requires an exact impact subject",
            ));
        }
        if let Some(subject) = params.subject.as_ref()
            && (!subject.is_valid() || params.symbol.as_deref() != Some(subject.fq_name()))
        {
            return Err(CliError::new(
                "METRICS_REQUEST_INVALID",
                "the impact subject must be complete and match the query symbol",
            ));
        }
        Ok(Self {
            workspace_root,
            database,
            metric,
            limit: params.limit.unwrap_or(50),
            symbol: params.symbol,
            depth: params.depth.unwrap_or(3),
            impact_subject: params.subject,
            impact_offset,
            filter: FileFilter::new(params.file_glob, params.folder_filter)?,
        })
    }

    fn query(&self) -> MetricsQuery {
        MetricsQuery {
            workspace_root: self.workspace_root.display().to_string(),
            metric: self.metric.to_string(),
            limit: self.limit,
            symbol: self.symbol.clone(),
            depth: self.depth,
            subject: self.impact_subject.clone(),
            offset: (self.metric == "impact").then_some(self.impact_offset.get()),
            file_glob: self.filter.file_glob().map(str::to_string),
            folder_filter: self.filter.folder_filter().map(str::to_string),
        }
    }

    pub(crate) fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }

    pub(crate) fn database(&self) -> &Path {
        &self.database
    }

    pub(crate) fn filter(&self) -> &FileFilter {
        &self.filter
    }

    #[cfg(test)]
    pub(crate) fn for_test(
        workspace_root: PathBuf,
        database: PathBuf,
        metric: &'static str,
        symbol: Option<String>,
        limit: usize,
        depth: usize,
    ) -> Result<Self> {
        Ok(Self {
            workspace_root,
            database,
            metric,
            limit,
            symbol,
            depth,
            impact_subject: None,
            impact_offset: AgentImpactPageOffset::first(),
            filter: FileFilter::new(None, None)?,
        })
    }
}
