    fn report_entry<'a>(entries: &'a [PathResolutionEntry], key: &str) -> &'a PathResolutionEntry {
        entries
            .iter()
            .find(|entry| entry.key == key)
            .unwrap_or_else(|| panic!("missing entry {key}: {entries:#?}"))
    }

    include!("workspace/local.rs");
    include!("workspace/git.rs");
