#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnostic {
    location: AgentDiagnosticLocation,
    severity: AgentDiagnosticSeverity,
    message: String,
    code: Option<String>,
}

impl AgentDiagnostic {
    fn is_valid(&self) -> bool {
        self.location.is_valid()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentDiagnosticLocation {
    file_path: String,
    start_offset: usize,
    end_offset: usize,
    start_line: usize,
    start_column: usize,
    preview: String,
}

impl AgentDiagnosticLocation {
    fn is_valid(&self) -> bool {
        !self.file_path.trim().is_empty() && self.start_offset <= self.end_offset
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentDiagnosticSeverity {
    Error,
    Warning,
    Info,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSemanticAnalysisOutcome {
    Complete,
    Incomplete,
}

struct AgentRequest {
    method: String,
    request: Value,
    runtime: AgentRuntimeArgs,
    full_response: bool,
    operation: AgentOperation,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AgentOperation {
    ReadOnly,
    MutationPreview,
    AppliedMutation,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolLookupResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    ok: bool,
    mode: AgentSymbolMode,
    request: Value,
    outcome: AgentSymbolLookupOutcome,
    schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum AgentSymbolLookupOutcome {
    Resolved {
        source: AgentSymbolLookupSource,
        symbol: Value,
        #[serde(skip_serializing_if = "Option::is_none")]
        selector_handle: Option<AgentSelectorHandle>,
        resolution: Value,
        relations: Vec<AgentSymbolRelation>,
        #[serde(skip_serializing_if = "Option::is_none")]
        compiler_fallback: Option<AgentCompilerFallback>,
    },
    IdentityAnchorUnavailable {
        source: AgentSymbolLookupSource,
        query: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        compiler_fallback: Option<AgentCompilerFallback>,
    },
    NotFound {
        source: AgentSymbolLookupSource,
        query: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        compiler_fallback: Option<AgentCompilerFallback>,
    },
    Ambiguous {
        source: AgentSymbolLookupSource,
        query: String,
        candidates: Vec<Value>,
        #[serde(skip_serializing_if = "Option::is_none")]
        compiler_fallback: Option<AgentCompilerFallback>,
    },
    Discovered {
        source: AgentSymbolLookupSource,
        query: String,
        candidates: Vec<Value>,
    },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "kebab-case")]
enum AgentSymbolLookupSource {
    Compiler,
    IndexedExact,
    Fuzzy,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSymbolRelation {
    relation: &'static str,
    result: Value,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCompilerFallback {
    code: String,
    message: String,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type")]
enum AgentCompilerResolveResponse {
    #[serde(rename = "RESOLVE_SUCCESS")]
    Resolved {
        symbol: AgentCompilerSymbolIdentity,
        #[serde(default, rename = "selectorHandle")]
        selector_handle: Option<AgentSelectorHandle>,
    },
    #[serde(rename = "RESOLVE_NOT_FOUND")]
    NotFound,
    #[serde(rename = "RESOLVE_AMBIGUOUS")]
    Ambiguous { candidates: Vec<Value> },
    #[serde(rename = "RESOLVE_FAILURE")]
    OperationalFailure,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentCompilerSymbolIdentity {
    fq_name: String,
    #[serde(flatten)]
    fields: BTreeMap<String, Value>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentRenamePosition {
    file_path: String,
    offset: u32,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRenamePreview {
    edits: Vec<AgentRenamePreviewEdit>,
    file_hashes: Vec<AgentRenamePreviewFileHash>,
    affected_files: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    search_scope: Option<Value>,
    schema_version: u32,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRenamePreviewEdit {
    file_path: String,
    start_offset: u32,
    end_offset: u32,
    new_text: String,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRenamePreviewFileHash {
    file_path: String,
    hash: String,
}

impl AgentCompilerSymbolIdentity {
    fn has_complete_anchor(&self) -> bool {
        let location = self.fields.get("location").and_then(Value::as_object);
        !self.fq_name.trim().is_empty()
            && self
                .fields
                .get("kind")
                .and_then(Value::as_str)
                .is_some_and(|kind| !kind.trim().is_empty())
            && location
                .and_then(|location| location.get("filePath"))
                .and_then(Value::as_str)
                .is_some_and(|path| !path.trim().is_empty())
            && location
                .and_then(|location| location.get("startOffset"))
                .and_then(Value::as_u64)
                .is_some()
    }

    fn rename_position(&self) -> Option<AgentRenamePosition> {
        let location = self.fields.get("location")?.as_object()?;
        let file_path = location.get("filePath")?.as_str()?.trim();
        let offset = location.get("startOffset")?.as_u64()?;
        if file_path.is_empty() || offset > i32::MAX as u64 {
            return None;
        }
        Some(AgentRenamePosition {
            file_path: file_path.to_string(),
            offset: offset as u32,
        })
    }
}

impl AgentRenamePreview {
    fn validate(&self) -> std::result::Result<(), String> {
        if self.edits.is_empty() {
            return Err("rename preview contained no edits".to_string());
        }
        let mut affected_files = Vec::new();
        for edit in &self.edits {
            if edit.file_path.trim().is_empty()
                || edit.start_offset > edit.end_offset
                || edit.new_text.is_empty()
            {
                return Err("rename preview contained an invalid text edit".to_string());
            }
            if !affected_files.contains(&edit.file_path) {
                affected_files.push(edit.file_path.clone());
            }
        }
        if affected_files != self.affected_files {
            return Err("rename preview affected files did not match its edits".to_string());
        }
        let hashed_files = self
            .file_hashes
            .iter()
            .map(|file_hash| file_hash.file_path.as_str())
            .collect::<BTreeSet<_>>();
        if self.file_hashes.len() != self.affected_files.len()
            || hashed_files.len() != self.affected_files.len()
            || self.file_hashes.iter().any(|file_hash| {
                !self.affected_files.contains(&file_hash.file_path)
                    || file_hash.hash.len() != 64
                    || !file_hash.hash.bytes().all(|byte| byte.is_ascii_hexdigit())
            })
        {
            return Err("rename preview file hashes did not cover every affected file".to_string());
        }
        Ok(())
    }
}
