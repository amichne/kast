fn git(workdir: &Path, args: &[&str]) {
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workdir)
        .output()
        .expect("git fixture command");
    assert!(
        output.status.success(),
        "git {args:?}: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn nested_git_mapping_overrides_relative_paths_and_maps_only_workspace_records() {
    let temp = tempfile::tempdir().expect("repository");
    let repository = temp.path();
    let workspace = repository.join("nested/workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    git(repository, &["init", "-q"]);
    git(repository, &["config", "user.email", "fixture@example.com"]);
    git(repository, &["config", "user.name", "Fixture"]);
    git(repository, &["config", "status.relativePaths", "true"]);
    for path in ["Modified.kt", "Deleted.kt", "Old.kt"] {
        std::fs::write(workspace.join(path), "before\n").expect("tracked file");
    }
    std::fs::write(repository.join("Outside.kt"), "before\n").expect("outside file");
    git(repository, &["add", "."]);
    git(repository, &["commit", "-qm", "fixture"]);

    std::fs::write(workspace.join("Modified.kt"), "after\n").expect("modified");
    std::fs::remove_file(workspace.join("Deleted.kt")).expect("deleted");
    std::fs::rename(workspace.join("Old.kt"), workspace.join("New.kt")).expect("renamed");
    std::fs::write(workspace.join("Added.kt"), "added\n").expect("added");
    std::fs::write(workspace.join("Untracked.kt"), "untracked\n").expect("untracked");
    std::fs::write(repository.join("Outside.kt"), "after\n").expect("outside modified");
    git(
        repository,
        &[
            "add",
            "-A",
            "--",
            "nested/workspace/Old.kt",
            "nested/workspace/New.kt",
            "nested/workspace/Added.kt",
        ],
    );
    let root = WorkspaceRoot::try_from(workspace.as_path()).expect("root");

    let DirtyWorkspaceRead::Snapshot(snapshot) = super::dirty::read_dirty_workspace(&root) else {
        panic!("nested Git workspace must be readable");
    };
    assert_eq!(
        snapshot.stamp().repository_root(),
        std::fs::canonicalize(repository).expect("canonical repository")
    );
    let dirty: Vec<_> = snapshot
        .stamp()
        .dirty_paths()
        .iter()
        .map(|path| path.as_path().to_path_buf())
        .collect();

    assert_eq!(
        dirty,
        [
            Path::new("Added.kt").to_path_buf(),
            Path::new("Deleted.kt").to_path_buf(),
            Path::new("Modified.kt").to_path_buf(),
            Path::new("New.kt").to_path_buf(),
            Path::new("Old.kt").to_path_buf(),
            Path::new("Untracked.kt").to_path_buf(),
        ]
    );
    let clean = WorkspaceFilePath::from_relative_path(Path::new("Clean.kt").to_path_buf())
        .expect("clean path");
    assert_eq!(snapshot.state_for(&clean), WorkspaceFileDirtyState::Clean);
}

#[test]
fn porcelain_v2_maps_conflicts_and_each_contained_rename_endpoint() {
    let status = b"u UU N... 100644 100644 100644 100644 aaaaaaa bbbbbbb ccccccc nested/workspace/Conflict.kt\0\
2 R. N... 100644 100644 100644 aaaaaaa bbbbbbb R100 nested/workspace/Inside.kt\0outside/Before.kt\0\
2 R. N... 100644 100644 100644 aaaaaaa bbbbbbb R100 outside/After.kt\0nested/workspace/Before.kt\0";

    let paths = super::dirty::parse_porcelain_v2(status, Path::new("nested/workspace"))
        .expect("porcelain v2");
    let paths: Vec<_> = paths
        .iter()
        .map(|path| path.as_path().to_path_buf())
        .collect();

    assert_eq!(
        paths,
        [
            Path::new("Before.kt").to_path_buf(),
            Path::new("Conflict.kt").to_path_buf(),
            Path::new("Inside.kt").to_path_buf(),
        ]
    );
}

fn complete_backend_responses(
    snapshot: &str,
    module_name: &str,
    source_roots: &[&str],
    content_roots: &[&str],
    files: &[&str],
) -> Vec<Result<serde_json::Value, super::backend::BackendRpcFailure>> {
    let mut responses = vec![backend_result(
        snapshot,
        vec![backend_module_with_ownership(
            module_name,
            files.len(),
            &[],
            None,
            source_roots,
            content_roots,
            &[],
        )],
    )];
    if !files.is_empty() {
        responses.push(backend_result(
            snapshot,
            vec![backend_module_with_ownership(
                module_name,
                files.len(),
                files,
                None,
                source_roots,
                content_roots,
                &[],
            )],
        ));
    }
    responses.push(backend_result(snapshot, vec![]));
    responses
}
