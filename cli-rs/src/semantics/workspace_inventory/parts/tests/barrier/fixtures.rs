struct MutatingIndexLaneReader {
    database_path: std::path::PathBuf,
    mutation_sql: &'static str,
    mutate_on_even_reads: usize,
    index_reads: usize,
    filesystem_reads: usize,
    dirty_reads: usize,
    inner: super::collect::LiveCandidateWorkspaceLaneReader,
}

impl MutatingIndexLaneReader {
    fn new(
        database_path: std::path::PathBuf,
        mutation_sql: &'static str,
        mutate_on_even_reads: usize,
    ) -> Self {
        Self {
            database_path,
            mutation_sql,
            mutate_on_even_reads,
            index_reads: 0,
            filesystem_reads: 0,
            dirty_reads: 0,
            inner: super::collect::LiveCandidateWorkspaceLaneReader,
        }
    }
}

impl super::collect::WorkspaceInventoryLaneReader for MutatingIndexLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        self.index_reads += 1;
        let even_observation = self.index_reads.is_multiple_of(2);
        let mutation_number = self.index_reads / 2;
        if even_observation && mutation_number <= self.mutate_on_even_reads {
            rusqlite::Connection::open(&self.database_path)
                .expect("mutation database")
                .execute_batch(self.mutation_sql)
                .expect("lane mutation");
        }
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        self.dirty_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        self.filesystem_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

fn barrier_fixture() -> (
    tempfile::TempDir,
    WorkspaceRoot,
    WorkspaceIndexFixture,
    Vec<Result<serde_json::Value, super::backend::BackendRpcFailure>>,
) {
    let (temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/main/kotlin/sample", "Stable.kt", true);
    insert_named_metadata(&fixture, 1, "Stable.kt", 1, "sample", None);
    fixture.insert_project_evidence(1, "Stable.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let mut responses = complete_backend_responses(
        "snapshot",
        "module",
        &["src/main/kotlin/sample"],
        &[],
        &["src/main/kotlin/sample/Stable.kt"],
    );
    responses.push(backend_result("snapshot", vec![]));
    (temp, root, fixture, responses)
}

fn empty_available_backend(
    token: &str,
    module_name: &str,
) -> Vec<Result<serde_json::Value, super::backend::BackendRpcFailure>> {
    vec![
        backend_result(token, vec![backend_module(module_name, 0, &[], None)]),
        backend_result(token, vec![]),
    ]
}

fn unavailable_backend(
    reason: &str,
) -> Result<serde_json::Value, super::backend::BackendRpcFailure> {
    Err(super::backend::BackendRpcFailure::Transport(
        reason.to_string(),
    ))
}
