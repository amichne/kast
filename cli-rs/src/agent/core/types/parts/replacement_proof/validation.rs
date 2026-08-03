fn validate_exact_replacement_edit(
    edit: &AgentReplacementPreviewEdit,
    proof: &AgentExactReplacementProof,
) -> std::result::Result<(), String> {
    if !edit.is_valid()
        || edit.file_path != proof.source_range.file_path
        || edit.start_offset != proof.source_range.start_offset
        || edit.end_offset != proof.source_range.end_offset
    {
        return Err(
            "replacement preview edit disagreed with the exact proven source range".to_string(),
        );
    }
    proof.validate(&edit.new_text)
}

fn validate_replacement_file_images(
    images: &[AgentExactFileImage],
    edit: &AgentReplacementPreviewEdit,
    proof: &AgentExactReplacementProof,
) -> std::result::Result<(), String> {
    let exact_edits = [AgentExactFileEdit {
        file_path: &edit.file_path,
        start_offset: edit.start_offset,
        end_offset: edit.end_offset,
        new_text: &edit.new_text,
    }];
    let image_hashes = validate_exact_file_image_set(images, &exact_edits)?;
    let legacy_hashes = proof
        .file_hashes
        .iter()
        .map(|file_hash| (file_hash.file_path.clone(), file_hash.hash.clone()))
        .collect::<BTreeMap<_, _>>();
    if legacy_hashes.len() != proof.file_hashes.len() || legacy_hashes != image_hashes {
        return Err(
            "replacement proof file hashes disagreed with exact preimage authority".to_string(),
        );
    }
    Ok(())
}

fn is_exact_replacement_name(value: &str) -> bool {
    !value.is_empty() && value.trim() == value
}

fn is_normalized_absolute_replacement_path(value: &str) -> bool {
    let path = Path::new(value);
    path.is_absolute()
        && path.components().all(|component| {
            matches!(
                component,
                std::path::Component::RootDir | std::path::Component::Normal(_)
            )
        })
}

fn is_lowercase_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn replacement_sha256(bytes: &[u8]) -> String {
    use sha2::Digest as _;

    hex::encode(sha2::Sha256::digest(bytes))
}

fn utf16_range_equals(value: &str, start: u32, end: u32, expected: &str) -> bool {
    let units = value.encode_utf16().collect::<Vec<_>>();
    let expected = expected.encode_utf16().collect::<Vec<_>>();
    let Ok(start) = usize::try_from(start) else {
        return false;
    };
    let Ok(end) = usize::try_from(end) else {
        return false;
    };
    units
        .get(start..end)
        .is_some_and(|actual| actual == expected.as_slice())
}

fn utf16_byte_offset(value: &str, target: u32) -> Option<usize> {
    let target = usize::try_from(target).ok()?;
    let mut logical_offset = 0usize;
    for (byte_offset, character) in value.char_indices() {
        if logical_offset == target {
            return Some(byte_offset);
        }
        logical_offset = logical_offset.checked_add(character.len_utf16())?;
        if logical_offset > target {
            return None;
        }
    }
    (logical_offset == target).then_some(value.len())
}
