const REPOSITORY_LABEL_INDEX_TYPE: &str = "KAST_REPOSITORY_LABEL_INDEX";
const REPOSITORY_LABEL_INDEX_SCHEMA_VERSION: u32 = 1;
const MAX_REPOSITORY_LABEL_INDEX_BYTES: u64 = 8 * 1024 * 1024;
const MAX_REPOSITORY_LABEL_ENTRIES: usize = 50_000;
const MAX_REPOSITORY_LABELS_PER_ENTRY: usize = 16;
const MAX_REPOSITORY_LABEL_LENGTH: usize = 160;
const MAX_REPOSITORY_CANONICAL_KEY_LENGTH: usize = 4_096;

#[derive(Debug, Clone)]
struct RepositoryLabelIndexPath(String);

impl RepositoryLabelIndexPath {
    fn parse(raw: String) -> Result<Self> {
        if raw.is_empty()
            || raw.len() > 4_096
            || raw.trim() != raw
            || raw.contains('\\')
            || raw.chars().any(char::is_control)
            || raw.starts_with('/')
            || matches!(raw.as_bytes(), [drive, b':', ..] if drive.is_ascii_alphabetic())
        {
            return Err(invalid_repository_label_index(
                "labelIndex/--label-index must be a bounded workspace-relative forward-slash path",
            ));
        }
        let mut segments = Vec::new();
        for component in Path::new(&raw).components() {
            match component {
                std::path::Component::Normal(segment) => {
                    let segment = segment.to_str().ok_or_else(|| {
                        invalid_repository_label_index(
                            "labelIndex/--label-index must be valid UTF-8",
                        )
                    })?;
                    segments.push(segment);
                }
                std::path::Component::CurDir => {}
                std::path::Component::ParentDir => {
                    return Err(invalid_repository_label_index(
                        "labelIndex/--label-index cannot escape the workspace with `..`",
                    ));
                }
                std::path::Component::RootDir | std::path::Component::Prefix(_) => {
                    return Err(invalid_repository_label_index(
                        "labelIndex/--label-index must be workspace-relative",
                    ));
                }
            }
        }
        if segments.is_empty() {
            return Err(invalid_repository_label_index(
                "labelIndex/--label-index must name a file",
            ));
        }
        Ok(Self(segments.join("/")))
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RepositoryLabelIndexArtifact {
    #[serde(rename = "type")]
    artifact_type: String,
    schema_version: u32,
    entries: Vec<RepositoryLabelEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RepositoryLabelEntry {
    canonical_key: String,
    content_hash: String,
    labels: Vec<String>,
}

struct ParsedRepositoryLabelIndex {
    entries: Vec<RepositoryLabelEntry>,
}

struct CompilerIdentityBoundLabels {
    by_identity: BTreeMap<String, String>,
}

impl ParsedRepositoryLabelIndex {
    fn load(workspace_root: &WorkspaceRoot, path: &RepositoryLabelIndexPath) -> Result<Self> {
        let candidate = workspace_root.as_path().join(path.as_str());
        let canonical = std::fs::canonicalize(&candidate).map_err(|error| {
            unavailable_repository_label_index(
                path,
                format!("cannot open the precomputed label index: {error}"),
            )
        })?;
        if !canonical.starts_with(workspace_root.as_path()) {
            return Err(unavailable_repository_label_index(
                path,
                "precomputed label index resolves outside the routed workspace",
            ));
        }
        let admitted_metadata = canonical.metadata().map_err(|error| {
            unavailable_repository_label_index(
                path,
                format!("cannot inspect the precomputed label index: {error}"),
            )
        })?;
        if !admitted_metadata.is_file() {
            return Err(unavailable_repository_label_index(
                path,
                "precomputed label index is not a regular file",
            ));
        }
        if admitted_metadata.len() > MAX_REPOSITORY_LABEL_INDEX_BYTES {
            return Err(invalid_repository_label_index(format!(
                "precomputed label index exceeds the {} byte limit",
                MAX_REPOSITORY_LABEL_INDEX_BYTES
            )));
        }
        let file = std::fs::File::open(&canonical).map_err(|error| {
            unavailable_repository_label_index(
                path,
                format!("cannot open the precomputed label index: {error}"),
            )
        })?;
        let metadata = file.metadata().map_err(|error| {
            unavailable_repository_label_index(
                path,
                format!("cannot inspect the precomputed label index: {error}"),
            )
        })?;
        if !same_repository_context_file(&admitted_metadata, &metadata) {
            return Err(unavailable_repository_label_index(
                path,
                "precomputed label index changed after containment was proven; retry the query",
            ));
        }
        if metadata.len() > MAX_REPOSITORY_LABEL_INDEX_BYTES {
            return Err(invalid_repository_label_index(format!(
                "precomputed label index exceeds the {} byte limit",
                MAX_REPOSITORY_LABEL_INDEX_BYTES
            )));
        }
        let mut bytes = Vec::new();
        let mut reader = std::io::Read::take(file, MAX_REPOSITORY_LABEL_INDEX_BYTES + 1);
        std::io::Read::read_to_end(
            &mut reader,
            &mut bytes,
        )
        .map_err(|error| {
            unavailable_repository_label_index(
                path,
                format!("cannot read the precomputed label index: {error}"),
            )
        })?;
        if bytes.len() as u64 > MAX_REPOSITORY_LABEL_INDEX_BYTES {
            return Err(invalid_repository_label_index(format!(
                "precomputed label index exceeds the {} byte limit",
                MAX_REPOSITORY_LABEL_INDEX_BYTES
            )));
        }
        let artifact: RepositoryLabelIndexArtifact =
            serde_json::from_slice(&bytes).map_err(|error| {
                let cause = error.to_string().chars().take(512).collect::<String>();
                invalid_repository_label_index(format!(
                    "precomputed label index is not valid versioned JSON: {cause}"
                ))
            })?;
        artifact.validated()
    }

    fn verify(&self, connection: &Connection) -> Result<CompilerIdentityBoundLabels> {
        let mut statement = connection
            .prepare(
                "SELECT symbol.stable_key, file.content_hash
                 FROM semantic_symbols symbol
                 JOIN semantic_files file ON file.id = symbol.file_id
                 ORDER BY symbol.stable_key",
            )
            .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        let compiler_hashes = statement
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?))
            })
            .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?
            .collect::<rusqlite::Result<BTreeMap<_, _>>>()
            .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
        let mut by_identity = BTreeMap::new();
        for entry in &self.entries {
            let Some(current_hash) = compiler_hashes.get(&entry.canonical_key) else {
                return Err(stale_repository_label_index(
                    &entry.canonical_key,
                    "canonical identity is absent from the current compiler snapshot",
                ));
            };
            let Some(current_hash) = current_hash.as_deref() else {
                return Err(CliError::new(
                    "REPOSITORY_INDEX_INVALID",
                    format!(
                        "compiler snapshot has no source content hash for {}",
                        entry.canonical_key
                    ),
                ));
            };
            if !is_lowercase_sha256(current_hash) {
                return Err(CliError::new(
                    "REPOSITORY_INDEX_INVALID",
                    format!(
                        "compiler snapshot has a non-canonical source content hash for {}",
                        entry.canonical_key
                    ),
                ));
            }
            if current_hash != entry.content_hash.as_str() {
                return Err(stale_repository_label_index(
                    &entry.canonical_key,
                    "compiler-recorded source content hash changed",
                ));
            }
            by_identity.insert(entry.canonical_key.clone(), entry.labels.join(" "));
        }
        Ok(CompilerIdentityBoundLabels { by_identity })
    }
}

impl RepositoryLabelIndexArtifact {
    fn validated(self) -> Result<ParsedRepositoryLabelIndex> {
        if self.artifact_type.len() > 128 || self.artifact_type.chars().any(char::is_control) {
            return Err(invalid_repository_label_index(
                "precomputed label index type must be bounded without control characters",
            ));
        }
        if self.artifact_type != REPOSITORY_LABEL_INDEX_TYPE
            || self.schema_version != REPOSITORY_LABEL_INDEX_SCHEMA_VERSION
        {
            return Err(unsupported_repository_label_index(
                &self.artifact_type,
                self.schema_version,
            ));
        }
        if self.entries.is_empty() || self.entries.len() > MAX_REPOSITORY_LABEL_ENTRIES {
            return Err(invalid_repository_label_index(format!(
                "precomputed label index must contain 1 through {MAX_REPOSITORY_LABEL_ENTRIES} entries"
            )));
        }
        let mut canonical_keys = BTreeSet::new();
        for entry in &self.entries {
            validate_repository_label_entry(entry)?;
            if !canonical_keys.insert(&entry.canonical_key) {
                return Err(invalid_repository_label_index(format!(
                    "precomputed label index repeats canonicalKey {}",
                    entry.canonical_key
                )));
            }
        }
        Ok(ParsedRepositoryLabelIndex {
            entries: self.entries,
        })
    }
}

impl CompilerIdentityBoundLabels {
    fn for_identity(&self, canonical_key: &str) -> Option<&str> {
        self.by_identity.get(canonical_key).map(String::as_str)
    }
}

fn validate_repository_label_entry(entry: &RepositoryLabelEntry) -> Result<()> {
    if entry.canonical_key.is_empty()
        || entry.canonical_key.len() > MAX_REPOSITORY_CANONICAL_KEY_LENGTH
        || entry.canonical_key.trim() != entry.canonical_key
        || entry.canonical_key.chars().any(char::is_control)
    {
        return Err(invalid_repository_label_index(
            "every canonicalKey must be bounded, non-blank, trimmed, and free of control characters",
        ));
    }
    if !is_lowercase_sha256(&entry.content_hash) {
        return Err(invalid_repository_label_index(format!(
            "label entry {} has a non-canonical contentHash",
            entry.canonical_key
        )));
    }
    if entry.labels.is_empty() || entry.labels.len() > MAX_REPOSITORY_LABELS_PER_ENTRY {
        return Err(invalid_repository_label_index(format!(
            "label entry {} must contain 1 through {MAX_REPOSITORY_LABELS_PER_ENTRY} labels",
            entry.canonical_key
        )));
    }
    let mut distinct = BTreeSet::new();
    for label in &entry.labels {
        if label.is_empty()
            || label.len() > MAX_REPOSITORY_LABEL_LENGTH
            || label.trim() != label
            || label.chars().any(char::is_control)
            || !label.chars().any(char::is_alphanumeric)
            || !distinct.insert(label)
        {
            return Err(invalid_repository_label_index(format!(
                "label entry {} contains a blank, duplicate, unbounded, or non-canonical label",
                entry.canonical_key
            )));
        }
    }
    Ok(())
}

fn is_lowercase_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn validate_repository_label_index_request(
    raw: Option<String>,
    syntax: RepositoryQuerySyntax,
    intent: RepositoryIntent,
    canonical_key: Option<&str>,
) -> Result<Option<RepositoryLabelIndexPath>> {
    let Some(raw) = raw else {
        return Ok(None);
    };
    if syntax != RepositoryQuerySyntax::NaturalLanguage
        || intent != RepositoryIntent::Resolve
        || canonical_key.is_some()
    {
        return Err(invalid_repository_query(
            "labelIndex/--label-index requires natural-language intent=resolve without canonicalKey/--canonical-key",
        ));
    }
    RepositoryLabelIndexPath::parse(raw).map(Some)
}

fn invalid_repository_label_index(message: impl Into<String>) -> CliError {
    let mut error = CliError::new("INVALID_REPOSITORY_LABEL_INDEX", message);
    error.details.insert(
        "remedy".to_string(),
        "Provide a bounded version-1 KAST_REPOSITORY_LABEL_INDEX JSON file generated from compiler canonical identities and source content hashes."
            .to_string(),
    );
    error
}

fn unsupported_repository_label_index(artifact_type: &str, schema_version: u32) -> CliError {
    let mut error = CliError::new(
        "UNSUPPORTED_REPOSITORY_LABEL_INDEX",
        "precomputed label index type or schema version is unsupported",
    );
    error
        .details
        .insert("actualType".to_string(), artifact_type.to_string());
    error
        .details
        .insert("actualSchemaVersion".to_string(), schema_version.to_string());
    error.details.insert(
        "expected".to_string(),
        format!("{REPOSITORY_LABEL_INDEX_TYPE} schema version {REPOSITORY_LABEL_INDEX_SCHEMA_VERSION}"),
    );
    error
}

fn unavailable_repository_label_index(
    path: &RepositoryLabelIndexPath,
    message: impl Into<String>,
) -> CliError {
    let mut error = CliError::new("REPOSITORY_LABEL_INDEX_UNAVAILABLE", message);
    error
        .details
        .insert("labelIndex".to_string(), path.as_str().to_string());
    error.details.insert(
        "remedy".to_string(),
        "Place the label index at a regular file inside the routed workspace or omit labelIndex."
            .to_string(),
    );
    error
}

fn stale_repository_label_index(canonical_key: &str, cause: &str) -> CliError {
    let mut error = CliError::new(
        "REPOSITORY_LABEL_INDEX_STALE",
        "precomputed label index does not match the current compiler snapshot",
    );
    error
        .details
        .insert("canonicalKey".to_string(), canonical_key.to_string());
    error
        .details
        .insert("cause".to_string(), cause.to_string());
    error.details.insert(
        "remedy".to_string(),
        "Regenerate the label index from the current compiler-backed graph before retrying."
            .to_string(),
    );
    error
}
