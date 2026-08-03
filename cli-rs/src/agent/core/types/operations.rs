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
pub(crate) struct AgentRenamePosition {
    file_path: String,
    offset: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentRenamePreview {
    edits: Vec<AgentRenamePreviewEdit>,
    file_hashes: Vec<AgentRenamePreviewFileHash>,
    affected_files: Vec<String>,
    proof: AgentExactRenameProof,
    file_images: Vec<AgentExactFileImage>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    search_scope: Option<Value>,
    schema_version: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRenamePreviewEdit {
    file_path: String,
    start_offset: u32,
    end_offset: u32,
    new_text: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRenamePreviewFileHash {
    file_path: String,
    hash: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentRenameAuthority {
    target: AgentExactRenameSymbolIdentity,
    proof: AgentExactRenameProof,
    edits: Vec<AgentRenamePreviewEdit>,
    file_images: Vec<AgentExactFileImage>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentRenamePostconditionAuthority {
    proof: AgentExactRenameProof,
    edits: Vec<AgentRenamePreviewEdit>,
    images: Vec<AgentExactFileImage>,
}

include!("parts/operations/rename_authority.rs");

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    deny_unknown_fields
)]
pub(crate) enum AgentMutationPostconditionEvidence {
    Rename(AgentRenamePostconditionEvidence),
    Replacement(AgentReplacementPostconditionEvidence),
    AddFile(AgentAddFilePostconditionEvidence),
    AddDeclaration(AgentAddDeclarationPostconditionEvidence),
}

#[derive(Debug, Clone, Serialize)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    deny_unknown_fields
)]
pub(crate) enum AgentMutationPostconditionAuthority {
    Rename(AgentRenamePostconditionAuthority),
    Replacement(AgentReplacementPostconditionAuthority),
    AddFile(AgentAddFilePostconditionAuthority),
    AddDeclaration(AgentAddDeclarationPostconditionAuthority),
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

    fn rename_target_identity(&self) -> Option<AgentExactRenameSymbolIdentity> {
        AgentExactRenameSymbolIdentity::from_compiler(self)
    }
}

impl AgentRenamePreview {
    fn validate_for_target(
        &self,
        expected_target: &AgentExactRenameSymbolIdentity,
    ) -> std::result::Result<(), String> {
        if &self.proof.target != expected_target {
            return Err(
                "rename preview proof target disagreed with the selected compiler identity"
                    .to_string(),
            );
        }
        self.validate()
    }

    pub(crate) fn validate(&self) -> std::result::Result<(), String> {
        if self.schema_version != SCHEMA_VERSION {
            return Err("rename preview used an incompatible schema version".to_string());
        }
        validate_exact_rename_edits(&self.proof, &self.edits)?;
        let mut affected_files = Vec::new();
        for edit in &self.edits {
            if !affected_files.contains(&edit.file_path) {
                affected_files.push(edit.file_path.clone());
            }
        }
        if affected_files != self.affected_files {
            return Err("rename preview affected files did not match its edits".to_string());
        }
        let exact_edits = self
            .edits
            .iter()
            .map(|edit| AgentExactFileEdit {
                file_path: &edit.file_path,
                start_offset: edit.start_offset,
                end_offset: edit.end_offset,
                new_text: &edit.new_text,
            })
            .collect::<Vec<_>>();
        let image_hashes = validate_exact_file_image_set(&self.file_images, &exact_edits)?;
        let legacy_hashes = self
            .file_hashes
            .iter()
            .map(|file_hash| (file_hash.file_path.clone(), file_hash.hash.clone()))
            .collect::<BTreeMap<_, _>>();
        if legacy_hashes.len() != self.file_hashes.len()
            || legacy_hashes != image_hashes
            || legacy_hashes
                .values()
                .any(|hash| !is_lowercase_exact_file_sha256(hash))
        {
            return Err(
                "rename preview file hashes disagreed with exact preimage authority".to_string(),
            );
        }
        Ok(())
    }

    pub(crate) fn into_authority(self) -> AgentRenameAuthority {
        AgentRenameAuthority {
            target: self.proof.target.clone(),
            proof: self.proof,
            edits: self.edits,
            file_images: self.file_images,
        }
    }
}

#[cfg(test)]
#[path = "parts/operations/postcondition_tests.rs"]
mod mutation_postcondition_carrier_tests;

fn validate_exact_rename_edits(
    proof: &AgentExactRenameProof,
    edits: &[AgentRenamePreviewEdit],
) -> std::result::Result<(), String> {
    if edits.is_empty() {
        return Err("rename preview contained no edits".to_string());
    }
    proof.validate()?;
    for edit in edits {
        if !is_normalized_absolute_exact_file_path(&edit.file_path)
            || edit.start_offset > edit.end_offset
            || edit.new_text.is_empty()
        {
            return Err("rename preview contained an invalid text edit".to_string());
        }
    }
    let replacement = &edits[0].new_text;
    if edits.iter().any(|edit| &edit.new_text != replacement) {
        return Err("rename preview edits disagreed on the replacement name".to_string());
    }
    let declaration_edits = edits
        .iter()
        .enumerate()
        .filter(|(_, edit)| {
            edit.file_path == proof.target.declaration_file
                && edit.start_offset == proof.target.declaration_start_offset
        })
        .collect::<Vec<_>>();
    if declaration_edits.len() != 1 {
        return Err(
            "rename preview did not contain exactly one target declaration edit".to_string(),
        );
    }
    let declaration_index = declaration_edits[0].0;
    let reference_ranges = edits
        .iter()
        .enumerate()
        .filter(|(index, _)| *index != declaration_index)
        .map(|(_, edit)| (edit.file_path.clone(), edit.start_offset, edit.end_offset))
        .collect::<BTreeSet<_>>();
    if reference_ranges.len() + 1 != edits.len() || reference_ranges != proof.reference_ranges() {
        return Err(
            "rename preview reference edits disagreed with the exact occurrence proof".to_string(),
        );
    }
    Ok(())
}
