#[derive(Debug, Clone, PartialEq, Eq)]
struct SemanticGraphStageInputFingerprint(String);

#[derive(Debug, Clone, PartialEq, Eq)]
struct PersistedFileContentHash(String);

impl PersistedFileContentHash {
    fn parse(value: String, path: &str, field: &str) -> Result<Self> {
        if value.len() != 64
            || !value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
        {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                format!("semantic graph {field} for `{path}` is not canonical SHA-256 hex"),
            ));
        }
        Ok(Self(value))
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct PersistedFileStageVersion(String);

impl PersistedFileStageVersion {
    fn parse(value: String, path: &str, field: &str) -> Result<Self> {
        if value.is_empty()
            || value.trim() != value
            || value.chars().all(char::is_whitespace)
            || value.chars().any(char::is_control)
        {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                format!(
                    "semantic graph {field} for `{path}` must be non-blank, trimmed, and printable"
                ),
            ));
        }
        Ok(Self(value))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct PersistedSemanticGraphSourcePath(String);

impl PersistedSemanticGraphSourcePath {
    fn parse(value: String) -> Result<Self> {
        let bytes = value.as_bytes();
        let has_windows_drive =
            bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
        let normalized = !value.is_empty()
            && !value.chars().any(char::is_control)
            && !value.contains('\\')
            && !value.starts_with('/')
            && !has_windows_drive
            && value
                .split('/')
                .all(|segment| !segment.is_empty() && segment != "." && segment != "..")
            && (value.ends_with(".kt") || value.ends_with(".kts"));
        if !normalized {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                format!("semantic graph source path `{value}` is not normalized and contained"),
            ));
        }
        Ok(Self(value))
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

impl SemanticGraphStageInputFingerprint {
    fn parse(value: String, path: &str) -> Result<Self> {
        if value.len() != 64
            || !value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
        {
            return Err(CliError::new(
                "GRAPH_COVERAGE_UNAVAILABLE",
                format!(
                    "semantic graph outcome for `{path}` has an invalid stage input fingerprint"
                ),
            ));
        }
        Ok(Self(value))
    }

    fn from_inputs(
        inputs: impl IntoIterator<
            Item = (
                PersistedSemanticGraphSourcePath,
                PersistedFileContentHash,
            ),
        >,
    ) -> Self {
        let mut inputs = inputs.into_iter().collect::<Vec<_>>();
        inputs.sort_by(|left, right| left.0.0.encode_utf16().cmp(right.0.0.encode_utf16()));
        let mut digest = Sha256::new();
        for (path, content_hash) in inputs {
            digest.update(b"source:");
            digest.update(path.0.as_bytes());
            digest.update(b":");
            digest.update(content_hash.0.as_bytes());
            digest.update(b"\n");
        }
        Self(hex::encode(digest.finalize()))
    }
}
