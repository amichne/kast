mod index;
pub(crate) mod model;

pub(crate) fn read_workspace_index(root: &model::WorkspaceRoot) -> model::WorkspaceIndexRead {
    index::read_workspace_index(root)
}

#[cfg(test)]
mod tests;
