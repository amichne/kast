fn extract_exact_add_declaration(
    preimage: &[u8],
    postimage: &[u8],
) -> std::result::Result<String, String> {
    let normalized = normalized_addition_preimage(preimage)?;
    let separator = exact_add_declaration_separator(&normalized).as_bytes();
    let suffix = postimage
        .strip_prefix(preimage)
        .and_then(|suffix| suffix.strip_prefix(separator))
        .and_then(|suffix| suffix.strip_suffix(b"\n"))
        .ok_or_else(|| {
            "add-declaration authority violated the exact FILE_BOTTOM LF policy".to_string()
        })?;
    let declaration = std::str::from_utf8(suffix)
        .map_err(|_| "add-declaration declaration was not strict UTF-8".to_string())?
        .to_string();
    validate_exact_add_declaration_append(preimage, postimage, &declaration)?;
    Ok(declaration)
}

fn strict_descendant(child: &str, parent: &str) -> bool {
    let child = Path::new(child);
    let parent = Path::new(parent);
    child != parent && child.starts_with(parent)
}

fn is_canonical_nonblank(value: &str) -> bool {
    !value.trim().is_empty()
        && value == value.trim()
        && !value.chars().any(char::is_control)
}

fn is_valid_gradle_project_path(value: &str) -> bool {
    value.starts_with(':')
        && !value.contains(['/', '\\'])
        && !value.chars().any(char::is_control)
        && (value == ":"
            || (!value.ends_with(':') && value[1..].split(':').all(|segment| !segment.is_empty())))
}
