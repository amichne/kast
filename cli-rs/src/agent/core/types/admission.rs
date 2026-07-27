#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentRefreshResult {
    refreshed_files: Vec<String>,
    removed_files: Vec<String>,
    full_refresh: bool,
    file_statuses: Vec<AgentSemanticAdmissionStatus>,
    #[serde(flatten)]
    summary: AgentSemanticAnalysisSummary,
    removed_file_count: usize,
    attempt_count: usize,
    #[serde(rename = "elapsedMillis")]
    _elapsed_millis: u64,
    schema_version: u32,
}

impl AgentRefreshResult {
    fn validated_summary(
        self,
        requested_file_paths: &[String],
    ) -> Option<AgentSemanticAnalysisSummary> {
        let requested_file_paths = requested_file_paths
            .iter()
            .map(|file_path| normalized_absolute_path(file_path))
            .collect::<Option<Vec<_>>>()?;
        let status_file_paths = self
            .file_statuses
            .iter()
            .map(|status| normalized_absolute_path(&status.file_path))
            .collect::<Option<Vec<_>>>()?;
        let refreshed_file_paths = self
            .refreshed_files
            .iter()
            .map(|file_path| normalized_absolute_path(file_path))
            .collect::<Option<Vec<_>>>()?;
        let removed_file_paths = self
            .removed_files
            .iter()
            .map(|file_path| normalized_absolute_path(file_path))
            .collect::<Option<Vec<_>>>()?;

        if self.attempt_count == 0
            || self.schema_version != SCHEMA_VERSION
            || self.full_refresh != requested_file_paths.is_empty()
        {
            return None;
        }
        if self.full_refresh {
            let is_empty_complete_refresh = self.file_statuses.is_empty()
                && self.refreshed_files.is_empty()
                && self.removed_files.is_empty()
                && self.summary.semantic_outcome == AgentSemanticAnalysisOutcome::Complete
                && self.summary.requested_file_count == 0
                && self.summary.analyzed_file_count == 0
                && self.summary.skipped_file_count == 0
                && self.removed_file_count == 0;
            return is_empty_complete_refresh.then_some(self.summary);
        }

        if status_file_paths != requested_file_paths
            || self
                .file_statuses
                .iter()
                .any(|status| !status.is_valid())
        {
            return None;
        }
        let admitted_file_paths = self
            .file_statuses
            .iter()
            .filter(|status| status.is_admitted())
            .map(|status| normalized_absolute_path(&status.file_path))
            .collect::<Option<Vec<_>>>()?;
        let status_removed_file_paths = self
            .file_statuses
            .iter()
            .filter(|status| status.is_removed())
            .map(|status| normalized_absolute_path(&status.file_path))
            .collect::<Option<Vec<_>>>()?;
        let requested_file_count = self
            .file_statuses
            .iter()
            .filter(|status| !status.is_removed())
            .count();
        let analyzed_file_count = admitted_file_paths.len();
        let skipped_file_count = requested_file_count.checked_sub(analyzed_file_count)?;
        let expected_outcome = if skipped_file_count == 0 {
            AgentSemanticAnalysisOutcome::Complete
        } else {
            AgentSemanticAnalysisOutcome::Incomplete
        };

        if refreshed_file_paths != admitted_file_paths
            || removed_file_paths != status_removed_file_paths
            || self.summary.semantic_outcome != expected_outcome
            || self.summary.requested_file_count != requested_file_count
            || self.summary.analyzed_file_count != analyzed_file_count
            || self.summary.skipped_file_count != skipped_file_count
            || self.removed_file_count != status_removed_file_paths.len()
        {
            return None;
        }
        Some(self.summary)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentSemanticAdmissionStatus {
    file_path: String,
    file_system_discovery: AgentFileSystemDiscoveryState,
    source_module_ownership: AgentSourceModuleOwnershipState,
    index_admission: AgentIndexAdmissionState,
    analysis_availability: AgentAnalysisAvailabilityState,
    analysis_status: Option<AgentFileAnalysisStatus>,
}

impl AgentSemanticAdmissionStatus {
    fn is_admitted(&self) -> bool {
        self.analysis_status
            .as_ref()
            .is_some_and(|status| status.state == AgentFileAnalysisState::Analyzed)
    }

    fn is_removed(&self) -> bool {
        self.file_system_discovery == AgentFileSystemDiscoveryState::Removed
    }

    fn is_valid(&self) -> bool {
        let Some(file_path) = normalized_absolute_path(&self.file_path) else {
            return false;
        };
        if self.analysis_status.as_ref().is_some_and(|status| {
            !status.is_valid()
                || normalized_absolute_path(&status.file_path).as_ref() != Some(&file_path)
        }) {
            return false;
        }
        match self.file_system_discovery {
            AgentFileSystemDiscoveryState::Removed => {
                self.source_module_ownership == AgentSourceModuleOwnershipState::NotApplicable
                    && self.index_admission == AgentIndexAdmissionState::NotApplicable
                    && self.analysis_availability
                        == AgentAnalysisAvailabilityState::NotApplicable
                    && self.analysis_status.is_none()
            }
            AgentFileSystemDiscoveryState::Pending => {
                self.source_module_ownership == AgentSourceModuleOwnershipState::NotApplicable
                    && self.index_admission == AgentIndexAdmissionState::NotApplicable
                    && self.analysis_availability == AgentAnalysisAvailabilityState::Pending
                    && self.analysis_status.as_ref().is_some_and(|status| {
                        status.state == AgentFileAnalysisState::PendingIndex
                    })
            }
            AgentFileSystemDiscoveryState::Discovered => self.is_valid_discovered_state(),
        }
    }

    fn is_valid_discovered_state(&self) -> bool {
        let Some(status) = self.analysis_status.as_ref() else {
            return false;
        };
        match self.source_module_ownership {
            AgentSourceModuleOwnershipState::OutsideSourceModules => {
                self.index_admission == AgentIndexAdmissionState::NotApplicable
                    && self.analysis_availability
                        == AgentAnalysisAvailabilityState::NotApplicable
                    && status.state == AgentFileAnalysisState::OutsideSourceModules
            }
            AgentSourceModuleOwnershipState::NotApplicable => false,
            AgentSourceModuleOwnershipState::Owned => match self.index_admission {
                AgentIndexAdmissionState::NotApplicable => false,
                AgentIndexAdmissionState::Pending => {
                    self.analysis_availability == AgentAnalysisAvailabilityState::Pending
                        && status.state == AgentFileAnalysisState::PendingIndex
                }
                AgentIndexAdmissionState::Admitted => match self.analysis_availability {
                    AgentAnalysisAvailabilityState::Available => {
                        status.state == AgentFileAnalysisState::Analyzed
                    }
                    AgentAnalysisAvailabilityState::Pending => {
                        status.state == AgentFileAnalysisState::PendingIndex
                    }
                    AgentAnalysisAvailabilityState::Failed => {
                        status.state == AgentFileAnalysisState::BackendFailure
                    }
                    AgentAnalysisAvailabilityState::NotApplicable => false,
                },
            },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentFileSystemDiscoveryState {
    Discovered,
    Pending,
    Removed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentSourceModuleOwnershipState {
    Owned,
    OutsideSourceModules,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentIndexAdmissionState {
    Admitted,
    Pending,
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentAnalysisAvailabilityState {
    Available,
    Pending,
    Failed,
    NotApplicable,
}

fn normalized_absolute_path(raw: &str) -> Option<PathBuf> {
    let path = Path::new(raw);
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
                normalized.pop();
            }
        }
    }
    Some(normalized)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgentFileAnalysisStatus {
    file_path: String,
    state: AgentFileAnalysisState,
    message: Option<String>,
}

impl AgentFileAnalysisStatus {
    fn is_valid(&self) -> bool {
        if self.file_path.trim().is_empty() {
            return false;
        }
        match self.state {
            AgentFileAnalysisState::Analyzed => self.message.is_none(),
            AgentFileAnalysisState::PendingIndex
            | AgentFileAnalysisState::OutsideSourceModules
            | AgentFileAnalysisState::MissingOnDisk
            | AgentFileAnalysisState::BackendFailure => self
                .message
                .as_deref()
                .is_some_and(|message| !message.trim().is_empty()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentFileAnalysisState {
    Analyzed,
    PendingIndex,
    OutsideSourceModules,
    MissingOnDisk,
    BackendFailure,
}
