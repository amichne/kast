fn validate_addition_declarations(
    declarations: &[AgentAdditionDeclaration],
    package_identity: &AgentAdditionKotlinPackage,
    content_length: usize,
) -> std::result::Result<(), String> {
    let mut previous: Option<&AgentAdditionRelativeRange> = None;
    let mut collision_keys = BTreeSet::new();
    for declaration in declarations {
        declaration.validate_for(package_identity, content_length)?;
        let range = &declaration.relative_range;
        if !collision_keys.insert(declaration.collision_key())
            || previous.is_some_and(|prior| {
                prior.start_offset > range.start_offset || prior.end_offset > range.start_offset
            })
        {
            return Err("addition declarations were not unique and ordered".to_string());
        }
        previous = Some(range);
    }
    Ok(())
}

fn validate_addition_context_coverage(
    context: &BTreeMap<&str, &str>,
    outbound: &AgentAdditionOutboundEvidence,
    rebinding: &AgentAdditionRebindingBaseline,
) -> std::result::Result<(), String> {
    let mut required_paths = BTreeSet::new();
    for occurrence in &outbound.occurrences {
        if let Some(path) = occurrence.resolved_target.source_file_path() {
            required_paths.insert(path);
        }
    }
    for occurrence in &rebinding.occurrences {
        required_paths.insert(occurrence.range.file_path.as_str());
        if let Some(path) = occurrence.current_target.source_file_path() {
            required_paths.insert(path);
        }
    }
    if required_paths.iter().any(|path| !context.contains_key(path)) {
        return Err("addition context did not cover every compiler occurrence".to_string());
    }
    Ok(())
}

fn validate_addition_target_path(value: &str) -> std::result::Result<(), String> {
    if !is_normalized_absolute_exact_file_path(value)
        || !value.ends_with(".kt")
        || value.ends_with(".kts")
    {
        return Err("addition target was not one normalized absolute Kotlin file".to_string());
    }
    Ok(())
}

pub(crate) fn validate_strict_addition_text(
    value: &str,
    allow_final_lf: bool,
) -> std::result::Result<(), String> {
    if value.trim().is_empty()
        || value.contains('\r')
        || value.contains('\u{feff}')
        || (!allow_final_lf && value.ends_with('\n'))
    {
        return Err("addition source was not strict normalized non-blank Kotlin text".to_string());
    }
    Ok(())
}

fn normalized_addition_preimage(bytes: &[u8]) -> std::result::Result<String, String> {
    let decoded = std::str::from_utf8(bytes)
        .map_err(|_| "add-declaration preimage was not strict UTF-8".to_string())?;
    Ok(decoded
        .strip_prefix('\u{feff}')
        .unwrap_or(decoded)
        .replace("\r\n", "\n")
        .replace('\r', "\n"))
}

fn exact_add_declaration_separator(normalized_preimage: &str) -> &'static str {
    if normalized_preimage.is_empty() || normalized_preimage.ends_with("\n\n") {
        ""
    } else if normalized_preimage.ends_with('\n') {
        "\n"
    } else {
        "\n\n"
    }
}

fn validate_exact_add_declaration_append(
    preimage: &[u8],
    postimage: &[u8],
    declaration: &str,
) -> std::result::Result<(), String> {
    let normalized = normalized_addition_preimage(preimage)?;
    let mut expected = preimage.to_vec();
    expected.extend_from_slice(exact_add_declaration_separator(&normalized).as_bytes());
    expected.extend_from_slice(declaration.as_bytes());
    expected.push(b'\n');
    if expected != postimage {
        return Err("add-declaration postimage violated the exact FILE_BOTTOM LF policy".to_string());
    }
    Ok(())
}
