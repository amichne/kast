#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EmptyCheckResult {
    changed_file_count: usize,
    diagnostic_count: usize,
    message: &'static str,
}

pub(crate) fn run_diagnostic(
    args: KastDiagnosticArgs,
    output_format: OutputFormat,
) -> Result<i32> {
    let KastDiagnosticCommand::Check { files } = args.command;
    let workspace_root = config::resolve_workspace_root(None)?;
    let file_paths = if files.is_empty() {
        match changed_kotlin_files(&workspace_root)? {
            Ok(file_paths) => file_paths,
            Err(envelope) => {
                return match backend_outcome(
                    agent::public_protocol::OperationId::DiagnosticCheck,
                    envelope,
                ) {
                    BackendOutcome::Complete(_) => Err(CliError::new(
                        "KAST_INVALID_AGENT_RESULT",
                        "Changed-file discovery returned an unexpected success value.",
                    )),
                    BackendOutcome::Rejected(envelope) => print_protocol(*envelope, output_format),
                };
            }
        }
    } else {
        files
            .into_iter()
            .map(|path| path.display().to_string())
            .collect()
    };
    if file_paths.is_empty() {
        return print_public_value(
            agent::public_protocol::OperationId::DiagnosticCheck,
            agent::public_protocol::OperationStatus::Complete,
            &EmptyCheckResult {
                changed_file_count: 0,
                diagnostic_count: 0,
                message: "No changed Kotlin files were found.",
            },
            output_format,
        );
    }
    let current = projected_value(check_diagnostics_command(
        &workspace_root,
        &file_paths,
        CheckDiagnosticsRead::CurrentPublication,
    ))?;
    match CurrentCheckAttempt::derive(current) {
        CurrentCheckAttempt::Covered(envelope) => {
            print_diagnostics(&workspace_root, envelope, output_format)
        }
        CurrentCheckAttempt::RefreshRequired(_) => print_diagnostics(
            &workspace_root,
            projected_value(check_diagnostics_command(
                &workspace_root,
                &file_paths,
                CheckDiagnosticsRead::ReconciledPublication,
            ))?,
            output_format,
        ),
        CurrentCheckAttempt::Rejected(envelope) => {
            print_diagnostics(&workspace_root, envelope, output_format)
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CheckDiagnosticsRead {
    CurrentPublication,
    ReconciledPublication,
}

/// Boundary transition:
/// `(Path, [String], CheckDiagnosticsRead) -> AgentCommand::Diagnostics`.
///
/// Converts the typed public-check read policy to the legacy `skip_refresh`
/// CLI flag only at command construction. The decision itself never travels
/// through a Boolean inside the check workflow.
fn check_diagnostics_command(
    workspace_root: &Path,
    file_paths: &[String],
    read: CheckDiagnosticsRead,
) -> AgentCommand {
    let skip_refresh = match read {
        CheckDiagnosticsRead::CurrentPublication => true,
        CheckDiagnosticsRead::ReconciledPublication => false,
    };
    AgentCommand::Diagnostics(AgentDiagnosticsArgs {
        runtime: agent_runtime(workspace_root.to_path_buf()),
        file_paths: file_paths.to_vec(),
        skip_refresh,
        limit: 500,
        page_token: None,
        view: AgentDiagnosticsViewArgs::default(),
    })
}

#[derive(Debug, PartialEq)]
enum CurrentCheckAttempt {
    Covered(Value),
    RefreshRequired(WorkspaceStaleness),
    Rejected(Value),
}

impl CurrentCheckAttempt {
    /// Proof transition: `JSON AgentEnvelope -> CurrentCheckAttempt`.
    ///
    /// A successful projected diagnostic result already carries exact hashes
    /// for every analyzed file, but covers the current publication only when
    /// its semantic outcome is complete. Typed pending-index evidence permits
    /// a refresh because the backend has not proven VFS/PSI freshness. Only
    /// closed workspace movement or pending-publication evidence authorizes a
    /// retry; malformed and unrelated failures remain rejected intact.
    fn derive(envelope: Value) -> Self {
        if envelope.get("ok").and_then(Value::as_bool) == Some(true) {
            return match CurrentDiagnosticsCoverage::derive(&envelope) {
                CurrentDiagnosticsCoverage::Complete => Self::Covered(envelope),
                CurrentDiagnosticsCoverage::PublicationPending => Self::RefreshRequired(
                    WorkspaceStaleness::DiagnosticPublicationPending,
                ),
                CurrentDiagnosticsCoverage::Unproven => Self::Rejected(envelope),
            };
        }
        match WorkspaceStalenessEvidence::derive(&envelope) {
            WorkspaceStalenessEvidence::Proven(staleness) => Self::RefreshRequired(staleness),
            WorkspaceStalenessEvidence::Absent => Self::Rejected(envelope),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CurrentDiagnosticsCoverage {
    Complete,
    PublicationPending,
    Unproven,
}

impl CurrentDiagnosticsCoverage {
    /// Proof transition: `JSON AgentDiagnosticsResult -> CurrentDiagnosticsCoverage`.
    ///
    /// Refines a successful transport envelope into complete current evidence,
    /// typed pending-index evidence, or an unproven result. Primitive protocol
    /// fields remain confined to this projection boundary.
    fn derive(envelope: &Value) -> Self {
        let Some(result) = envelope.get("result") else {
            return Self::Unproven;
        };
        match result.get("semanticOutcome").and_then(Value::as_str) {
            Some("COMPLETE") => Self::Complete,
            Some("INCOMPLETE")
                if result
                    .get("fileStatuses")
                    .and_then(Value::as_array)
                    .is_some_and(|statuses| {
                        statuses.iter().any(|status| {
                            status.get("state").and_then(Value::as_str) == Some("PENDING_INDEX")
                        })
                    }) =>
            {
                Self::PublicationPending
            }
            _ => Self::Unproven,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WorkspaceStaleness {
    SemanticAdmissionMoved,
    PublishedGenerationMoved,
    RuntimeIndexing,
    DiagnosticPublicationPending,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WorkspaceStalenessEvidence {
    Proven(WorkspaceStaleness),
    Absent,
}

impl WorkspaceStalenessEvidence {
    /// Proof transition: `JSON AgentEnvelope -> WorkspaceStalenessEvidence`.
    ///
    /// Refines only explicit runtime, publication, or semantic-admission
    /// movement into retry authority. A generic conflict without typed
    /// workspace-state details is deliberately not refreshable.
    fn derive(envelope: &Value) -> Self {
        let error = envelope
            .get("error")
            .or_else(|| envelope.pointer("/result/steps/0/error"));
        let Some(error) = error else {
            return Self::Absent;
        };
        match error.get("code").and_then(Value::as_str) {
            Some("RUNTIME_NOT_READY") => Self::Proven(WorkspaceStaleness::RuntimeIndexing),
            Some(
                "PUBLISHED_WORKSPACE_MOVED"
                | "PUBLISHED_WORKSPACE_MISMATCH"
                | "PUBLISHED_WORKSPACE_UNAVAILABLE",
            ) => Self::Proven(WorkspaceStaleness::PublishedGenerationMoved),
            Some("CONFLICT")
                if error
                    .pointer("/details/rpcError/data/details/workspaceState")
                    .is_some() =>
            {
                Self::Proven(WorkspaceStaleness::SemanticAdmissionMoved)
            }
            _ => Self::Absent,
        }
    }
}
