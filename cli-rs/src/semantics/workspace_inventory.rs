#[path = "workspace_inventory/backend.rs"]
pub(crate) mod backend;
#[path = "workspace_inventory/barrier.rs"]
mod barrier;
#[path = "workspace_inventory/collect.rs"]
pub(crate) mod collect;
#[path = "workspace_inventory/dirty.rs"]
mod dirty;
#[path = "workspace_inventory/index.rs"]
mod index;
#[path = "workspace_inventory/model.rs"]
pub(crate) mod model;

pub(crate) fn read_workspace_index(root: &model::WorkspaceRoot) -> model::WorkspaceIndexRead {
    index::read_workspace_index(root)
}

pub(crate) fn read_persisted_workspace_index(
    root: &model::WorkspaceRoot,
) -> model::WorkspaceIndexRead {
    index::read_persisted_workspace_index(root)
}

#[cfg(test)]
#[path = "../../tests/support/workspace_files.rs"]
mod workspace_files_test_support;

#[cfg(test)]
#[path = "workspace_inventory/tests.rs"]
mod tests;
