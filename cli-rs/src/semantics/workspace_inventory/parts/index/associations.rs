impl AssociationRows {
    fn remove_orphan_rows(&mut self, manifest_keys: &BTreeSet<FileKey>) -> usize {
        let mut orphan_count = 0;
        for key in self.all_keys() {
            if !manifest_keys.contains(&key) {
                orphan_count += self.projects.remove(&key).map_or(0, |rows| rows.len());
                orphan_count += self.invalid_projects.remove(&key).unwrap_or_default();
                orphan_count += self.source_sets.remove(&key).map_or(0, |rows| rows.len());
                orphan_count += self.invalid_source_sets.remove(&key).unwrap_or_default();
            }
        }
        orphan_count
    }

    fn all_keys(&self) -> BTreeSet<FileKey> {
        self.projects
            .keys()
            .chain(self.invalid_projects.keys())
            .chain(self.source_sets.keys())
            .chain(self.invalid_source_sets.keys())
            .cloned()
            .collect()
    }
}
