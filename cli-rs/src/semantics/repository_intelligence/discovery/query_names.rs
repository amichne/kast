fn explicit_repository_names(question: &str) -> BTreeSet<String> {
    let words = question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    let mut names = BTreeSet::new();
    if words
        .first()
        .is_some_and(|word| word.eq_ignore_ascii_case("resolve"))
        && let Some(name) = words.get(1)
    {
        names.insert((*name).to_string());
    }
    if let Some(member) = dotted_member_name(question) {
        names.insert(member);
    }
    names.extend(words.windows(2).filter_map(|pair| {
        let family = crate::symbol_query::SymbolDiscoveryFamily::from_word(pair[1])?;
        explicit_name_before_family(pair[0], family).then(|| pair[0].to_string())
    }));
    names.extend(words.into_iter().filter_map(|word| {
        let uppercase = word
            .chars()
            .filter(|character| character.is_uppercase())
            .count();
        (uppercase >= 2 || word.contains('_')).then(|| word.to_string())
    }));
    names
}

fn explicit_name_before_family(
    name: &str,
    family: crate::symbol_query::SymbolDiscoveryFamily,
) -> bool {
    if matches!(
        name.to_ascii_lowercase().as_str(),
        "a" | "an" | "exact" | "kotlin" | "one" | "that" | "the" | "which"
    ) {
        return false;
    }
    match family {
        crate::symbol_query::SymbolDiscoveryFamily::Type => {
            name.contains('_') || name.chars().any(char::is_uppercase)
        }
        crate::symbol_query::SymbolDiscoveryFamily::Callable => true,
    }
}
