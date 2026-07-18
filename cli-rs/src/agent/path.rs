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
    fn rpc_path(&self) -> &str {
        &self.rpc_path
    }

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

#[cfg(test)]
mod agent_file_path_tests {
    use super::*;

    #[test]
    fn relative_kotlin_file_resolves_against_explicit_workspace() {
        let fixture = PathFixture::with_file("src/with spaces/App.kt");

        let actual = fixture
            .normalizer()
            .normalize("src/with spaces/App.kt")
            .expect("canonical target");

        assert_eq!(actual.rpc_path(), fixture.canonical_file());
    }

    #[test]
    fn absolute_kotlin_file_remains_compatible() {
        let fixture = PathFixture::with_file("src/App.kt");

        let actual = fixture
            .normalizer()
            .normalize(fixture.file.to_str().expect("UTF-8 file"))
            .expect("canonical target");

        assert_eq!(actual.rpc_path(), fixture.canonical_file());
    }

    #[test]
    fn kotlin_script_is_supported() {
        let fixture = PathFixture::with_file("build-logic/settings.gradle.kts");

        let actual = fixture
            .normalizer()
            .normalize("build-logic/settings.gradle.kts")
            .expect("canonical script");

        assert_eq!(actual.rpc_path(), fixture.canonical_file());
    }

    #[test]
    fn missing_kotlin_leaf_uses_canonical_existing_parent() {
        let fixture = PathFixture::with_workspace();
        let parent = fixture.workspace.join("src/generated");
        std::fs::create_dir_all(&parent).expect("source parent");
        let expected = parent
            .canonicalize()
            .expect("canonical parent")
            .join("Deleted.kt");

        let actual = fixture
            .normalizer()
            .normalize("src/generated/Deleted.kt")
            .expect("missing Kotlin target");

        assert_eq!(actual.rpc_path(), expected.to_str().expect("UTF-8 expected"));
    }

    #[test]
    fn relative_path_requires_explicit_workspace_root() {
        let fixture = PathFixture::with_file("src/App.kt");
        let runtime = AgentRuntimeArgs {
            workspace_root: None,
            backend_name: None,
            lease_id: None,
        };
        let normalizer = AgentFilePathNormalizer::from_runtime(&runtime)
            .expect("current-directory normalizer");

        let error = normalizer
            .normalize("src/App.kt")
            .expect_err("relative path without declared workspace must fail");

        assert_eq!(error.code, "AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE");
        drop(fixture);
    }

    #[test]
    fn relative_parent_escape_fails_closed() {
        let fixture = PathFixture::with_workspace();

        let error = fixture
            .normalizer()
            .normalize("../Outside.kt")
            .expect_err("lexical escape must fail");

        assert_eq!(error.code, "AGENT_FILE_OUTSIDE_WORKSPACE");
    }

    #[test]
    fn absolute_outside_file_fails_closed() {
        let fixture = PathFixture::with_workspace();
        let outside = fixture.temp.path().join("Outside.kt");
        std::fs::write(&outside, "class Outside\n").expect("outside source");

        let error = fixture
            .normalizer()
            .normalize(outside.to_str().expect("UTF-8 outside path"))
            .expect_err("outside target must fail");

        assert_eq!(error.code, "AGENT_FILE_OUTSIDE_WORKSPACE");
    }

    #[test]
    fn unsupported_extension_fails_closed() {
        let fixture = PathFixture::with_file("src/App.java");

        let error = fixture
            .normalizer()
            .normalize("src/App.java")
            .expect_err("Java target must fail");

        assert_eq!(error.code, "AGENT_FILE_KIND_UNSUPPORTED");
    }

    #[test]
    fn directory_with_kotlin_extension_fails_closed() {
        let fixture = PathFixture::with_workspace();
        std::fs::create_dir_all(fixture.workspace.join("src/Directory.kt"))
            .expect("Kotlin-named directory");

        let error = fixture
            .normalizer()
            .normalize("src/Directory.kt")
            .expect_err("directory target must fail");

        assert_eq!(error.code, "AGENT_FILE_KIND_UNSUPPORTED");
    }

    #[cfg(unix)]
    #[test]
    fn in_workspace_symlink_resolves_to_real_kotlin_file() {
        let fixture = PathFixture::with_file("src/Real.kt");
        let alias = fixture.workspace.join("src/Alias.kt");
        std::os::unix::fs::symlink(&fixture.file, &alias).expect("safe symlink");

        let actual = fixture
            .normalizer()
            .normalize("src/Alias.kt")
            .expect("safe symlink target");

        assert_eq!(actual.rpc_path(), fixture.canonical_file());
    }

    #[cfg(unix)]
    #[test]
    fn escaping_symlink_fails_closed() {
        let fixture = PathFixture::with_workspace();
        let outside = fixture.temp.path().join("Outside.kt");
        std::fs::write(&outside, "class Outside\n").expect("outside source");
        let alias = fixture.workspace.join("Alias.kt");
        std::os::unix::fs::symlink(&outside, &alias).expect("escaping symlink");

        let error = fixture
            .normalizer()
            .normalize("Alias.kt")
            .expect_err("symlink escape must fail");

        assert_eq!(error.code, "AGENT_FILE_SYMLINK_UNSAFE");
    }

    #[cfg(unix)]
    #[test]
    fn broken_symlink_fails_closed() {
        let fixture = PathFixture::with_workspace();
        let alias = fixture.workspace.join("Broken.kt");
        std::os::unix::fs::symlink(fixture.workspace.join("Missing.kt"), &alias)
            .expect("broken symlink");

        let error = fixture
            .normalizer()
            .normalize("Broken.kt")
            .expect_err("broken symlink must fail");

        assert_eq!(error.code, "AGENT_FILE_SYMLINK_UNSAFE");
    }

    struct PathFixture {
        temp: tempfile::TempDir,
        workspace: PathBuf,
        file: PathBuf,
    }

    impl PathFixture {
        fn with_workspace() -> Self {
            let temp = tempfile::tempdir().expect("tempdir");
            let workspace = temp.path().join("workspace");
            std::fs::create_dir_all(&workspace).expect("workspace");
            Self {
                temp,
                workspace,
                file: PathBuf::new(),
            }
        }

        fn with_file(relative_path: &str) -> Self {
            let mut fixture = Self::with_workspace();
            fixture.file = fixture.workspace.join(relative_path);
            std::fs::create_dir_all(fixture.file.parent().expect("source parent"))
                .expect("source directory");
            std::fs::write(&fixture.file, "class Fixture\n").expect("source");
            fixture
        }

        fn normalizer(&self) -> AgentFilePathNormalizer {
            AgentFilePathNormalizer::from_runtime(&AgentRuntimeArgs {
                workspace_root: Some(self.workspace.clone()),
                backend_name: None,
                lease_id: None,
            })
            .expect("normalizer")
        }

        fn canonical_file(&self) -> String {
            self.file
                .canonicalize()
                .expect("canonical file")
                .to_str()
                .expect("UTF-8 canonical file")
                .to_string()
        }
    }
}
