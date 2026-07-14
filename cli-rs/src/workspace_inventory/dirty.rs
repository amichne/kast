use std::collections::BTreeSet;
use std::ffi::OsStr;
use std::path::{Component, Path, PathBuf};
use std::process::Command;

use super::model::{
    DirtyWorkspaceRead, DirtyWorkspaceSnapshot, DirtyWorkspaceStamp, WorkspaceFilePath,
    WorkspaceLaneUnavailableReason, WorkspaceRoot,
};

pub(super) fn read_dirty_workspace(root: &WorkspaceRoot) -> DirtyWorkspaceRead {
    let repository_root = match git_top_level(root.as_path()) {
        Ok(repository_root) => repository_root,
        Err(reason) => return DirtyWorkspaceRead::Unavailable(reason),
    };
    let workspace_prefix = match root.as_path().strip_prefix(&repository_root) {
        Ok(prefix) => prefix,
        Err(_) => {
            return DirtyWorkspaceRead::Unavailable(WorkspaceLaneUnavailableReason::new(
                "GIT_TOP_LEVEL_OUTSIDE_WORKSPACE_ANCESTRY",
            ));
        }
    };
    let output = match Command::new("git")
        .args(["-c", "status.relativePaths=false", "status"])
        .args(["--porcelain=v2", "-z", "--untracked-files=all", "--", "."])
        .current_dir(root.as_path())
        .output()
    {
        Ok(output) => output,
        Err(error) => {
            return DirtyWorkspaceRead::Unavailable(WorkspaceLaneUnavailableReason::new(format!(
                "GIT_STATUS_EXECUTION_FAILED:{error}"
            )));
        }
    };
    if !output.status.success() {
        return DirtyWorkspaceRead::Unavailable(WorkspaceLaneUnavailableReason::new(format!(
            "GIT_STATUS_FAILED:{}",
            output.status
        )));
    }
    match parse_porcelain_v2(&output.stdout, workspace_prefix) {
        Ok(dirty_paths) => DirtyWorkspaceRead::Snapshot(DirtyWorkspaceSnapshot::complete(
            DirtyWorkspaceStamp::new(repository_root, dirty_paths),
        )),
        Err(reason) => DirtyWorkspaceRead::Unavailable(reason),
    }
}

fn git_top_level(workspace_root: &Path) -> Result<PathBuf, WorkspaceLaneUnavailableReason> {
    let output = Command::new("git")
        .args(["rev-parse", "--show-toplevel"])
        .current_dir(workspace_root)
        .output()
        .map_err(|error| {
            WorkspaceLaneUnavailableReason::new(format!("GIT_TOP_LEVEL_EXECUTION_FAILED:{error}"))
        })?;
    if !output.status.success() {
        return Err(WorkspaceLaneUnavailableReason::new("GIT_UNAVAILABLE"));
    }
    let raw = std::str::from_utf8(&output.stdout)
        .map_err(|_| WorkspaceLaneUnavailableReason::new("GIT_TOP_LEVEL_INVALID_UTF8"))?;
    std::fs::canonicalize(raw.trim()).map_err(|error| {
        WorkspaceLaneUnavailableReason::new(format!("GIT_TOP_LEVEL_INVALID:{error}"))
    })
}

pub(super) fn parse_porcelain_v2(
    bytes: &[u8],
    workspace_prefix: &Path,
) -> Result<BTreeSet<WorkspaceFilePath>, WorkspaceLaneUnavailableReason> {
    let mut records = bytes
        .split(|byte| *byte == 0)
        .filter(|record| !record.is_empty());
    let mut paths = BTreeSet::new();
    while let Some(record) = records.next() {
        let record = std::str::from_utf8(record)
            .map_err(|_| WorkspaceLaneUnavailableReason::new("GIT_STATUS_INVALID_UTF8"))?;
        match record.as_bytes().first().copied() {
            Some(b'1') => {
                let path = field_after_fixed_columns(record, 8)?;
                insert_contained(path, workspace_prefix, &mut paths)?;
            }
            Some(b'2') => {
                let current = field_after_fixed_columns(record, 9)?;
                insert_contained(current, workspace_prefix, &mut paths)?;
                let original = records.next().ok_or_else(|| {
                    WorkspaceLaneUnavailableReason::new("GIT_RENAME_ORIGINAL_MISSING")
                })?;
                let original = std::str::from_utf8(original)
                    .map_err(|_| WorkspaceLaneUnavailableReason::new("GIT_STATUS_INVALID_UTF8"))?;
                insert_contained(original, workspace_prefix, &mut paths)?;
            }
            Some(b'u') => {
                let path = field_after_fixed_columns(record, 10)?;
                insert_contained(path, workspace_prefix, &mut paths)?;
            }
            Some(b'?') => {
                let path = record.strip_prefix("? ").ok_or_else(|| {
                    WorkspaceLaneUnavailableReason::new("GIT_UNTRACKED_RECORD_INVALID")
                })?;
                insert_contained(path, workspace_prefix, &mut paths)?;
            }
            Some(b'#') => {}
            Some(_) | None => {
                return Err(WorkspaceLaneUnavailableReason::new(
                    "GIT_STATUS_RECORD_UNSUPPORTED",
                ));
            }
        }
    }
    Ok(paths)
}

fn field_after_fixed_columns(
    record: &str,
    fixed_columns: usize,
) -> Result<&str, WorkspaceLaneUnavailableReason> {
    record
        .splitn(fixed_columns + 1, ' ')
        .nth(fixed_columns)
        .filter(|path| !path.is_empty())
        .ok_or_else(|| WorkspaceLaneUnavailableReason::new("GIT_STATUS_RECORD_INVALID"))
}

fn insert_contained(
    repository_relative: &str,
    workspace_prefix: &Path,
    paths: &mut BTreeSet<WorkspaceFilePath>,
) -> Result<(), WorkspaceLaneUnavailableReason> {
    let repository_relative = Path::new(OsStr::new(repository_relative));
    if repository_relative.is_absolute()
        || repository_relative
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err(WorkspaceLaneUnavailableReason::new(
            "GIT_STATUS_PATH_INVALID",
        ));
    }
    let Ok(workspace_relative) = repository_relative.strip_prefix(workspace_prefix) else {
        return Ok(());
    };
    if let Some(path) = WorkspaceFilePath::from_relative_path(workspace_relative.to_path_buf()) {
        paths.insert(path);
    }
    Ok(())
}
