pub(crate) mod backend;
mod barrier;
pub(crate) mod collect;
mod dirty;
mod index;
pub(crate) mod model;

pub(crate) fn read_workspace_index(root: &model::WorkspaceRoot) -> model::WorkspaceIndexRead {
    index::read_workspace_index(root)
}

#[cfg(test)]
#[path = "../tests/support/workspace_files.rs"]
mod workspace_files_test_support;

#[cfg(test)]
mod tests;
