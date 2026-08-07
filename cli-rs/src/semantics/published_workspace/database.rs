impl PublishedWorkspaceDatabase {
    pub(crate) fn database(&self) -> &Path {
        &self.database
    }

    pub(crate) fn repository_base_database(&self) -> Option<&Path> {
        self.repository_base_database.as_deref()
    }

    pub(crate) fn require_manifest(
        &self,
        expected: &PublishedWorkspaceGenerationManifest,
    ) -> Result<()> {
        if &self.manifest != expected {
            return Err(CliError::new(
                "PUBLISHED_WORKSPACE_MISMATCH",
                "The workspace publication row does not match the revision admitted by the indexer runtime.",
            ));
        }
        Ok(())
    }

    pub(crate) fn read<T>(
        &self,
        operation: impl FnOnce(&PublishedWorkspaceDatabase) -> Result<T>,
    ) -> Result<T> {
        let value = operation(self)?;
        self.revalidate()?;
        Ok(value)
    }

    pub(crate) fn revalidate(&self) -> Result<()> {
        let current = resolve_published_workspace_database_from(&self.workspace_data)?;
        if current != *self {
            return Err(CliError::new(
                "PUBLISHED_WORKSPACE_MOVED",
                "The published workspace revision changed during the semantic read.",
            ));
        }
        Ok(())
    }
}
