use crate::config::{self, KastConfig};
use crate::error::{CliError, Result};
use serde::Serialize;
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use uuid::Uuid;

const TRACE_ENVELOPE_FIELD: &str = "kastTrace";

#[derive(Debug)]
pub(super) struct TraceCorrelatedRpcRequest {
    wire_request: String,
    correlation: RpcTraceCorrelation,
    telemetry_output: Option<PathBuf>,
    started_at_epoch_nanos: u64,
    started_at: Instant,
}

impl TraceCorrelatedRpcRequest {
    pub(super) fn wire_request(&self) -> &str {
        &self.wire_request
    }

    pub(super) fn record_completion(self, outcome: RpcTraceOutcome) {
        let Some(output_file) = self.telemetry_output else {
            return;
        };
        let duration_nanos =
            u64::try_from(self.started_at.elapsed().as_nanos()).unwrap_or(u64::MAX);
        let span = CliTraceSpan::completed(
            &self.correlation,
            self.started_at_epoch_nanos,
            duration_nanos,
            outcome,
        );
        if let Err(failure) = append_cli_span(&output_file, &span) {
            eprintln!("warning: {}: {}", failure.code(), failure.message());
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(super) enum RpcTraceOutcome {
    Succeeded,
    Failed,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RpcTraceCorrelation {
    invocation_id: String,
    parent_invocation_id: String,
    request_id: String,
    trace_id: String,
    parent_span_id: String,
}

impl RpcTraceCorrelation {
    fn create(request_id: String) -> Self {
        Self {
            invocation_id: Uuid::new_v4().hyphenated().to_string(),
            parent_invocation_id: parent_invocation_id(),
            request_id,
            trace_id: Uuid::new_v4().simple().to_string(),
            parent_span_id: random_span_id(),
        }
    }

    fn attributes(&self) -> BTreeMap<&'static str, String> {
        BTreeMap::from([
            ("kast.invocation.id", self.invocation_id.clone()),
            (
                "kast.invocation.parentId",
                self.parent_invocation_id.clone(),
            ),
            ("kast.request.id", self.request_id.clone()),
            ("kast.trace.role", "CLI".to_string()),
        ])
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CliTraceSpan {
    name: &'static str,
    trace_id: String,
    span_id: String,
    kind: &'static str,
    status: &'static str,
    start_epoch_nanos: u64,
    end_epoch_nanos: u64,
    duration_nanos: u64,
    attributes: BTreeMap<&'static str, String>,
    events: Vec<Value>,
}

impl CliTraceSpan {
    fn completed(
        correlation: &RpcTraceCorrelation,
        start_epoch_nanos: u64,
        duration_nanos: u64,
        outcome: RpcTraceOutcome,
    ) -> Self {
        Self {
            name: "kast.cli.invocation",
            trace_id: correlation.trace_id.clone(),
            span_id: correlation.parent_span_id.clone(),
            kind: "INTERNAL",
            status: match outcome {
                RpcTraceOutcome::Succeeded => "OK",
                RpcTraceOutcome::Failed => "ERROR",
            },
            start_epoch_nanos,
            end_epoch_nanos: start_epoch_nanos.saturating_add(duration_nanos),
            duration_nanos,
            attributes: correlation.attributes(),
            events: Vec::new(),
        }
    }
}

#[derive(Debug)]
enum TraceEmissionFailure {
    CreateDirectory(String),
    Serialize(String),
    Append(String),
}

impl TraceEmissionFailure {
    fn code(&self) -> &'static str {
        match self {
            Self::CreateDirectory(_) => "CLI_TRACE_DIRECTORY_UNAVAILABLE",
            Self::Serialize(_) => "CLI_TRACE_SERIALIZATION_FAILED",
            Self::Append(_) => "CLI_TRACE_APPEND_FAILED",
        }
    }

    fn message(&self) -> &str {
        match self {
            Self::CreateDirectory(message) | Self::Serialize(message) | Self::Append(message) => {
                message
            }
        }
    }
}

/// Proof transition: `String -> TraceCorrelatedRpcRequest`.
///
/// Establishes a unique invocation, parent-session, JSON-RPC request, trace, and
/// CLI-span identity and inserts only those bounded values into the internal
/// `kastTrace` transport envelope. Raw request extraction is permitted only at
/// the Unix-socket serialization boundary. Expected malformed or conflicting
/// input is represented by the crate's finite `CliError` result.
pub(super) fn trace_correlated_rpc_request(
    raw_request: String,
    workspace_root: &Path,
    config: &KastConfig,
) -> Result<TraceCorrelatedRpcRequest> {
    let started_at_epoch_nanos = epoch_nanos()?;
    let started_at = Instant::now();
    let mut request = parse_request_object(&raw_request)?;
    if request.contains_key(TRACE_ENVELOPE_FIELD) {
        return Err(CliError::new(
            "RPC_TRACE_CONFLICT",
            "The JSON-RPC request already contains the internal trace envelope.",
        ));
    }
    let request_id = parse_request_id(request.get("id"))?;
    let correlation = RpcTraceCorrelation::create(request_id);
    request.insert(
        TRACE_ENVELOPE_FIELD.to_string(),
        serde_json::to_value(&correlation)?,
    );
    Ok(TraceCorrelatedRpcRequest {
        wire_request: serde_json::to_string(&request)?,
        correlation,
        telemetry_output: telemetry_output(config, workspace_root)?,
        started_at_epoch_nanos,
        started_at,
    })
}

fn parse_request_object(raw_request: &str) -> Result<Map<String, Value>> {
    serde_json::from_str::<Value>(raw_request)?
        .as_object()
        .cloned()
        .ok_or_else(|| {
            CliError::new(
                "RPC_REQUEST_INVALID",
                "The JSON-RPC request must be an object before trace correlation.",
            )
        })
}

fn parse_request_id(value: Option<&Value>) -> Result<String> {
    let candidate = match value {
        Some(Value::Number(number)) => number.to_string(),
        Some(Value::String(text)) => text.clone(),
        _ => {
            return Err(CliError::new(
                "RPC_REQUEST_ID_INVALID",
                "The JSON-RPC request id must be a bounded number or identifier string.",
            ));
        }
    };
    if candidate.is_empty()
        || candidate.len() > 128
        || !candidate
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b':' | b'-'))
    {
        return Err(CliError::new(
            "RPC_REQUEST_ID_INVALID",
            "The JSON-RPC request id must be a bounded number or identifier string.",
        ));
    }
    Ok(candidate)
}

fn parent_invocation_id() -> String {
    let parent_material = ["KAST_AGENT_SESSION_ID", "CODEX_THREAD_ID"]
        .into_iter()
        .find_map(|name| std::env::var(name).ok().filter(|value| !value.is_empty()))
        .unwrap_or_else(parent_process_material);
    crate::manifest::sha256_bytes(parent_material.as_bytes())
}

#[cfg(unix)]
fn parent_process_material() -> String {
    format!("parent-process:{}", unsafe { libc::getppid() })
}

#[cfg(not(unix))]
fn parent_process_material() -> String {
    format!("parent-process:{}", std::process::id())
}

fn random_span_id() -> String {
    let bytes = Uuid::new_v4().into_bytes();
    format!(
        "{:016x}",
        u64::from_be_bytes(bytes[..8].try_into().expect("UUID prefix is eight bytes"))
    )
}

fn epoch_nanos() -> Result<u64> {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| CliError::new("CLI_TRACE_CLOCK_INVALID", error.to_string()))?
        .as_nanos();
    u64::try_from(nanos).map_err(|_| {
        CliError::new(
            "CLI_TRACE_CLOCK_INVALID",
            "The current epoch timestamp exceeds the trace representation.",
        )
    })
}

fn telemetry_output(config: &KastConfig, workspace_root: &Path) -> Result<Option<PathBuf>> {
    if !config.telemetry.enabled {
        return Ok(None);
    }
    let configured = config
        .telemetry
        .output_file
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .map(PathBuf::from);
    Ok(Some(match configured {
        Some(path) if path.is_absolute() => path,
        Some(path) => workspace_root.join(path),
        None => {
            config::workspace_data_directory(workspace_root)?.join("telemetry/idea-spans.jsonl")
        }
    }))
}

fn append_cli_span(
    output_file: &Path,
    span: &CliTraceSpan,
) -> std::result::Result<(), TraceEmissionFailure> {
    if let Some(parent) = output_file.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| TraceEmissionFailure::CreateDirectory(error.to_string()))?;
    }
    let mut payload = serde_json::to_vec(span)
        .map_err(|error| TraceEmissionFailure::Serialize(error.to_string()))?;
    payload.push(b'\n');
    OpenOptions::new()
        .create(true)
        .append(true)
        .open(output_file)
        .and_then(|mut output| output.write_all(&payload))
        .map_err(|error| TraceEmissionFailure::Append(error.to_string()))
}
