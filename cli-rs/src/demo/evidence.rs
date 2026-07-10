#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoSelectedStory {
    fq_name: String,
    indexed_reference_count: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    compiler_identity: Option<DemoCompilerIdentity>,
    #[serde(skip_serializing_if = "Option::is_none")]
    compiler_reference_count: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    diagnostics: Option<DemoDiagnosticsSummary>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoCompilerIdentity {
    fq_name: String,
    kind: String,
    file_path: String,
    line: u32,
    preview: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DemoDiagnosticsSummary {
    clean: bool,
    error_count: usize,
    warning_count: usize,
    info_count: usize,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type")]
enum DemoResolveResponse {
    #[serde(rename = "RESOLVE_SUCCESS")]
    Success { symbol: DemoProtocolSymbol },
    #[serde(rename = "RESOLVE_FAILURE")]
    Failure { message: String },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type")]
enum DemoReferencesResponse {
    #[serde(rename = "REFERENCES_SUCCESS")]
    Success {
        references: Vec<DemoProtocolLocation>,
    },
    #[serde(rename = "REFERENCES_FAILURE")]
    Failure { message: String },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DemoProtocolSymbol {
    fq_name: String,
    kind: String,
    location: DemoProtocolLocation,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DemoProtocolLocation {
    file_path: String,
    start_line: u32,
    preview: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DemoDiagnosticsResult {
    diagnostics: Vec<DemoProtocolDiagnostic>,
}

#[derive(Debug, Deserialize)]
struct DemoProtocolDiagnostic {
    severity: String,
}

fn selected_demo_story(
    candidate: &DemoCandidate,
    connection: Option<&DemoBackendConnection>,
    warnings: &mut Vec<String>,
) -> DemoSelectedStory {
    let compiler = connection.and_then(|connection| {
        compiler_story_evidence(connection, candidate, || false)
            .inspect_err(|error| warnings.push(error.message.clone()))
            .ok()
    });
    let (compiler_identity, compiler_reference_count, diagnostics) = compiler
        .map(|evidence| {
            (
                Some(evidence.identity),
                Some(evidence.reference_count),
                Some(evidence.diagnostics),
            )
        })
        .unwrap_or((None, None, None));
    DemoSelectedStory {
        fq_name: candidate.fq_name.clone(),
        indexed_reference_count: candidate.evidence_count,
        compiler_identity,
        compiler_reference_count,
        diagnostics,
    }
}

struct DemoEvidenceWorker {
    request_sender: mpsc::Sender<(u64, DemoCandidate)>,
    result_receiver: mpsc::Receiver<std::result::Result<DemoSelectedStory, String>>,
    generation: Arc<AtomicU64>,
}

impl DemoEvidenceWorker {
    fn spawn(connection: DemoBackendConnection) -> Self {
        let (request_sender, request_receiver) = mpsc::channel::<(u64, DemoCandidate)>();
        let (result_sender, result_receiver) = mpsc::channel();
        let generation = Arc::new(AtomicU64::new(0));
        let worker_generation = Arc::clone(&generation);
        std::thread::spawn(move || {
            while let Ok((request_generation, candidate)) = request_receiver.recv() {
                let result = load_selected_demo_story(&connection, &candidate, || {
                    worker_generation.load(Ordering::Relaxed) != request_generation
                })
                .map_err(|error| error.message);
                if result_sender.send(result).is_err() {
                    break;
                }
            }
        });
        Self {
            request_sender,
            result_receiver,
            generation,
        }
    }

    fn request(&self, candidate: DemoCandidate) -> std::result::Result<(), String> {
        let generation = self.generation.fetch_add(1, Ordering::Relaxed) + 1;
        self.request_sender
            .send((generation, candidate))
            .map_err(|_| "The compiler evidence worker stopped unexpectedly.".to_string())
    }

    fn try_receive(&self) -> Option<std::result::Result<DemoSelectedStory, String>> {
        self.result_receiver.try_recv().ok()
    }
}

impl Drop for DemoEvidenceWorker {
    fn drop(&mut self) {
        self.generation.fetch_add(1, Ordering::Relaxed);
    }
}

fn load_selected_demo_story(
    connection: &DemoBackendConnection,
    candidate: &DemoCandidate,
    cancelled: impl Fn() -> bool,
) -> Result<DemoSelectedStory> {
    let compiler = compiler_story_evidence(connection, candidate, cancelled)?;
    Ok(DemoSelectedStory {
        fq_name: candidate.fq_name.clone(),
        indexed_reference_count: candidate.evidence_count,
        compiler_identity: Some(compiler.identity),
        compiler_reference_count: Some(compiler.reference_count),
        diagnostics: Some(compiler.diagnostics),
    })
}

struct DemoCompilerEvidence {
    identity: DemoCompilerIdentity,
    reference_count: usize,
    diagnostics: DemoDiagnosticsSummary,
}

fn compiler_story_evidence(
    connection: &DemoBackendConnection,
    candidate: &DemoCandidate,
    cancelled: impl Fn() -> bool,
) -> Result<DemoCompilerEvidence> {
    ensure_demo_evidence_not_cancelled(&cancelled)?;
    let timeout = Duration::from_secs(10);
    let resolve: DemoResolveResponse = rpc::request_wait_for_close(
        &connection.socket_path,
        "symbol/resolve",
        serde_json::json!({
            "symbol": simple_symbol_name(&candidate.fq_name),
            "fileHint": candidate.file,
            "includeDeclarationScope": true,
            "surroundingLines": 3,
        }),
        timeout,
    )?;
    let symbol = match resolve {
        DemoResolveResponse::Success { symbol } => symbol,
        DemoResolveResponse::Failure { message } => {
            return Err(CliError::new("DEMO_RESOLVE_FAILED", message));
        }
    };
    ensure_demo_evidence_not_cancelled(&cancelled)?;
    let references: DemoReferencesResponse = rpc::request_wait_for_close(
        &connection.socket_path,
        "symbol/references",
        serde_json::json!({
            "symbol": simple_symbol_name(&candidate.fq_name),
            "fileHint": candidate.file,
            "includeDeclaration": true,
        }),
        timeout,
    )?;
    let reference_count = match references {
        DemoReferencesResponse::Success { references } => references.len(),
        DemoReferencesResponse::Failure { message } => {
            return Err(CliError::new("DEMO_REFERENCES_FAILED", message));
        }
    };
    ensure_demo_evidence_not_cancelled(&cancelled)?;
    let diagnostics: DemoDiagnosticsResult = rpc::request_wait_for_close(
        &connection.socket_path,
        "raw/diagnostics",
        serde_json::json!({ "filePaths": [&symbol.location.file_path] }),
        timeout,
    )?;
    let error_count = diagnostics
        .diagnostics
        .iter()
        .filter(|diagnostic| diagnostic.severity == "ERROR")
        .count();
    let warning_count = diagnostics
        .diagnostics
        .iter()
        .filter(|diagnostic| diagnostic.severity == "WARNING")
        .count();
    let info_count = diagnostics
        .diagnostics
        .iter()
        .filter(|diagnostic| diagnostic.severity == "INFO")
        .count();
    Ok(DemoCompilerEvidence {
        identity: DemoCompilerIdentity {
            fq_name: symbol.fq_name,
            kind: symbol.kind,
            file_path: symbol.location.file_path,
            line: symbol.location.start_line,
            preview: symbol.location.preview,
        },
        reference_count,
        diagnostics: DemoDiagnosticsSummary {
            clean: error_count == 0,
            error_count,
            warning_count,
            info_count,
        },
    })
}

fn ensure_demo_evidence_not_cancelled(cancelled: &impl Fn() -> bool) -> Result<()> {
    if cancelled() {
        Err(CliError::new(
            "DEMO_EVIDENCE_CANCELLED",
            "Compiler evidence loading was cancelled.",
        ))
    } else {
        Ok(())
    }
}
