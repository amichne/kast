#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "type",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum RawExactFileObservation {
    Absent {
        file_path: String,
    },
    Present {
        file_path: String,
        image: AgentExactByteImage,
    },
}

impl RawExactFileObservation {
    fn validate_for(&self, requested_relative_path: &str) -> Result<()> {
        let (file_path, image) = match self {
            Self::Absent { file_path } => (file_path, None),
            Self::Present { file_path, image } => (file_path, Some(image)),
        };
        let path = Path::new(file_path);
        if file_path != requested_relative_path
            || path.is_absolute()
            || file_path.is_empty()
            || !path
                .components()
                .all(|component| matches!(component, std::path::Component::Normal(_)))
            || image.is_some_and(|image| image.validate().is_err())
        {
            return Err(CliError::new(
                "KAST_EXACT_OBSERVATION_INVALID",
                "The raw exact-file observation did not bind the requested canonical relative path and byte image.",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationScratchInspectQuery {
    mutation_attempt_id: String,
    workspace_relative_parent_paths: Vec<String>,
    owned_scratch_sets: Vec<AgentMutationScratchSet>,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationScratchOwnership {
    Owned,
    Unowned,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationScratchRole {
    Quarantine,
    Prepared,
    PreparedCleanup,
    QuarantineCleanup,
    UnownedInternal,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationScratchState {
    Absent,
    Present,
    Unsafe,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationScratchObservation {
    file_path: String,
    ownership: MutationScratchOwnership,
    role: MutationScratchRole,
    state: MutationScratchState,
    sha256: Option<String>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationScratchInspectResult {
    mutation_attempt_id: String,
    observations: Vec<MutationScratchObservation>,
    schema_version: u32,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationScratchRecoveryAction {
    RestorePreimage,
    FinalizePostimage,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationScratchRecoveryQuery {
    mutation_attempt_id: String,
    action: MutationScratchRecoveryAction,
    scratch_direction: MutationScratchDirection,
    target_file_path: String,
    preimage: ExactMutationPreimage,
    postimage: AgentExactByteImage,
    scratch: AgentMutationScratchSet,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationScratchRecoveryOutcome {
    RestoredPreimage,
    FinalizedPostimage,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationScratchTargetState {
    Absent,
    Present,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationScratchRecoveryResult {
    mutation_attempt_id: String,
    action: MutationScratchRecoveryAction,
    outcome: MutationScratchRecoveryOutcome,
    target_state: MutationScratchTargetState,
    target_sha256: Option<String>,
    scratch_observations: Vec<MutationScratchObservation>,
    schema_version: u32,
}
