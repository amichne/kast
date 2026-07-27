fn returning_callable_index(
    nodes: &[RepositoryNode],
) -> BTreeMap<String, BTreeSet<String>> {
    let mut index = BTreeMap::<String, BTreeSet<String>>::new();
    for node in nodes {
        if !crate::symbol_query::SymbolDiscoveryFamily::Callable.admits(&node.kind) {
            continue;
        }
        let Some(return_type) = node.return_type.as_deref() else {
            continue;
        };
        for type_key in crate::symbol_query::type_evidence_keys(return_type) {
            index.entry(type_key).or_default().insert(node.name.clone());
        }
    }
    index
}

fn returning_callables(
    index: &BTreeMap<String, BTreeSet<String>>,
    node: &RepositoryNode,
) -> String {
    let exact = node
        .fq_name
        .as_ref()
        .and_then(|name| index.get(&name.to_ascii_lowercase()));
    exact
        .or_else(|| index.get(&node.name.to_ascii_lowercase()))
        .map(|names| names.iter().cloned().collect::<Vec<_>>().join("\n"))
        .unwrap_or_default()
}
