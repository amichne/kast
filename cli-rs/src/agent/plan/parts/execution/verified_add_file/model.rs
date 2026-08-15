const VERIFIED_ADD_FILE_STORE_SCHEMA_VERSION: u32 = 1;
const VERIFIED_ADD_FILE_INITIAL_VERSION: u64 = 0;
const VERIFIED_ADD_FILE_TERMINAL_VERSION: u64 = 5;

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(transparent)]
struct VerifiedAddFilePlanVersion(u64);

impl VerifiedAddFilePlanVersion {
    fn initial(value: u64) -> Result<Self> {
        (value == VERIFIED_ADD_FILE_INITIAL_VERSION)
            .then_some(Self(value))
            .ok_or_else(|| {
                CliError::new(
                    "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
                    "The server-issued add-file plan was not at its initial lifecycle version.",
                )
            })
    }

    fn value(self) -> u64 {
        self.0
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(transparent)]
struct VerifiedAddFileSha256(String);

impl VerifiedAddFileSha256 {
    fn admit(value: String) -> Result<Self> {
        canonical_lowercase_sha256(&value)
            .then_some(Self(value))
            .ok_or_else(|| {
                CliError::new(
                    "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
                    "The persisted add-file postimage digest is not canonical SHA-256.",
                )
            })
    }

    fn from_source(source: &VerifiedAddFileSource) -> Self {
        Self(manifest::sha256_bytes(source.as_str().as_bytes()))
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(transparent)]
struct VerifiedAddFileTarget(String);

impl VerifiedAddFileTarget {
    fn admit(workspace_root: &Path, requested: PathBuf) -> Result<Self> {
        let absolute = if requested.is_absolute() {
            requested
        } else {
            workspace_root.join(requested)
        };
        if absolute.extension().and_then(|value| value.to_str()) != Some("kt") {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_FILE_TARGET_INVALID",
                "The add-file target must be one absent workspace-owned .kt file.",
            ));
        }
        match fs::symlink_metadata(&absolute) {
            Ok(_) => {
                return Err(CliError::new(
                    "KAST_VERIFIED_ADD_FILE_TARGET_INVALID",
                    "The add-file target must be absent before planning.",
                ));
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(CliError::from(error)),
        }
        let parent = absolute.parent().ok_or_else(|| {
            CliError::new(
                "KAST_VERIFIED_ADD_FILE_TARGET_INVALID",
                "The add-file target has no parent directory.",
            )
        })?;
        let canonical_parent = parent.canonicalize().map_err(|error| {
            CliError::new(
                "KAST_VERIFIED_ADD_FILE_TARGET_INVALID",
                format!("The add-file parent could not be proven: {error}"),
            )
        })?;
        if !canonical_parent.starts_with(workspace_root) {
            return Err(CliError::new(
                "KAST_VERIFIED_ADD_FILE_TARGET_INVALID",
                "The add-file target escapes the exact workspace root.",
            ));
        }
        let target = canonical_parent.join(
            absolute
                .file_name()
                .ok_or_else(|| CliError::new("KAST_VERIFIED_ADD_FILE_TARGET_INVALID", "The add-file target has no file name."))?,
        );
        target
            .to_str()
            .map(|value| Self(value.to_string()))
            .ok_or_else(|| {
                CliError::new(
                    "KAST_VERIFIED_ADD_FILE_TARGET_INVALID",
                    "The canonical add-file target is not exact UTF-8.",
                )
            })
    }

    fn as_str(&self) -> &str {
        &self.0
    }

    fn readmit(workspace_root: &Path, value: String) -> Result<Self> {
        let target = PathBuf::from(&value);
        let parent = target.parent().ok_or_else(|| {
            CliError::new(
                "KAST_PLAN_INVALID",
                "The persisted add-file target has no parent directory.",
            )
        })?;
        let canonical_ancestor = deepest_existing_add_file_parent(parent)?;
        let missing_suffix = parent.strip_prefix(&canonical_ancestor).map_err(|_| {
            CliError::new(
                "KAST_PLAN_INVALID",
                "The persisted add-file parent is not rooted at its canonical ancestor.",
            )
        })?;
        let expected = canonical_ancestor.join(missing_suffix).join(target.file_name().ok_or_else(|| {
            CliError::new("KAST_PLAN_INVALID", "The persisted add-file target has no file name.")
        })?);
        (target.is_absolute()
            && !target.components().any(|part| {
                matches!(part, std::path::Component::CurDir | std::path::Component::ParentDir)
            })
            && target.extension().and_then(|part| part.to_str()) == Some("kt")
            && expected == target
            && canonical_ancestor.starts_with(workspace_root)
            && target.starts_with(workspace_root))
        .then_some(Self(value))
        .ok_or_else(|| {
            CliError::new(
                "KAST_PLAN_INVALID",
                "The persisted add-file target no longer belongs to the exact workspace.",
            )
        })
    }
}

fn deepest_existing_add_file_parent(parent: &Path) -> Result<PathBuf> {
    let mut candidate = parent;
    loop {
        match fs::symlink_metadata(candidate) {
            Ok(metadata) if metadata.is_dir() => {
                let canonical = candidate.canonicalize().map_err(CliError::from)?;
                if canonical != candidate {
                    return Err(CliError::new(
                        "KAST_PLAN_INVALID",
                        "The persisted add-file parent traverses a symbolic link.",
                    ));
                }
                return Ok(canonical);
            }
            Ok(_) => {
                return Err(CliError::new(
                    "KAST_PLAN_INVALID",
                    "The persisted add-file parent is obstructed by a non-directory path.",
                ));
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                candidate = candidate.parent().ok_or_else(|| {
                    CliError::new(
                        "KAST_PLAN_INVALID",
                        "The persisted add-file target has no existing parent authority.",
                    )
                })?;
            }
            Err(error) => return Err(error.into()),
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
struct VerifiedAddFileSource(String);

impl VerifiedAddFileSource {
    fn admit(content: PreparedPlanContent) -> Result<Self> {
        let value = String::from_utf8(content.bytes).map_err(|_| {
            CliError::new(
                "KAST_VERIFIED_ADD_FILE_SOURCE_INVALID",
                "The proposed Kotlin file must be exact UTF-8 text.",
            )
        })?;
        crate::agent::validate_strict_addition_text(&value, true)
            .map_err(|message| CliError::new("KAST_VERIFIED_ADD_FILE_SOURCE_INVALID", message))?;
        Ok(Self(value))
    }

    fn as_str(&self) -> &str {
        &self.0
    }

    fn readmit(value: String) -> Result<Self> {
        crate::agent::validate_strict_addition_text(&value, true)
            .map_err(|message| CliError::new("KAST_PLAN_INVALID", message))?;
        Ok(Self(value))
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddFilePlanStage {
    AwaitingApproval,
    Approved,
    RecoveryPrepared,
    ApplyAdmitted,
    AppliedUnverified,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
enum VerifiedAddFileOperation {
    AddFile,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredVerifiedAddFilePlan {
    schema_version: u32,
    workspace_root: String,
    plan_id: crate::agent::public_protocol::VerifiedAddFilePlanId,
    plan_version: VerifiedAddFilePlanVersion,
    target_path: VerifiedAddFileTarget,
    proposed_content: VerifiedAddFileSource,
    postimage_sha256: VerifiedAddFileSha256,
    planned_generation: u64,
    state: StoredVerifiedAddFileState,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum StoredVerifiedAddFileState {
    AwaitingApproval,
    ApplyOutcomeUnknown {
        authority: StoredVerifiedAddFileApplyInFlight,
    },
    RecoveryRequired { result: StoredRecoveryAddFileResult },
    ReconciliationRequired { result: StoredReconciliationAddFileResult },
    Terminal { result: StoredTerminalAddFileResult },
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddFileProgress {
    IntentAdmission,
    Planning,
    Revalidation,
    RecoveryPreparation,
    SourceApplication,
    WorkspacePublication,
    PsiAdmission,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddFileFailure {
    WorkspaceMismatch,
    PlanNotFound,
    StalePlanVersion,
    ApprovalRejected,
    TargetAlreadyExists,
    TargetGenerated,
    TargetAmbiguouslyOwned,
    TargetSymlinkEscape,
    TargetNotWritable,
    PackageOrDeclarationInvalid,
    PlanRevalidationFailed,
    VcsWritePromptRejected,
    SourceApplicationFailed,
    PublicationFailed,
    GenerationNotAdvanced,
    PsiNotAdmitted,
    Cancelled,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddFileRecoveryAction {
    DeleteCreatedTarget,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddFileReconciliationAction {
    InspectTarget,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedAddFilePublication {
    generation: u64,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum VerifiedAddFileDeclarationKind {
    Class,
    Interface,
    Object,
    EnumClass,
    AnnotationClass,
    Function,
    Property,
    TypeAlias,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedAddFileDeclarationIdentity {
    name: String,
    kind: VerifiedAddFileDeclarationKind,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedAddFileIdentity {
    target_path: String,
    package_name: String,
    declarations: Vec<VerifiedAddFileDeclarationIdentity>,
}
