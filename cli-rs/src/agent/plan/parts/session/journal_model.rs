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
        lease: Box<MutationLeaseReceipt>,
    },
}
