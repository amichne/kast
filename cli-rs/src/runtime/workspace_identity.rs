#[derive(Debug, Clone, PartialEq, Eq)]
struct WorkspaceGitIdentity {
    common_dir: PathBuf,
    branch: Option<String>,
    head: Option<String>,
}

fn workspace_git_identities_are_compatible_for_idea(
    invocation: &WorkspaceGitIdentity,
    candidate: &WorkspaceGitIdentity,
) -> bool {
    if invocation.common_dir != candidate.common_dir {
        return false;
    }
    match (&invocation.branch, &candidate.branch) {
        (Some(invocation_branch), Some(candidate_branch)) => invocation_branch == candidate_branch,
        _ => invocation.head.is_some() && invocation.head == candidate.head,
    }
}

fn workspace_git_identity(workspace_root: &Path) -> Option<WorkspaceGitIdentity> {
    let _root = git_path(workspace_root, &["rev-parse", "--show-toplevel"])?;
    let _git_dir = git_path(workspace_root, &["rev-parse", "--git-dir"])?;
    Some(WorkspaceGitIdentity {
        common_dir: git_path(workspace_root, &["rev-parse", "--git-common-dir"])?,
        branch: git_output(workspace_root, &["branch", "--show-current"]),
        head: git_output(workspace_root, &["rev-parse", "HEAD"]),
    })
}

fn git_path(workspace_root: &Path, args: &[&str]) -> Option<PathBuf> {
    let raw = git_output(workspace_root, args)?;
    let path = PathBuf::from(raw.trim());
    Some(config::normalize(if path.is_absolute() {
        path
    } else {
        workspace_root.join(path)
    }))
}

fn git_output(workspace_root: &Path, args: &[&str]) -> Option<String> {
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workspace_root)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!value.is_empty()).then_some(value)
}
