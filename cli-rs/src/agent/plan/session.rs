#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum StoredPlanState {
    Planned,
    Terminal {
        receipt: Box<TerminalMutationReceipt>,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "outcome",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum TerminalMutationReceipt {
    Verified {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        files: Vec<VerifiedMutationFile>,
        diagnostics: VerifiedMutationDiagnostics,
        compiler_verification: Box<CompilerVerificationEvidence>,
        lease: MutationLeaseReceipt,
        schema_version: u32,
    },
    Rejected {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
    Conflicted {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
    RolledBack {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
}

impl TerminalMutationReceipt {
    fn verified(
        plan: &StoredPlan,
        files: Vec<VerifiedMutationFile>,
        diagnostics: VerifiedMutationDiagnostics,
        compiler_verification: CompilerVerificationEvidence,
        lease: MutationLeaseReceipt,
    ) -> Self {
        Self::Verified {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            files,
            diagnostics,
            compiler_verification: Box::new(compiler_verification),
            lease,
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn rejected(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::Rejected {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn conflicted(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::Conflicted {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn rolled_back(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::RolledBack {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }

    fn validate_for(&self, plan: &StoredPlan) -> Result<()> {
        let identity_matches = match self {
            Self::Verified {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            }
            | Self::Rejected {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            }
            | Self::Conflicted {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            }
            | Self::RolledBack {
                plan_id,
                recovery_id,
                workspace_root,
                operation,
                schema_version,
                ..
            } => {
                plan_id == &plan.plan_id.hyphenated().to_string()
                    && recovery_id == plan_id
                    && workspace_root == &plan.workspace_root
                    && operation == plan.operation.name()
                    && *schema_version == crate::SCHEMA_VERSION
            }
        };
        if !identity_matches {
            return Err(CliError::new(
                "KAST_PLAN_INVALID",
                "The stored terminal receipt identity does not match its plan.",
            ));
        }
        match self {
            Self::Verified {
                files,
                diagnostics,
                compiler_verification,
                lease,
                ..
            } => {
                let transitions = plan.operation.transitions(Path::new(&plan.workspace_root))?;
                let expected_files = transitions
                    .iter()
                    .map(ExactMutationTransition::verified_file)
                    .collect::<Vec<_>>();
                compiler_verification.validate_for(&plan.operation, &transitions)?;
                if files != &expected_files
                    || diagnostics
                        != &compiler_verification
                            .analysis
                            .post_diagnostics
                            .verified_diagnostics()
                    || lease.state != WorkspaceLeaseState::Released
                    || lease.release_receipt.released_at.trim().is_empty()
                {
                    return Err(CliError::new(
                        "KAST_PLAN_INVALID",
                        "The stored VERIFIED receipt does not bind exact files, diagnostics, compiler proof, and released lease evidence.",
                    ));
                }
            }
            Self::Rejected { reason, .. }
            | Self::Conflicted { reason, .. }
            | Self::RolledBack { reason, .. }
                if reason.trim().is_empty() =>
            {
                return Err(CliError::new(
                    "KAST_PLAN_INVALID",
                    "A non-verified terminal receipt needs one non-blank reason.",
                ));
            }
            Self::Rejected { .. } | Self::Conflicted { .. } | Self::RolledBack { .. } => {}
        }
        Ok(())
    }

    fn exit_code(&self) -> i32 {
        match self {
            Self::Verified { .. } => 0,
            Self::Rejected { .. }
            | Self::Conflicted { .. }
            | Self::RolledBack { .. } => 1,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(
    tag = "outcome",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
enum RecoveryRequiredReceipt {
    RecoveryRequired {
        plan_id: String,
        recovery_id: String,
        workspace_root: String,
        operation: String,
        reason: String,
        schema_version: u32,
    },
}

impl RecoveryRequiredReceipt {
    fn new(plan: &StoredPlan, reason: impl Into<String>) -> Self {
        Self::RecoveryRequired {
            plan_id: plan.plan_id.hyphenated().to_string(),
            recovery_id: plan.plan_id.hyphenated().to_string(),
            workspace_root: plan.workspace_root.clone(),
            operation: plan.operation.name().to_string(),
            reason: reason.into(),
            schema_version: crate::SCHEMA_VERSION,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RecoveryJournal {
    schema_version: u32,
    recovery_id: Uuid,
    plan_id: Uuid,
    workspace_root: String,
    transitions: Vec<ExactMutationTransition>,
    pre_diagnostics: CompilerDiagnosticSnapshot,
    mutation_attempt_id: Uuid,
    owned_scratch: Vec<MutationScratchAuthority>,
    state: RecoveryJournalState,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationScratchDirection {
    Forward,
    RestorePreimage,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum MutationScratchExpectation {
    Unused,
    Exact { image: AgentExactByteImage },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationScratchPathAuthority {
    relative_path: String,
    absolute_path: String,
    expectation: MutationScratchExpectation,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationScratchAuthority {
    owner_attempt_id: Uuid,
    transition_index: usize,
    transition_relative_path: String,
    target_file_path: String,
    direction: MutationScratchDirection,
    quarantine: MutationScratchPathAuthority,
    prepared: MutationScratchPathAuthority,
    prepared_cleanup: MutationScratchPathAuthority,
    quarantine_cleanup: MutationScratchPathAuthority,
}

impl MutationScratchAuthority {
    fn new(
        workspace_root: &Path,
        transition: &ExactMutationTransition,
        transition_index: usize,
        owner_attempt_id: Uuid,
        direction: MutationScratchDirection,
    ) -> Result<Self> {
        let target = Path::new(&transition.absolute_path);
        let parent = target.parent().ok_or_else(|| {
            CliError::new(
                "KAST_RECOVERY_INVALID",
                "An exact transition target has no scratch parent.",
            )
        })?;
        let suffix = format!("{}-{transition_index}", owner_attempt_id.hyphenated());
        let expectation = |role: &str| -> MutationScratchExpectation {
            match (direction, &transition.preimage, role) {
                (MutationScratchDirection::Forward, ExactMutationPreimage::Absent, "quarantine")
                | (
                    MutationScratchDirection::Forward,
                    ExactMutationPreimage::Absent,
                    "quarantine-cleanup",
                ) => MutationScratchExpectation::Unused,
                (
                    MutationScratchDirection::Forward,
                    ExactMutationPreimage::Present { image },
                    "quarantine" | "quarantine-cleanup",
                ) => MutationScratchExpectation::Exact {
                    image: image.clone(),
                },
                (
                    MutationScratchDirection::Forward,
                    _,
                    "prepared" | "prepared-cleanup",
                ) => MutationScratchExpectation::Exact {
                    image: transition.postimage.clone(),
                },
                (
                    MutationScratchDirection::RestorePreimage,
                    ExactMutationPreimage::Absent,
                    "quarantine" | "quarantine-cleanup",
                ) => MutationScratchExpectation::Exact {
                    image: transition.postimage.clone(),
                },
                (
                    MutationScratchDirection::RestorePreimage,
                    ExactMutationPreimage::Absent,
                    "prepared" | "prepared-cleanup",
                ) => MutationScratchExpectation::Exact {
                    image: AgentExactByteImage::from_bytes(&[]),
                },
                (
                    MutationScratchDirection::RestorePreimage,
                    ExactMutationPreimage::Present { image },
                    "prepared" | "prepared-cleanup",
                ) => MutationScratchExpectation::Exact {
                    image: image.clone(),
                },
                (
                    MutationScratchDirection::RestorePreimage,
                    ExactMutationPreimage::Present { .. },
                    "quarantine" | "quarantine-cleanup",
                ) => MutationScratchExpectation::Exact {
                    image: transition.postimage.clone(),
                },
                _ => unreachable!("closed scratch role"),
            }
        };
        let path = |name: String, expectation: MutationScratchExpectation| -> Result<_> {
            let absolute = parent.join(name);
            let relative = absolute.strip_prefix(workspace_root).map_err(|_| {
                CliError::new(
                    "KAST_RECOVERY_INVALID",
                    "A mutation scratch path escaped its exact workspace root.",
                )
            })?;
            Ok(MutationScratchPathAuthority {
                relative_path: relative.to_string_lossy().into_owned(),
                absolute_path: absolute.to_string_lossy().into_owned(),
                expectation,
            })
        };
        Ok(Self {
            owner_attempt_id,
            transition_index,
            transition_relative_path: transition.relative_path.clone(),
            target_file_path: transition.absolute_path.clone(),
            direction,
            quarantine: path(
                format!(".kast-quarantine-{suffix}"),
                expectation("quarantine"),
            )?,
            prepared: path(
                format!(".kast-prepared-{suffix}.tmp"),
                expectation("prepared"),
            )?,
            prepared_cleanup: path(
                format!(".kast-cleanup-{suffix}-prepared"),
                expectation("prepared-cleanup"),
            )?,
            quarantine_cleanup: path(
                format!(".kast-cleanup-{suffix}-quarantine"),
                expectation("quarantine-cleanup"),
            )?,
        })
    }

    fn wire_set(&self) -> AgentMutationScratchSet {
        AgentMutationScratchSet {
            target_file_path: self.target_file_path.clone(),
            quarantine_path: self.quarantine.absolute_path.clone(),
            prepared_path: self.prepared.absolute_path.clone(),
            prepared_cleanup_path: self.prepared_cleanup.absolute_path.clone(),
            quarantine_cleanup_path: self.quarantine_cleanup.absolute_path.clone(),
        }
    }

    fn paths(&self) -> [&MutationScratchPathAuthority; 4] {
        [
            &self.quarantine,
            &self.prepared,
            &self.prepared_cleanup,
            &self.quarantine_cleanup,
        ]
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    rename_all = "camelCase",
    deny_unknown_fields
)]
struct ExactMutationTransition {
    relative_path: String,
    absolute_path: String,
    preimage: ExactMutationPreimage,
    postimage: AgentExactByteImage,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum ExactMutationPreimage {
    Absent,
    Present {
        image: AgentExactByteImage,
    },
}

impl ExactMutationTransition {
    fn matches_pre(&self, observation: &RawExactFileObservation) -> bool {
        match (&self.preimage, observation) {
            (
                ExactMutationPreimage::Absent,
                RawExactFileObservation::Absent { file_path },
            ) => file_path == &self.relative_path,
            (
                ExactMutationPreimage::Present { image: expected },
                RawExactFileObservation::Present { file_path, image },
            ) => file_path == &self.relative_path && image == expected,
            _ => false,
        }
    }

    fn matches_post(&self, observation: &RawExactFileObservation) -> bool {
        matches!(
            observation,
            RawExactFileObservation::Present { file_path, image }
                if file_path == &self.relative_path && image == &self.postimage
        )
    }

    fn verified_file(&self) -> VerifiedMutationFile {
        VerifiedMutationFile {
            path: self.absolute_path.clone(),
            sha256: self.postimage.sha256().to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(
    tag = "phase",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum RecoveryJournalState {
    Prepared,
    Writing {
        completed_transition_count: usize,
    },
    WritesApplied,
    Refreshed,
    DiagnosticsVerified,
    SemanticVerified {
        compiler_verification: Box<CompilerVerificationEvidence>,
    },
    DurableVerifiedEvidence {
        files: Vec<VerifiedMutationFile>,
        diagnostics: VerifiedMutationDiagnostics,
        compiler_verification: Box<CompilerVerificationEvidence>,
    },
    VerifiedEvidence {
        files: Vec<VerifiedMutationFile>,
        diagnostics: VerifiedMutationDiagnostics,
        compiler_verification: Box<CompilerVerificationEvidence>,
        lease: MutationLeaseReceipt,
    },
}

impl RecoveryJournal {
    fn prepared(
        plan: &StoredPlan,
        transitions: Vec<ExactMutationTransition>,
        pre_diagnostics: CompilerDiagnosticSnapshot,
    ) -> Result<Self> {
        let root = Path::new(&plan.workspace_root);
        validate_sorted_transition_set(root, &transitions)?;
        let mutation_attempt_id = Uuid::new_v4();
        let owned_scratch = transitions
            .iter()
            .enumerate()
            .map(|(index, transition)| {
                MutationScratchAuthority::new(
                    root,
                    transition,
                    index,
                    mutation_attempt_id,
                    MutationScratchDirection::Forward,
                )
            })
            .collect::<Result<Vec<_>>>()?;
        Ok(Self {
            schema_version: RECOVERY_SCHEMA_VERSION,
            recovery_id: plan.plan_id,
            plan_id: plan.plan_id,
            workspace_root: plan.workspace_root.clone(),
            transitions,
            pre_diagnostics,
            mutation_attempt_id,
            owned_scratch,
            state: RecoveryJournalState::Prepared,
        })
    }

    fn validate(&self, recovery_id: Uuid, plan: &StoredPlan) -> Result<()> {
        if self.schema_version != RECOVERY_SCHEMA_VERSION
            || self.recovery_id != recovery_id
            || self.plan_id != plan.plan_id
            || self.workspace_root != plan.workspace_root
            || self.mutation_attempt_id.get_version() != Some(Version::Random)
        {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The recovery journal identity does not match its change plan and exact root.",
            ));
        }
        validate_sorted_transition_set(Path::new(&self.workspace_root), &self.transitions)?;
        let expected = plan.operation.transitions(Path::new(&self.workspace_root))?;
        if self.transitions != expected {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The recovery journal transitions do not match the persisted mutation authority.",
            ));
        }
        let expected_scratch = self
            .owned_scratch
            .iter()
            .map(|scratch| {
                let transition = self.transitions.get(scratch.transition_index).ok_or_else(|| {
                    CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Mutation scratch referenced an unknown exact transition.",
                    )
                })?;
                MutationScratchAuthority::new(
                    Path::new(&self.workspace_root),
                    transition,
                    scratch.transition_index,
                    scratch.owner_attempt_id,
                    scratch.direction,
                )
            })
            .collect::<Result<Vec<_>>>()?;
        let mut scratch_paths = self
            .owned_scratch
            .iter()
            .flat_map(|scratch| scratch.paths())
            .map(|path| path.absolute_path.as_str())
            .collect::<Vec<_>>();
        scratch_paths.sort_unstable();
        let mut scratch_transitions = BTreeSet::new();
        if self.owned_scratch != expected_scratch
            || self.owned_scratch.iter().any(|scratch| {
                scratch.owner_attempt_id.get_version() != Some(Version::Random)
                    || !scratch_transitions.insert(scratch.transition_index)
            })
            || scratch_paths
                .windows(2)
                .any(|window| window[0] >= window[1])
        {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The journal-owned scratch roles do not bind their exact transition images and paths.",
            ));
        }
        let expected_pre_files = self
            .transitions
            .iter()
            .filter_map(|transition| match &transition.preimage {
                ExactMutationPreimage::Absent => None,
                ExactMutationPreimage::Present { image } => Some(CompilerDiagnosticFileHash {
                    file_path: transition.absolute_path.clone(),
                    sha256: image.sha256().to_string(),
                }),
            })
            .collect::<Vec<_>>();
        self.pre_diagnostics
            .validate_for_files(&expected_pre_files)?;
        match &self.state {
            RecoveryJournalState::Writing {
                completed_transition_count,
            } if *completed_transition_count == 0
                || *completed_transition_count > self.transitions.len() =>
            {
                return Err(CliError::new(
                    "KAST_RECOVERY_INVALID",
                    "The recovery write checkpoint exceeded its exact transition set.",
                ));
            }
            RecoveryJournalState::SemanticVerified {
                compiler_verification,
            } => compiler_verification.validate_for(&plan.operation, &self.transitions)?,
            RecoveryJournalState::DurableVerifiedEvidence {
                files,
                diagnostics,
                compiler_verification,
            }
            | RecoveryJournalState::VerifiedEvidence {
                files,
                diagnostics,
                compiler_verification,
                ..
            } => {
                if !self.owned_scratch.is_empty() {
                    return Err(CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Verified recovery evidence cannot retain journal-owned mutation scratch.",
                    ));
                }
                compiler_verification.validate_for(&plan.operation, &self.transitions)?;
                let expected_files = self
                    .transitions
                    .iter()
                    .map(ExactMutationTransition::verified_file)
                    .collect::<Vec<_>>();
                if files != &expected_files
                    || diagnostics
                        != &compiler_verification
                            .analysis
                            .post_diagnostics
                            .verified_diagnostics()
                {
                    return Err(CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Durable verified evidence does not bind its exact transitions.",
                    ));
                }
                if let RecoveryJournalState::VerifiedEvidence { lease, .. } = &self.state
                    && (lease.state != WorkspaceLeaseState::Released
                        || lease.release_receipt.released_at.trim().is_empty())
                {
                    return Err(CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Released verified evidence has no valid lease release receipt.",
                    ));
                }
            }
            RecoveryJournalState::Prepared
            | RecoveryJournalState::Writing { .. }
            | RecoveryJournalState::WritesApplied
            | RecoveryJournalState::Refreshed
            | RecoveryJournalState::DiagnosticsVerified => {}
        }
        Ok(())
    }
}

fn validate_sorted_transition_set(
    workspace_root: &Path,
    transitions: &[ExactMutationTransition],
) -> Result<()> {
    if !workspace_root.is_absolute() || transitions.is_empty() {
        return Err(CliError::new(
            "KAST_RECOVERY_INVALID",
            "The mutation transition set needs one exact-root transition.",
        ));
    }
    let mut previous: Option<&str> = None;
    for transition in transitions {
        let relative = Path::new(&transition.relative_path);
        let absolute = Path::new(&transition.absolute_path);
        if transition.relative_path.is_empty()
            || relative.is_absolute()
            || !relative
                .components()
                .all(|component| matches!(component, std::path::Component::Normal(_)))
            || !absolute.is_absolute()
            || !absolute.starts_with(workspace_root)
            || workspace_root.join(relative) != absolute
            || previous.is_some_and(|path| path >= transition.relative_path.as_str())
            || transition.postimage.validate().is_err()
            || matches!(
                &transition.preimage,
                ExactMutationPreimage::Present { image }
                    if image.validate().is_err() || image == &transition.postimage
            )
        {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The mutation transition set is not unique, path-sorted, root-bound, and byte-exact.",
            ));
        }
        previous = Some(&transition.relative_path);
    }
    Ok(())
}

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

impl RecoveryJournal {
    fn rotate_mutation_attempt(&mut self) {
        self.mutation_attempt_id = Uuid::new_v4();
    }

    fn remove_scratch(&mut self, authority: &MutationScratchAuthority) -> Result<()> {
        let position = self
            .owned_scratch
            .iter()
            .position(|candidate| candidate == authority)
            .ok_or_else(|| {
                CliError::new(
                    "KAST_RECOVERY_INVALID",
                    "Cannot clear mutation scratch that the journal does not own.",
                )
            })?;
        self.owned_scratch.remove(position);
        Ok(())
    }

    fn validate_backend_recovery_details(&self, error: &CliError) -> Result<()> {
        if let Some(reason) = error.details.get(BACKEND_RECOVERY_DETAILS_INVALID) {
            return Err(CliError::new(
                "KAST_BACKEND_RECOVERY_DETAILS_INVALID",
                format!("The backend returned malformed recovery path details: {reason}"),
            ));
        }
        let indexed = error
            .details
            .keys()
            .filter(|key| key.starts_with("recoveryFilePath."))
            .collect::<Vec<_>>();
        let Some(raw_count) = error.details.get("recoveryFilePathCount") else {
            return if indexed.is_empty() {
                Ok(())
            } else {
                Err(CliError::new(
                    "KAST_BACKEND_RECOVERY_DETAILS_INVALID",
                    "The backend recovery path indexes had no count.",
                ))
            };
        };
        let count = raw_count.parse::<usize>().ok().filter(|count| {
            *count > 0 && count.to_string() == *raw_count && indexed.len() == *count
        });
        let Some(count) = count else {
            return Err(CliError::new(
                "KAST_BACKEND_RECOVERY_DETAILS_INVALID",
                "The backend recovery path count was noncanonical or incomplete.",
            ));
        };
        let owned = self
            .owned_scratch
            .iter()
            .flat_map(|scratch| scratch.paths())
            .map(|path| path.absolute_path.as_str())
            .collect::<BTreeSet<_>>();
        let mut reported = BTreeSet::new();
        for index in 0..count {
            let key = format!("recoveryFilePath.{index}");
            let path = error.details.get(&key).ok_or_else(|| {
                CliError::new(
                    "KAST_BACKEND_RECOVERY_DETAILS_INVALID",
                    "The backend recovery path indexes were not contiguous.",
                )
            })?;
            if !owned.contains(path.as_str()) || !reported.insert(path.as_str()) {
                return Err(CliError::new(
                    "KAST_BACKEND_RECOVERY_DETAILS_INVALID",
                    "The backend recovery paths were not a unique subset of journal-owned scratch.",
                ));
            }
        }
        Ok(())
    }

    fn active_scratch(&self, transition_index: usize) -> Result<&MutationScratchAuthority> {
        self.owned_scratch
            .iter()
            .find(|scratch| {
                scratch.owner_attempt_id == self.mutation_attempt_id
                    && scratch.transition_index == transition_index
            })
            .ok_or_else(|| {
                CliError::new(
                    "KAST_RECOVERY_INVALID",
                    "The active mutation attempt has no scratch authority for its transition.",
                )
            })
    }

    fn arm_restore_scratch(&mut self, transition_index: usize) -> Result<()> {
        if self
            .owned_scratch
            .iter()
            .any(|scratch| scratch.transition_index == transition_index)
        {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "Cannot arm a restore write while its prior scratch authority remains owned.",
            ));
        }
        let transition = self.transitions.get(transition_index).ok_or_else(|| {
            CliError::new(
                "KAST_RECOVERY_INVALID",
                "Cannot arm scratch for an unknown exact transition.",
            )
        })?;
        self.owned_scratch.push(MutationScratchAuthority::new(
            Path::new(&self.workspace_root),
            transition,
            transition_index,
            self.mutation_attempt_id,
            MutationScratchDirection::RestorePreimage,
        )?);
        self.owned_scratch.sort_by(|left, right| {
            left.target_file_path
                .cmp(&right.target_file_path)
                .then_with(|| left.quarantine.absolute_path.cmp(&right.quarantine.absolute_path))
        });
        Ok(())
    }

    fn inspect_query(&self) -> Result<MutationScratchInspectQuery> {
        let root = Path::new(&self.workspace_root);
        let mut parents = self
            .transitions
            .iter()
            .map(|transition| {
                let parent = Path::new(&transition.absolute_path)
                    .parent()
                    .expect("validated transition target has a parent");
                let relative = parent.strip_prefix(root).map_err(|_| {
                    CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "A transition parent escaped the exact workspace root.",
                    )
                })?;
                Ok(if relative.as_os_str().is_empty() {
                    ".".to_string()
                } else {
                    relative.to_string_lossy().into_owned()
                })
            })
            .collect::<Result<Vec<_>>>()?;
        parents.sort();
        parents.dedup();
        let mut owned = self
            .owned_scratch
            .iter()
            .map(MutationScratchAuthority::wire_set)
            .collect::<Vec<_>>();
        owned.sort_by(|left, right| {
            left.target_file_path
                .cmp(&right.target_file_path)
                .then_with(|| left.quarantine_path.cmp(&right.quarantine_path))
                .then_with(|| left.prepared_path.cmp(&right.prepared_path))
                .then_with(|| left.prepared_cleanup_path.cmp(&right.prepared_cleanup_path))
                .then_with(|| {
                    left.quarantine_cleanup_path
                        .cmp(&right.quarantine_cleanup_path)
                })
        });
        Ok(MutationScratchInspectQuery {
            mutation_attempt_id: self.mutation_attempt_id.hyphenated().to_string(),
            workspace_relative_parent_paths: parents,
            owned_scratch_sets: owned,
        })
    }
}

impl MutationScratchInspectResult {
    fn validate_for(&self, journal: &RecoveryJournal) -> Result<()> {
        let expected_attempt = journal.mutation_attempt_id.hyphenated().to_string();
        let mut expected_owned = BTreeMap::new();
        for scratch in &journal.owned_scratch {
            for (path, role) in [
                (&scratch.quarantine, MutationScratchRole::Quarantine),
                (&scratch.prepared, MutationScratchRole::Prepared),
                (
                    &scratch.prepared_cleanup,
                    MutationScratchRole::PreparedCleanup,
                ),
                (
                    &scratch.quarantine_cleanup,
                    MutationScratchRole::QuarantineCleanup,
                ),
            ] {
                if expected_owned
                    .insert(path.absolute_path.as_str(), (role, &path.expectation))
                    .is_some()
                {
                    return Err(CliError::new(
                        "KAST_MUTATION_SCRATCH_INVALID",
                        "Journal-owned mutation scratch repeated a path.",
                    ));
                }
            }
        }
        if self.schema_version != crate::SCHEMA_VERSION
            || self.mutation_attempt_id != expected_attempt
            || self
                .observations
                .windows(2)
                .any(|window| window[0].file_path >= window[1].file_path)
        {
            return Err(CliError::new(
                "KAST_MUTATION_SCRATCH_INVALID",
                "Mutation scratch inspection did not bind its attempt and sorted path set.",
            ));
        }
        let mut observed_owned = BTreeSet::new();
        for observation in &self.observations {
            let expected = expected_owned.get(observation.file_path.as_str());
            let valid_state = match observation.state {
                MutationScratchState::Present => observation
                    .sha256
                    .as_deref()
                    .is_some_and(is_lowercase_session_sha256),
                MutationScratchState::Absent | MutationScratchState::Unsafe => {
                    observation.sha256.is_none()
                }
            };
            let valid_ownership = match observation.ownership {
                MutationScratchOwnership::Owned => expected.is_some_and(|(role, _)| {
                    *role == observation.role
                        && observed_owned.insert(observation.file_path.as_str())
                }),
                MutationScratchOwnership::Unowned => {
                    expected.is_none()
                        && observation.role == MutationScratchRole::UnownedInternal
                        && observation.state != MutationScratchState::Absent
                }
            };
            let valid_expectation = observation.ownership != MutationScratchOwnership::Owned
                || observation.state != MutationScratchState::Present
                || expected.is_some_and(|(_, expectation)| match expectation {
                    MutationScratchExpectation::Unused => false,
                    MutationScratchExpectation::Exact { image } => {
                        observation.sha256.as_deref() == Some(image.sha256())
                    }
                });
            if !valid_state || !valid_ownership || !valid_expectation {
                return Err(CliError::new(
                    "KAST_MUTATION_SCRATCH_INVALID",
                    "Mutation scratch inspection returned an invalid role, ownership, state, or journal image.",
                ));
            }
        }
        if observed_owned.len() != expected_owned.len() {
            return Err(CliError::new(
                "KAST_MUTATION_SCRATCH_INVALID",
                "Mutation scratch inspection omitted a journal-owned role.",
            ));
        }
        Ok(())
    }

    fn has_blocker(&self) -> bool {
        self.observations.iter().any(|observation| {
            observation.ownership == MutationScratchOwnership::Unowned
                || observation.state == MutationScratchState::Unsafe
        })
    }

    fn owned_present(&self) -> bool {
        self.observations.iter().any(|observation| {
            observation.ownership == MutationScratchOwnership::Owned
                && observation.state == MutationScratchState::Present
        })
    }

    fn scratch_is_present(&self, scratch: &MutationScratchAuthority) -> bool {
        scratch.paths().iter().any(|path| {
            self.observations.iter().any(|observation| {
                observation.file_path == path.absolute_path
                    && observation.ownership == MutationScratchOwnership::Owned
                    && observation.state == MutationScratchState::Present
            })
        })
    }

    fn scratch_is_absent(&self, scratch: &MutationScratchAuthority) -> bool {
        scratch.paths().iter().all(|path| {
            self.observations.iter().any(|observation| {
                observation.file_path == path.absolute_path
                    && observation.ownership == MutationScratchOwnership::Owned
                    && observation.state == MutationScratchState::Absent
            })
        })
    }
}

impl MutationScratchRecoveryResult {
    fn validate_restore(
        &self,
        journal: &RecoveryJournal,
        transition: &ExactMutationTransition,
        scratch: &MutationScratchAuthority,
    ) -> Result<()> {
        let expected_target = match &transition.preimage {
            ExactMutationPreimage::Absent => (MutationScratchTargetState::Absent, None),
            ExactMutationPreimage::Present { image } => {
                (MutationScratchTargetState::Present, Some(image.sha256()))
            }
        };
        let expected_observations = [
            (&scratch.quarantine, MutationScratchRole::Quarantine),
            (&scratch.prepared, MutationScratchRole::Prepared),
            (
                &scratch.prepared_cleanup,
                MutationScratchRole::PreparedCleanup,
            ),
            (
                &scratch.quarantine_cleanup,
                MutationScratchRole::QuarantineCleanup,
            ),
        ];
        let observations_match = self.scratch_observations.len() == expected_observations.len()
            && self
                .scratch_observations
                .iter()
                .zip(expected_observations)
                .all(|(observation, (path, role))| {
                    observation.file_path == path.absolute_path
                        && observation.ownership == MutationScratchOwnership::Owned
                        && observation.role == role
                        && observation.state == MutationScratchState::Absent
                        && observation.sha256.is_none()
                });
        if self.schema_version != crate::SCHEMA_VERSION
            || self.mutation_attempt_id != journal.mutation_attempt_id.hyphenated().to_string()
            || self.action != MutationScratchRecoveryAction::RestorePreimage
            || self.outcome != MutationScratchRecoveryOutcome::RestoredPreimage
            || self.target_state != expected_target.0
            || self.target_sha256.as_deref() != expected_target.1
            || !observations_match
        {
            return Err(CliError::new(
                "KAST_MUTATION_SCRATCH_RECOVERY_INVALID",
                "Mutation scratch recovery did not prove the exact preimage and four absent owned roles.",
            ));
        }
        Ok(())
    }
}

fn inspect_mutation_scratch(
    workspace_root: &Path,
    journal: &RecoveryJournal,
    lease_id: AgentWorkspaceLeaseId,
) -> Result<MutationScratchInspectResult> {
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/inspect-mutation-scratch",
        serde_json::to_value(journal.inspect_query()?)?,
        LeasedRawOperation::ScratchRecovery,
    )?;
    let result: MutationScratchInspectResult =
        parse_closed_raw(raw, "mutation scratch inspection")?;
    result.validate_for(journal)?;
    Ok(result)
}

fn restore_owned_mutation_scratch(
    workspace_root: &Path,
    journal: &RecoveryJournal,
    scratch: &MutationScratchAuthority,
    lease_id: AgentWorkspaceLeaseId,
) -> Result<()> {
    let transition = journal
        .transitions
        .get(scratch.transition_index)
        .ok_or_else(|| {
            CliError::new(
                "KAST_RECOVERY_INVALID",
                "Mutation scratch recovery referenced an unknown exact transition.",
            )
        })?;
    let query = MutationScratchRecoveryQuery {
        mutation_attempt_id: journal.mutation_attempt_id.hyphenated().to_string(),
        action: MutationScratchRecoveryAction::RestorePreimage,
        scratch_direction: scratch.direction,
        target_file_path: transition.absolute_path.clone(),
        preimage: transition.preimage.clone(),
        postimage: transition.postimage.clone(),
        scratch: scratch.wire_set(),
    };
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/recover-mutation-scratch",
        serde_json::to_value(query)?,
        LeasedRawOperation::ScratchRecovery,
    )?;
    let result: MutationScratchRecoveryResult =
        parse_closed_raw(raw, "mutation scratch recovery")?;
    result.validate_restore(journal, transition, scratch)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationPostconditionQuery {
    authority: AgentMutationPostconditionAuthority,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationPostconditionStatus {
    Verified,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationPostconditionOperation {
    Rename,
    Replacement,
    AddFile,
    AddDeclaration,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationPostconditionPostimage {
    file_path: String,
    sha256: String,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationPostconditionResult {
    status: MutationPostconditionStatus,
    operation: MutationPostconditionOperation,
    current_generation: u64,
    postimages: Vec<MutationPostconditionPostimage>,
    evidence: AgentMutationPostconditionEvidence,
    schema_version: u32,
}

impl MutationPostconditionResult {
    fn validate_for(
        &self,
        operation: &StoredOperation,
        transitions: &[ExactMutationTransition],
    ) -> Result<()> {
        let expected_operation = match operation {
            StoredOperation::Rename { .. } => MutationPostconditionOperation::Rename,
            StoredOperation::Replace { .. } => MutationPostconditionOperation::Replacement,
            StoredOperation::AddFile { .. } => MutationPostconditionOperation::AddFile,
            StoredOperation::AddDeclaration { .. } => MutationPostconditionOperation::AddDeclaration,
        };
        let expected_postimages = transitions
            .iter()
            .map(|transition| MutationPostconditionPostimage {
                file_path: transition.absolute_path.clone(),
                sha256: transition.postimage.sha256().to_string(),
            })
            .collect::<Vec<_>>();
        if self.status != MutationPostconditionStatus::Verified
            || self.operation != expected_operation
            || self.schema_version != crate::SCHEMA_VERSION
            || self.postimages != expected_postimages
            || self.current_generation > i64::MAX as u64
            || self.current_generation < operation.minimum_postcondition_generation()
            || operation
                .validate_postcondition_evidence(&self.evidence)
                .is_err()
        {
            return Err(CliError::new(
                "KAST_MUTATION_POSTCONDITION_INVALID",
                "Compiler postcondition evidence did not bind the stored operation and every exact postimage.",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawApplyEditsResult {
    applied: Vec<Value>,
    affected_files: Vec<String>,
    created_files: Vec<String>,
    deleted_files: Vec<String>,
    schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedMutationFile {
    path: String,
    sha256: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedMutationDiagnostics {
    error: usize,
    warning: usize,
    info: usize,
    total: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationLeaseReceipt {
    state: WorkspaceLeaseState,
    ownership: WorkspaceLeaseOwnership,
    release_receipt: WorkspaceLeaseReleaseReceipt,
}

struct OwnedMutationLease {
    lease_id: AgentWorkspaceLeaseId,
    workspace_root: PathBuf,
}

impl OwnedMutationLease {
    fn acquire(workspace_root: &Path) -> Result<Self> {
        let acquired = runtime::workspace_lease_acquire_process_owned(AgentLeaseAcquireArgs {
            workspace_root: workspace_root.to_path_buf(),
            wait_timeout_ms: crate::cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS,
        })?;
        if acquired.state != WorkspaceLeaseState::Ready {
            return Err(CliError::new(
                "WORKSPACE_LEASE_NOT_READY",
                "The internally acquired mutation lease did not reach READY.",
            ));
        }
        let lease_id = acquired.lease_id.parse().map_err(|message: String| {
            CliError::new(
                "WORKSPACE_LEASE_ID_INVALID",
                format!("The internally acquired mutation lease id was invalid: {message}"),
            )
        })?;
        Ok(Self {
            lease_id,
            workspace_root: workspace_root.to_path_buf(),
        })
    }

    fn id(&self) -> AgentWorkspaceLeaseId {
        self.lease_id.clone()
    }

    fn release(self) -> Result<MutationLeaseReceipt> {
        let released = runtime::workspace_lease_release(AgentLeaseAccessArgs {
            lease_id: self.lease_id,
            workspace_root: self.workspace_root,
        })?;
        if released.state != WorkspaceLeaseState::Released {
            return Err(CliError::new(
                "WORKSPACE_LEASE_RELEASE_INCOMPLETE",
                "The mutation lease did not reach RELEASED.",
            ));
        }
        let release_receipt = released.release_receipt.ok_or_else(|| {
            CliError::new(
                "WORKSPACE_LEASE_RELEASE_RECEIPT_MISSING",
                "The released mutation lease returned no release receipt.",
            )
        })?;
        Ok(MutationLeaseReceipt {
            state: released.state,
            ownership: released.ownership,
            release_receipt,
        })
    }
}

#[cfg(test)]
mod mutation_session_contract_tests {
    use super::*;

    #[test]
    fn exact_observer_is_closed_and_binds_the_requested_relative_path() {
        let unknown = serde_json::json!({
            "type": "ABSENT",
            "filePath": "src/New.kt",
            "unexpected": true,
        });
        assert!(serde_json::from_value::<RawExactFileObservation>(unknown).is_err());

        let wrong_path = serde_json::json!({
            "type": "ABSENT",
            "filePath": "src/Other.kt",
        });
        let observation = serde_json::from_value::<RawExactFileObservation>(wrong_path)
            .expect("closed observer response");
        assert!(observation.validate_for("src/New.kt").is_err());
    }

    #[test]
    fn mutation_transition_set_requires_deterministic_unique_paths() {
        let postimage = AgentExactByteImage::from_bytes(b"a");
        let transitions = vec![
            ExactMutationTransition {
                relative_path: "src/Z.kt".to_string(),
                absolute_path: "/workspace/src/Z.kt".to_string(),
                preimage: ExactMutationPreimage::Absent,
                postimage: postimage.clone(),
            },
            ExactMutationTransition {
                relative_path: "src/A.kt".to_string(),
                absolute_path: "/workspace/src/A.kt".to_string(),
                preimage: ExactMutationPreimage::Absent,
                postimage,
            },
        ];
        assert!(validate_sorted_transition_set(Path::new("/workspace"), &transitions).is_err());
    }
}
