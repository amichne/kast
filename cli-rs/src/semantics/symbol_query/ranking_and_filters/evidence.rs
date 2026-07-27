pub(crate) fn type_evidence_keys(value: &str) -> BTreeSet<String> {
    let mut keys = BTreeSet::new();
    for type_name in value.split(|character: char| {
        !(character.is_alphanumeric() || matches!(character, '.' | '_'))
    }) {
        if type_name.is_empty() {
            continue;
        }
        let normalized = type_name.to_ascii_lowercase();
        keys.insert(simple_name(&normalized).to_string());
        keys.insert(normalized);
    }
    keys
}

fn type_mentions(value: &str, name: &str) -> bool {
    type_evidence_keys(value).contains(&name.to_ascii_lowercase())
}

fn named_evidence_mentions(value: &str, name: &str) -> bool {
    value.lines().any(|candidate| candidate.eq_ignore_ascii_case(name))
}
