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
