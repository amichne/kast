use crate::config as agent_path_config;
use std::ffi::OsString as AgentPathSegment;
use std::fs as agent_path_fs;
use std::io::ErrorKind as AgentPathIoErrorKind;

#[derive(Debug)]
struct AgentFilePathNormalizer {
    declared_root: PathBuf,
    canonical_root: PathBuf,
    relative_targets_allowed: bool,
}

impl AgentFilePathNormalizer {
    fn from_runtime(runtime: &AgentRuntimeArgs) -> std::result::Result<Self, AgentError> {
        let requested_root = runtime.workspace_root.clone();
        let resolved_root = agent_path_config::resolve_workspace_root(requested_root.clone())
            .map_err(|error| {
                agent_path_error(
                    "AGENT_WORKSPACE_INVALID",
                    format!("Cannot resolve the agent workspace root: {error}"),
                    requested_root.as_deref(),
                    None,
                    None,
                )
            })?;
        let declared_root = lexically_normalize_absolute(&resolved_root).ok_or_else(|| {
            agent_path_error(
                "AGENT_WORKSPACE_INVALID",
                format!(
                    "The agent workspace root is not an absolute path: {}",
                    resolved_root.display()
                ),
                Some(&resolved_root),
                None,
                None,
            )
        })?;
        let canonical_root = agent_path_fs::canonicalize(&declared_root).map_err(|error| {
            agent_path_error(
                "AGENT_WORKSPACE_INVALID",
                format!(
                    "Cannot canonicalize the agent workspace root {}: {error}",
                    declared_root.display()
                ),
                Some(&declared_root),
                None,
                None,
            )
        })?;
        if !canonical_root.is_dir() {
            return Err(agent_path_error(
                "AGENT_WORKSPACE_INVALID",
                format!(
                    "The agent workspace root is not a directory: {}",
                    canonical_root.display()
                ),
                Some(&declared_root),
                Some(&canonical_root),
                None,
            ));
        }
        Ok(Self {
            declared_root,
            canonical_root,
            relative_targets_allowed: runtime.workspace_root.is_some(),
        })
    }

    fn normalize(
        &self,
        input: &str,
    ) -> std::result::Result<CanonicalKotlinFilePath, AgentError> {
        if input.trim().is_empty() {
            return Err(self.error(
                "AGENT_FILE_KIND_UNSUPPORTED",
                "Kotlin file path cannot be empty.",
                input,
                None,
            ));
        }
        let input_path = Path::new(input);
        if !input_path.is_absolute() && !self.relative_targets_allowed {
            return Err(self.error(
                "AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE",
                "A relative Kotlin file path requires explicit --workspace-root.",
                input,
                None,
            ));
        }
        if !is_kotlin_path(input_path) {
            return Err(self.error(
                "AGENT_FILE_KIND_UNSUPPORTED",
                "Kotlin file targets must end in .kt or .kts.",
                input,
                None,
            ));
        }

        let joined = if input_path.is_absolute() {
            input_path.to_path_buf()
        } else {
            self.declared_root.join(input_path)
        };
        let candidate = lexically_normalize_absolute(&joined).ok_or_else(|| {
            self.error(
                "AGENT_FILE_OUTSIDE_WORKSPACE",
                "The Kotlin file path escapes the filesystem root.",
                input,
                Some(&joined),
            )
        })?;
        let declared_candidate_is_contained = candidate.starts_with(&self.declared_root);
        if !input_path.is_absolute() && !declared_candidate_is_contained {
            return Err(self.error(
                "AGENT_FILE_OUTSIDE_WORKSPACE",
                "The relative Kotlin file path escapes the declared workspace root.",
                input,
                Some(&candidate),
            ));
        }

        let (canonical_path, target_exists) = self.resolve_candidate(input, &candidate)?;
        if !canonical_path.starts_with(&self.canonical_root) {
            let (code, message) = if declared_candidate_is_contained {
                (
                    "AGENT_FILE_SYMLINK_UNSAFE",
                    "The Kotlin file path resolves through a symlink outside the workspace.",
                )
            } else {
                (
                    "AGENT_FILE_OUTSIDE_WORKSPACE",
                    "The Kotlin file path is outside the workspace.",
                )
            };
            return Err(self.error(code, message, input, Some(&canonical_path)));
        }
        if target_exists {
            let metadata = agent_path_fs::metadata(&canonical_path).map_err(|error| {
                self.error(
                    "AGENT_FILE_PATH_UNREADABLE",
                    format!("Cannot read Kotlin file metadata: {error}"),
                    input,
                    Some(&canonical_path),
                )
            })?;
            if !metadata.is_file() {
                return Err(self.error(
                    "AGENT_FILE_KIND_UNSUPPORTED",
                    "The Kotlin target exists but is not a regular file.",
                    input,
                    Some(&canonical_path),
                ));
            }
        }
        if !is_kotlin_path(&canonical_path) {
            return Err(self.error(
                "AGENT_FILE_KIND_UNSUPPORTED",
                "The resolved target must end in .kt or .kts.",
                input,
                Some(&canonical_path),
            ));
        }
        let rpc_path = canonical_path.to_str().ok_or_else(|| {
            self.error(
                "AGENT_FILE_PATH_UNREADABLE",
                "The resolved Kotlin file path is not valid UTF-8.",
                input,
                Some(&canonical_path),
            )
        })?;
        Ok(CanonicalKotlinFilePath {
            rpc_path: rpc_path.to_string(),
        })
    }

    fn normalize_all(
        &self,
        inputs: &[String],
    ) -> std::result::Result<Vec<String>, AgentError> {
        inputs
            .iter()
            .map(|input| self.normalize(input).map(CanonicalKotlinFilePath::into_rpc_path))
            .collect()
    }

    fn resolve_candidate(
        &self,
        input: &str,
        candidate: &Path,
    ) -> std::result::Result<(PathBuf, bool), AgentError> {
        let mut cursor = candidate.to_path_buf();
        let mut missing_suffix = Vec::<AgentPathSegment>::new();
        loop {
            match agent_path_fs::symlink_metadata(&cursor) {
                Ok(metadata) => {
                    let canonical_prefix = agent_path_fs::canonicalize(&cursor).map_err(|error| {
                        let code = if metadata.file_type().is_symlink() {
                            "AGENT_FILE_SYMLINK_UNSAFE"
                        } else {
                            "AGENT_FILE_PATH_UNREADABLE"
                        };
                        self.error(
                            code,
                            format!("Cannot resolve Kotlin file path: {error}"),
                            input,
                            Some(&cursor),
                        )
                    })?;
                    if !missing_suffix.is_empty() {
                        let canonical_metadata = agent_path_fs::metadata(&canonical_prefix)
                            .map_err(|error| {
                                self.error(
                                    "AGENT_FILE_PATH_UNREADABLE",
                                    format!("Cannot read Kotlin path prefix metadata: {error}"),
                                    input,
                                    Some(&canonical_prefix),
                                )
                            })?;
                        if !canonical_metadata.is_dir() {
                            return Err(self.error(
                                "AGENT_FILE_KIND_UNSUPPORTED",
                                "A missing Kotlin target is nested beneath a non-directory path.",
                                input,
                                Some(&canonical_prefix),
                            ));
                        }
                    }
                    let mut resolved = canonical_prefix;
                    for segment in missing_suffix.iter().rev() {
                        resolved.push(segment);
                    }
                    return Ok((resolved, missing_suffix.is_empty()));
                }
                Err(error) if error.kind() == AgentPathIoErrorKind::NotFound => {
                    let Some(file_name) = cursor.file_name() else {
                        return Err(self.error(
                            "AGENT_FILE_PATH_UNREADABLE",
                            "Cannot find an existing filesystem ancestor for the Kotlin target.",
                            input,
                            Some(&cursor),
                        ));
                    };
                    missing_suffix.push(file_name.to_os_string());
                    let Some(parent) = cursor.parent() else {
                        return Err(self.error(
                            "AGENT_FILE_PATH_UNREADABLE",
                            "Cannot find an existing filesystem ancestor for the Kotlin target.",
                            input,
                            Some(&cursor),
                        ));
                    };
                    cursor = parent.to_path_buf();
                }
                Err(error) => {
                    return Err(self.error(
                        "AGENT_FILE_PATH_UNREADABLE",
                        format!("Cannot inspect Kotlin file path: {error}"),
                        input,
                        Some(&cursor),
                    ));
                }
            }
        }
    }

    fn error(
        &self,
        code: &'static str,
        message: impl Into<String>,
        input: &str,
        resolved_path: Option<&Path>,
    ) -> AgentError {
        agent_path_error(
            code,
            message,
            Some(&self.declared_root),
            resolved_path,
            Some(input),
        )
    }
}

#[derive(Debug)]
struct CanonicalKotlinFilePath {
    rpc_path: String,
}

impl CanonicalKotlinFilePath {
    fn into_rpc_path(self) -> String {
        self.rpc_path
    }
}

fn normalize_agent_file_target(
    runtime: &AgentRuntimeArgs,
    input: &str,
) -> std::result::Result<String, AgentError> {
    AgentFilePathNormalizer::from_runtime(runtime)?
        .normalize(input)
        .map(CanonicalKotlinFilePath::into_rpc_path)
}

fn lexically_normalize_absolute(path: &Path) -> Option<PathBuf> {
    if !path.is_absolute() {
        return None;
    }
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Prefix(_) | Component::RootDir | Component::Normal(_) => {
                normalized.push(component.as_os_str());
            }
            Component::CurDir => {}
            Component::ParentDir => {
                if !normalized.pop() {
                    return None;
                }
            }
        }
    }
    Some(normalized)
}

fn is_kotlin_path(path: &Path) -> bool {
    matches!(path.extension().and_then(|extension| extension.to_str()), Some("kt" | "kts"))
}

fn agent_path_error(
    code: &'static str,
    message: impl Into<String>,
    workspace_root: Option<&Path>,
    resolved_path: Option<&Path>,
    input: Option<&str>,
) -> AgentError {
    let mut error = agent_error(code, message);
    if let Some(workspace_root) = workspace_root {
        error.details.insert(
            "workspaceRoot".to_string(),
            json!(workspace_root.display().to_string()),
        );
    }
    if let Some(resolved_path) = resolved_path {
        error.details.insert(
            "resolvedPath".to_string(),
            json!(resolved_path.display().to_string()),
        );
    }
    if let Some(input) = input {
        error
            .details
            .insert("input".to_string(), json!(input));
    }
    error
}
