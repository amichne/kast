use crate::SCHEMA_VERSION;
use crate::cli::{
    MetricsCommand, MetricsFilterArgs, MetricsGraphArgs, MetricsImpactArgs, MetricsLimitArgs,
    MetricsScopeArgs, MetricsSearchArgs,
};
use crate::config;
use crate::error::{CliError, Result};
use crate::metrics_database::{
    DirectMetricsError, DirectResult, FileFilter, MetricsDatabase, MetricsGraph, MetricsGraphNode,
};
use crossterm::event::{self, Event, KeyCode};
use crossterm::execute;
use crossterm::terminal::{
    EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode,
};
use ratatui::Terminal;
use ratatui::backend::CrosstermBackend;
use ratatui::layout::{Constraint, Direction, Layout};
use ratatui::style::{Modifier, Style};
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Wrap};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::env;
use std::io::{self, IsTerminal};
use std::path::{Path, PathBuf};
use std::time::Duration;

#[derive(Debug, Clone)]
pub(crate) struct MetricsRequest {
    workspace_root: PathBuf,
    database: PathBuf,
    metric: &'static str,
    limit: usize,
    symbol: Option<String>,
    depth: usize,
    filter: FileFilter,
    graph_json: bool,
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
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MetricsRpcResponse {
    #[serde(rename = "type")]
    response_type: &'static str,
    ok: bool,
    query: MetricsQuery,
    results: Value,
    log_file: String,
    schema_version: u32,
}

pub fn run(command: MetricsCommand) -> Result<i32> {
    let request = MetricsRequest::from_command(command)?;
    if request.metric == "graph" {
        return run_graph(request);
    }

    let result = query_direct(&request);
    match result {
        Ok(results) => print_metrics_response(&request, results),
        Err(error) => Err(error.into_cli_error()),
    }
}

fn run_graph(request: MetricsRequest) -> Result<i32> {
    let graph = MetricsDatabase::open(&request)
        .and_then(|db| db.graph(request.symbol.as_deref().unwrap_or_default(), request.depth))
        .map_err(DirectMetricsError::into_cli_error)?;

    if request.graph_json || !io::stdout().is_terminal() {
        return print_metrics_response(&request, serde_json::to_value(graph)?);
    }

    run_graph_tui(&graph)
}

fn query_direct(request: &MetricsRequest) -> DirectResult<Value> {
    let db = MetricsDatabase::open(request)?;
    match request.metric {
        "fanIn" => db.fan_in(request.limit),
        "fanOut" => db.fan_out(request.limit),
        "deadCode" => db.dead_code(),
        "impact" => db.impact(request.symbol.as_deref().unwrap_or_default(), request.depth),
        "coupling" => db.coupling(),
        "search" => db.search(request.symbol.as_deref().unwrap_or_default(), request.limit),
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
    let result = if request.metric == "graph" {
        MetricsDatabase::open(&request)
            .and_then(|db| db.graph(request.symbol.as_deref().unwrap_or_default(), request.depth))
            .and_then(|graph| {
                serde_json::to_value(graph)
                    .map_err(|error| DirectMetricsError::Query(CliError::from(error)))
            })
    } else {
        query_direct(&request)
    };
    let response = match result {
        Ok(results) => serde_json::to_value(MetricsRpcResponse {
            response_type: "METRICS_SUCCESS",
            ok: true,
            query: request.query(),
            results,
            log_file: String::new(),
            schema_version: SCHEMA_VERSION,
        })?,
        Err(error) => {
            let error = error.into_cli_error();
            json!({
                "type": "METRICS_FAILURE",
                "ok": false,
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

fn print_metrics_response(request: &MetricsRequest, results: Value) -> Result<i32> {
    print_json_value(&serde_json::to_value(MetricsResponse {
        ok: true,
        query: request.query(),
        results,
        log_file: String::new(),
        schema_version: SCHEMA_VERSION,
    })?)
}

fn print_json_value(value: &Value) -> Result<i32> {
    serde_json::to_writer_pretty(io::stdout(), value)?;
    println!();
    Ok(0)
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
            MetricsCommand::Graph(args) => Self::from_graph(args),
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

    fn from_graph(args: MetricsGraphArgs) -> Result<Self> {
        let mut request = Self::from_scope("graph", args.scope, 50, Some(args.symbol), args.depth)?;
        request.graph_json = args.json;
        Ok(request)
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
        let workspace_root = config::normalize(scope.workspace_root.unwrap_or(env::current_dir()?));
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
            filter: FileFilter::new(None, None)?,
            graph_json: false,
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
            "graph" => "graph",
            other => {
                return Err(CliError::new(
                    "METRICS_UNSUPPORTED",
                    format!("Unsupported Rust metrics command: {other}"),
                ));
            }
        };
        let workspace_root = config::normalize(
            params
                .workspace_root
                .or(workspace_root_arg)
                .unwrap_or(env::current_dir()?),
        );
        let database = config::workspace_database_path(&workspace_root)?;
        Ok(Self {
            workspace_root,
            database,
            metric,
            limit: params.limit.unwrap_or(50),
            symbol: params.symbol,
            depth: params.depth.unwrap_or(3),
            filter: FileFilter::new(params.file_glob, params.folder_filter)?,
            graph_json: true,
        })
    }

    fn query(&self) -> MetricsQuery {
        MetricsQuery {
            workspace_root: self.workspace_root.display().to_string(),
            metric: self.metric.to_string(),
            limit: self.limit,
            symbol: self.symbol.clone(),
            depth: self.depth,
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
            filter: FileFilter::new(None, None)?,
            graph_json: true,
        })
    }
}

fn run_graph_tui(graph: &MetricsGraph) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let mut selected = 0usize;
    let result = loop {
        terminal.draw(|frame| {
            let chunks = Layout::default()
                .direction(Direction::Horizontal)
                .constraints([Constraint::Percentage(42), Constraint::Percentage(58)])
                .split(frame.area());
            let items: Vec<_> = graph
                .nodes
                .iter()
                .map(|node| ListItem::new(format!("{}  {}", node.node_type, node.name)))
                .collect();
            let mut state = ListState::default();
            state.select(Some(selected.min(graph.nodes.len().saturating_sub(1))));
            let list = List::new(items)
                .block(
                    Block::default()
                        .title("Metrics Graph")
                        .borders(Borders::ALL),
                )
                .highlight_style(Style::default().add_modifier(Modifier::REVERSED))
                .highlight_symbol("> ");
            frame.render_stateful_widget(list, chunks[0], &mut state);

            let details = graph
                .nodes
                .get(selected)
                .map(node_details)
                .unwrap_or_default();
            let paragraph = Paragraph::new(details)
                .block(Block::default().title("Node").borders(Borders::ALL))
                .wrap(Wrap { trim: false });
            frame.render_widget(paragraph, chunks[1]);
        })?;

        if event::poll(Duration::from_millis(250))?
            && let Event::Key(key) = event::read()?
        {
            match key.code {
                KeyCode::Char('q') | KeyCode::Esc => break Ok(0),
                KeyCode::Up | KeyCode::Char('k') => {
                    selected = selected.saturating_sub(1);
                }
                KeyCode::Down | KeyCode::Char('j') => {
                    selected = (selected + 1).min(graph.nodes.len().saturating_sub(1));
                }
                _ => {}
            }
        }
    };
    disable_raw_mode().ok();
    execute!(terminal.backend_mut(), LeaveAlternateScreen).ok();
    terminal.show_cursor().ok();
    result
}

fn node_details(node: &MetricsGraphNode) -> String {
    let mut details = vec![
        format!("id: {}", node.id),
        format!("name: {}", node.name),
        format!("type: {}", node.node_type),
    ];
    if let Some(parent_id) = &node.parent_id {
        details.push(format!("parent: {parent_id}"));
    }
    if !node.children.is_empty() {
        details.push(format!("children: {}", node.children.join(", ")));
    }
    if !node.attributes.is_empty() {
        details.push(String::new());
        details.extend(node.attributes.iter().cloned());
    }
    details.join("\n")
}
