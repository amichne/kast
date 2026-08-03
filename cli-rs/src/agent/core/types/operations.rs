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

impl AgentRenameAuthority {
    pub(crate) fn from_projected_result(result: &Value) -> std::result::Result<Self, String> {
        let preview = result
            .pointer("/plan/preview")
            .cloned()
            .ok_or_else(|| "projected rename plan omitted its exact preview".to_string())?;
        let preview: AgentRenamePreview = serde_json::from_value(preview)
            .map_err(|error| format!("projected rename preview was malformed: {error}"))?;
        preview.validate()?;
        Ok(preview.into_authority())
    }

    pub(crate) fn validate(&self) -> std::result::Result<(), String> {
        if self.target != self.proof.target {
            return Err("rename authority target disagreed with its proof".to_string());
        }
        validate_exact_rename_edits(&self.proof, &self.edits)?;
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
        validate_exact_file_image_set(&self.file_images, &exact_edits)?;
        Ok(())
    }

    pub(crate) fn new_name(&self) -> &str {
        &self.edits[0].new_text
    }

    pub(crate) fn target_position(&self) -> AgentRenamePosition {
        self.target.position()
    }

    pub(crate) fn file_images(&self) -> &[AgentExactFileImage] {
        &self.file_images
    }

    pub(crate) fn postcondition_authority(&self) -> AgentRenamePostconditionAuthority {
        AgentRenamePostconditionAuthority {
            proof: self.proof.clone(),
            edits: self.edits.clone(),
            images: self.file_images.clone(),
        }
    }

    pub(crate) fn minimum_postcondition_generation(&self) -> u64 {
        self.proof.required_generation.0
    }

    pub(crate) fn validate_postcondition_evidence(
        &self,
        result: &AgentRenamePostconditionEvidence,
    ) -> std::result::Result<(), String> {
        self.validate()?;
        let adjusted = adjusted_rename_edit_ranges(&self.edits)?;
        let declaration_edit = self
            .edits
            .iter()
            .find(|edit| {
                edit.file_path == self.proof.target.declaration_file
                    && edit.start_offset == self.proof.target.declaration_start_offset
            })
            .ok_or_else(|| {
                "rename postcondition authority lost its declaration edit".to_string()
            })?;
        let declaration_range = adjusted
            .get(&(
                declaration_edit.file_path.clone(),
                declaration_edit.start_offset,
                declaration_edit.end_offset,
            ))
            .ok_or_else(|| "rename declaration range was not adjusted".to_string())?;
        let mut expected_target = self.proof.target.clone();
        expected_target.fq_name = renamed_postcondition_fq_name(
            &expected_target.fq_name,
            &declaration_edit.new_text,
        );
        expected_target.declaration_start_offset = declaration_range.0;
        if result.resulting_target != expected_target
            || result.evidence != self.proof.evidence
            || result.evidence.validate()? != result.occurrences.len()
        {
            return Err(
                "rename postcondition changed its resulting identity or complete cardinality"
                    .to_string(),
            );
        }
        let expected_ranges = self
            .proof
            .occurrences
            .iter()
            .map(|occurrence| occurrence.reference.location.source_range_key())
            .map(|key| {
                adjusted
                    .get(&key)
                    .map(|range| (key.0, range.0, range.1))
                    .ok_or_else(|| {
                        "rename postcondition authority dropped one occurrence edit".to_string()
                    })
            })
            .collect::<std::result::Result<BTreeSet<_>, _>>()?;
        let mut observed_ranges = BTreeSet::new();
        for occurrence in &result.occurrences {
            if occurrence.resolved_target != result.resulting_target
                || !occurrence.reference.location.is_valid()
                || !occurrence.reference.containing_symbol.is_valid()
                || occurrence.provenance != AgentExactRenameOccurrenceProvenance::Compiler
                || !observed_ranges.insert(occurrence.reference.location.source_range_key())
            {
                return Err(
                    "rename postcondition occurrence evidence was malformed or rebound"
                        .to_string(),
                );
            }
        }
        if observed_ranges != expected_ranges {
            return Err(
                "rename postcondition occurrence ranges changed from the exact authority"
                    .to_string(),
            );
        }
        Ok(())
    }
}

type RenameSourceRangeKey = (String, u32, u32);
type RenameAdjustedRange = (u32, u32);
type AdjustedRenameRanges = BTreeMap<RenameSourceRangeKey, RenameAdjustedRange>;

fn adjusted_rename_edit_ranges(
    edits: &[AgentRenamePreviewEdit],
) -> std::result::Result<AdjustedRenameRanges, String> {
    let mut by_file = BTreeMap::<&str, Vec<&AgentRenamePreviewEdit>>::new();
    for edit in edits {
        by_file.entry(&edit.file_path).or_default().push(edit);
    }
    let mut adjusted = BTreeMap::new();
    for (_, mut file_edits) in by_file {
        file_edits.sort_by_key(|edit| edit.start_offset);
        let mut delta = 0i64;
        for edit in file_edits {
            let start = i64::from(edit.start_offset) + delta;
            let replacement_length = i64::try_from(edit.new_text.encode_utf16().count())
                .map_err(|_| "rename replacement length overflowed".to_string())?;
            let end = start + replacement_length;
            if start < 0 || end < start || end > i64::from(i32::MAX) {
                return Err("rename adjusted range overflowed".to_string());
            }
            adjusted.insert(
                (edit.file_path.clone(), edit.start_offset, edit.end_offset),
                (start as u32, end as u32),
            );
            delta += replacement_length
                - (i64::from(edit.end_offset) - i64::from(edit.start_offset));
        }
    }
    Ok(adjusted)
}

fn renamed_postcondition_fq_name(old: &str, new_name: &str) -> String {
    old.rsplit_once('.')
        .map_or_else(|| new_name.to_string(), |(owner, _)| format!("{owner}.{new_name}"))
}

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
mod mutation_postcondition_carrier_tests {
    use super::*;

    fn identity() -> Value {
        json!({
            "fqName": "sample.target",
            "kind": "FUNCTION",
            "declarationFile": "/workspace/src/Target.kt",
            "declarationStartOffset": 4
        })
    }

    fn signature() -> Value {
        json!({
            "type": "function",
            "name": "target",
            "receiverType": null,
            "contextReceiverTypes": [],
            "typeParameters": [],
            "valueParameters": [],
            "returnType": "kotlin.Unit",
            "visibility": "PUBLIC",
            "modality": "FINAL",
            "hasStableParameterNames": true,
            "suspend": false,
            "operator": false,
            "inline": false,
            "override": false,
            "infix": false,
            "static": false,
            "tailrec": false,
            "external": false,
            "expect": false,
            "actual": false
        })
    }

    fn replacement_dimensions() -> Value {
        json!([
            "EXACT_TARGET_IDENTITY",
            "SUPPORTED_TARGET_KIND",
            "SINGLE_SUPPORTED_PROPOSED_DECLARATION",
            "COMPILER_SIGNATURE_EQUAL",
            "PROPOSED_PSI_TRAVERSAL_EXHAUSTIVE",
            "EVERY_REFERENCE_COMPILER_RESOLVED",
            "EVERY_REFERENCE_TARGET_MATCHED",
            "EVERY_CALL_EXACT",
            "NO_UNSUPPORTED_REFERENCE_KIND",
            "EXACT_OUTBOUND_CARDINALITY",
            "SOURCE_CONTEXT_HASH_BOUND",
            "SEMANTIC_GENERATION_UNCHANGED"
        ])
    }

    fn owner() -> Value {
        json!({
            "sourceRoot": "/workspace/src",
            "ideaModuleName": "root.main",
            "gradleBuildRoot": "/workspace",
            "gradleProjectPath": ":",
            "sourceSetName": "main"
        })
    }

    fn declaration() -> Value {
        json!({
            "packageIdentity": {"type": "ROOT"},
            "name": "Added",
            "kind": "CLASS",
            "relativeRange": {"startOffset": 0, "endOffset": 5},
            "collisionSignature": "1".repeat(64)
        })
    }

    fn valid_variants() -> Vec<Value> {
        vec![
            json!({
                "type": "RENAME",
                "resultingTarget": identity(),
                "evidence": {
                    "type": "COMPLETE",
                    "cardinality": {"type": "EXACT", "totalCount": 0},
                    "coverage": {
                        "type": "COMPLETE",
                        "identity": "COMPLETE",
                        "projectScope": "COMPLETE",
                        "sourceSetScope": "COMPLETE",
                        "indexFreshness": "COMPLETE",
                        "backend": "COMPLETE",
                        "requestedFamily": "COMPLETE",
                        "limitations": []
                    }
                },
                "occurrences": []
            }),
            json!({
                "type": "REPLACEMENT",
                "resultingTarget": identity(),
                "sourceRange": {
                    "filePath": "/workspace/src/Target.kt",
                    "startOffset": 0,
                    "endOffset": 8,
                    "startLine": 1,
                    "startColumn": 1,
                    "preview": "fun x()"
                },
                "signature": signature(),
                "outboundEvidence": {
                    "type": "complete",
                    "cardinality": {"type": "EXACT", "totalCount": 0},
                    "dimensions": replacement_dimensions()
                },
                "outboundReferences": []
            }),
            json!({
                "type": "ADD_FILE",
                "owner": owner(),
                "packageIdentity": {"type": "ROOT"},
                "declarations": [declaration()],
                "outboundEvidence": {"cardinality": 0, "occurrences": []}
            }),
            json!({
                "type": "ADD_DECLARATION",
                "owner": owner(),
                "packageIdentity": {"type": "ROOT"},
                "declaration": declaration(),
                "outboundEvidence": {"cardinality": 0, "occurrences": []}
            }),
        ]
    }

    #[test]
    fn all_postcondition_variants_reject_malformed_nested_evidence() {
        for mut value in valid_variants() {
            match value["type"].as_str().expect("variant") {
                "RENAME" | "REPLACEMENT" => {
                    value["resultingTarget"]["unexpected"] = json!(true)
                }
                "ADD_FILE" | "ADD_DECLARATION" => {
                    value["owner"]["unexpected"] = json!(true)
                }
                _ => unreachable!(),
            }
            assert!(
                serde_json::from_value::<AgentMutationPostconditionEvidence>(value).is_err()
            );
        }
    }

    #[test]
    fn all_postcondition_variants_retain_semantic_substitutions_in_typed_equality() {
        for value in valid_variants() {
            let expected: AgentMutationPostconditionEvidence =
                serde_json::from_value(value.clone()).expect("valid typed evidence");
            let mut substituted = value;
            match substituted["type"].as_str().expect("variant") {
                "RENAME" => substituted["resultingTarget"]["fqName"] = json!("sample.other"),
                "REPLACEMENT" => {
                    substituted["signature"]["returnType"] = json!("kotlin.String")
                }
                "ADD_FILE" => substituted["owner"]["sourceSetName"] = json!("test"),
                "ADD_DECLARATION" => substituted["declaration"]["name"] = json!("Other"),
                _ => unreachable!(),
            }
            let substituted = serde_json::from_value(substituted)
                .expect("substitution remains structurally valid");
            assert_ne!(expected, substituted);
        }
    }
}

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
