use base64::{Engine as _, engine::general_purpose::STANDARD as STANDARD_BASE64};

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentExactByteImage {
    content_base64: String,
    sha256: String,
}

impl AgentExactByteImage {
    pub(crate) fn from_bytes(bytes: &[u8]) -> Self {
        Self {
            content_base64: STANDARD_BASE64.encode(bytes),
            sha256: exact_file_sha256(bytes),
        }
    }

    pub(crate) fn validate(&self) -> std::result::Result<Vec<u8>, String> {
        if !is_lowercase_exact_file_sha256(&self.sha256) {
            return Err("exact byte image SHA-256 was not lowercase hexadecimal".to_string());
        }
        let bytes = STANDARD_BASE64
            .decode(&self.content_base64)
            .map_err(|_| "exact byte image content was not standard Base64".to_string())?;
        if STANDARD_BASE64.encode(&bytes) != self.content_base64 {
            return Err("exact byte image content was not canonical standard Base64".to_string());
        }
        if exact_file_sha256(&bytes) != self.sha256 {
            return Err("exact byte image content disagreed with its SHA-256".to_string());
        }
        Ok(bytes)
    }

    pub(crate) fn sha256(&self) -> &str {
        &self.sha256
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentExactFileImage {
    file_path: String,
    preimage: AgentExactByteImage,
    postimage: AgentExactByteImage,
}

impl AgentExactFileImage {
    fn decode(&self) -> std::result::Result<(Vec<u8>, Vec<u8>), String> {
        if !is_normalized_absolute_exact_file_path(&self.file_path) {
            return Err("exact file image path was not normalized and absolute".to_string());
        }
        let preimage = self.preimage.validate()?;
        let postimage = self.postimage.validate()?;
        if preimage == postimage {
            return Err("exact file image edit retained an unchanged byte image".to_string());
        }
        Ok((preimage, postimage))
    }

    pub(crate) fn file_path(&self) -> &str {
        &self.file_path
    }

    fn preimage_sha256(&self) -> &str {
        &self.preimage.sha256
    }

    pub(crate) fn preimage(&self) -> &AgentExactByteImage {
        &self.preimage
    }

    pub(crate) fn postimage(&self) -> &AgentExactByteImage {
        &self.postimage
    }

    #[allow(dead_code)] // Consumed by the exact-image apply slice, not legacy submit.
    pub(crate) fn forward_cas_request(&self) -> AgentExactFileImageCasRequest {
        AgentExactFileImageCasRequest {
            file_path: self.file_path.clone(),
            expected_current_sha256: self.preimage.sha256.clone(),
            content_base64: self.postimage.content_base64.clone(),
            expected_result_sha256: self.postimage.sha256.clone(),
            mutation_attempt_id: None,
            mutation_scratch: None,
        }
    }

    #[allow(dead_code)] // Consumed by the exact-image recovery slice.
    pub(crate) fn restore_cas_request(&self) -> AgentExactFileImageCasRequest {
        AgentExactFileImageCasRequest {
            file_path: self.file_path.clone(),
            expected_current_sha256: self.postimage.sha256.clone(),
            content_base64: self.preimage.content_base64.clone(),
            expected_result_sha256: self.preimage.sha256.clone(),
            mutation_attempt_id: None,
            mutation_scratch: None,
        }
    }
}

struct AgentExactFileEdit<'a> {
    file_path: &'a str,
    start_offset: u32,
    end_offset: u32,
    new_text: &'a str,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AgentMutationScratchSet {
    pub(crate) target_file_path: String,
    pub(crate) quarantine_path: String,
    pub(crate) prepared_path: String,
    pub(crate) prepared_cleanup_path: String,
    pub(crate) quarantine_cleanup_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
#[allow(dead_code)] // Typed transport bridge for the next apply/recovery slice.
pub(crate) struct AgentExactFileImageCasRequest {
    file_path: String,
    expected_current_sha256: String,
    content_base64: String,
    expected_result_sha256: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    mutation_attempt_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    mutation_scratch: Option<AgentMutationScratchSet>,
}

impl AgentExactFileImageCasRequest {
    pub(crate) fn forward(
        file_path: String,
        preimage: &AgentExactByteImage,
        postimage: &AgentExactByteImage,
    ) -> Self {
        Self {
            file_path,
            expected_current_sha256: preimage.sha256.clone(),
            content_base64: postimage.content_base64.clone(),
            expected_result_sha256: postimage.sha256.clone(),
            mutation_attempt_id: None,
            mutation_scratch: None,
        }
    }

    pub(crate) fn restore(
        file_path: String,
        preimage: &AgentExactByteImage,
        postimage: &AgentExactByteImage,
    ) -> Self {
        Self {
            file_path,
            expected_current_sha256: postimage.sha256.clone(),
            content_base64: preimage.content_base64.clone(),
            expected_result_sha256: preimage.sha256.clone(),
            mutation_attempt_id: None,
            mutation_scratch: None,
        }
    }

    pub(crate) fn for_attempt(
        mut self,
        mutation_attempt_id: uuid::Uuid,
        mutation_scratch: AgentMutationScratchSet,
    ) -> Self {
        self.mutation_attempt_id = Some(mutation_attempt_id.hyphenated().to_string());
        self.mutation_scratch = Some(mutation_scratch);
        self
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[allow(dead_code)] // Typed transport bridge for the next apply/recovery slice.
enum AgentExactFileImageCasStatus {
    Committed,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
#[allow(dead_code)] // Typed transport bridge for the next apply/recovery slice.
pub(crate) struct AgentExactFileImageCasResponse {
    file_path: String,
    status: AgentExactFileImageCasStatus,
    previous_sha256: String,
    result_sha256: String,
    schema_version: u32,
}

impl AgentExactFileImageCasResponse {
    #[allow(dead_code)] // Consumed when exact-image apply parses the backend response.
    pub(crate) fn validate_for(
        &self,
        request: &AgentExactFileImageCasRequest,
    ) -> std::result::Result<(), String> {
        if self.schema_version != SCHEMA_VERSION
            || self.status != AgentExactFileImageCasStatus::Committed
            || self.file_path != request.file_path
            || self.previous_sha256 != request.expected_current_sha256
            || self.result_sha256 != request.expected_result_sha256
        {
            return Err(
                "exact file image CAS response disagreed with its typed request".to_string(),
            );
        }
        Ok(())
    }
}

fn validate_exact_file_image_set(
    images: &[AgentExactFileImage],
    edits: &[AgentExactFileEdit<'_>],
) -> std::result::Result<BTreeMap<String, String>, String> {
    if images.is_empty() || edits.is_empty() {
        return Err("exact file image authority was empty".to_string());
    }
    let mut edits_by_path = BTreeMap::<&str, Vec<&AgentExactFileEdit<'_>>>::new();
    for edit in edits {
        edits_by_path.entry(edit.file_path).or_default().push(edit);
    }
    let mut preimage_hashes = BTreeMap::new();
    for image in images {
        let (preimage, expected_postimage) = image.decode()?;
        if preimage_hashes
            .insert(
                image.file_path().to_string(),
                image.preimage_sha256().to_string(),
            )
            .is_some()
        {
            return Err("exact file image authority repeated a file path".to_string());
        }
        let file_edits = edits_by_path.get(image.file_path()).ok_or_else(|| {
            "exact file image authority included a file without an edit".to_string()
        })?;
        let actual_postimage = apply_normalized_utf16_edits(&preimage, file_edits)?;
        if actual_postimage != expected_postimage {
            return Err(
                "exact file image postimage disagreed with its normalized UTF-16 edits".to_string(),
            );
        }
    }
    if preimage_hashes
        .keys()
        .map(String::as_str)
        .collect::<BTreeSet<_>>()
        != edits_by_path.keys().copied().collect::<BTreeSet<_>>()
    {
        return Err("exact file images did not cover exactly the edited files".to_string());
    }
    Ok(preimage_hashes)
}

fn apply_normalized_utf16_edits(
    raw_preimage: &[u8],
    edits: &[&AgentExactFileEdit<'_>],
) -> std::result::Result<Vec<u8>, String> {
    const BOM: &[u8] = b"\xef\xbb\xbf";
    const ILLEGAL_BOUNDARY: usize = usize::MAX;

    let bom_length = usize::from(raw_preimage.starts_with(BOM)) * BOM.len();
    let decoded = std::str::from_utf8(&raw_preimage[bom_length..])
        .map_err(|_| "exact file image preimage was not strict UTF-8".to_string())?;
    let mut boundaries = vec![bom_length];
    let mut raw_offset = bom_length;
    let mut decoded_offset = 0;
    let mut first_separator = None;
    while decoded_offset < decoded.len() {
        let remaining = &decoded[decoded_offset..];
        if remaining.starts_with("\r\n") {
            decoded_offset += 2;
            raw_offset += 2;
            boundaries.push(raw_offset);
            first_separator.get_or_insert("\r\n");
        } else if remaining.starts_with('\r') {
            decoded_offset += 1;
            raw_offset += 1;
            boundaries.push(raw_offset);
            first_separator.get_or_insert("\r");
        } else if remaining.starts_with('\n') {
            decoded_offset += 1;
            raw_offset += 1;
            boundaries.push(raw_offset);
            first_separator.get_or_insert("\n");
        } else {
            let scalar = remaining
                .chars()
                .next()
                .expect("a non-empty strict UTF-8 suffix has one scalar");
            decoded_offset += scalar.len_utf8();
            raw_offset += scalar.len_utf8();
            if scalar.len_utf16() == 2 {
                boundaries.push(ILLEGAL_BOUNDARY);
            }
            boundaries.push(raw_offset);
        }
    }
    if raw_offset != raw_preimage.len() {
        return Err("exact file image UTF-8 mapping did not consume its preimage".to_string());
    }

    let line_separator = first_separator.unwrap_or("\n");
    let mut previous_start = None;
    let mut previous_end = 0;
    let mut raw_cursor = 0;
    let mut output = Vec::with_capacity(raw_preimage.len());
    for edit in edits {
        let start = usize::try_from(edit.start_offset)
            .map_err(|_| "exact file image edit offset was invalid".to_string())?;
        let end = usize::try_from(edit.end_offset)
            .map_err(|_| "exact file image edit offset was invalid".to_string())?;
        let duplicate_empty = previous_start.is_some_and(|previous_start| {
            start == end && previous_start == previous_end && start == previous_start
        });
        if end < start
            || end >= boundaries.len()
            || previous_start.is_some_and(|previous_start| {
                start < previous_start || start < previous_end || duplicate_empty
            })
        {
            return Err(
                "exact file image edits were outside, unsorted, or overlapping".to_string(),
            );
        }
        let raw_start = boundaries[start];
        let raw_end = boundaries[end];
        if raw_start == ILLEGAL_BOUNDARY || raw_end == ILLEGAL_BOUNDARY {
            return Err("exact file image edit split a UTF-16 surrogate pair".to_string());
        }
        if edit.new_text.contains('\r') {
            return Err("exact file image replacement text was not LF-normalized".to_string());
        }
        output.extend_from_slice(&raw_preimage[raw_cursor..raw_start]);
        output.extend_from_slice(edit.new_text.replace('\n', line_separator).as_bytes());
        raw_cursor = raw_end;
        previous_start = Some(start);
        previous_end = end;
    }
    output.extend_from_slice(&raw_preimage[raw_cursor..]);
    Ok(output)
}

fn is_normalized_absolute_exact_file_path(value: &str) -> bool {
    let path = Path::new(value);
    let normalized = path.components().collect::<PathBuf>();
    path.is_absolute()
        && normalized.to_str() == Some(value)
        && path
            .components()
            .any(|component| matches!(component, Component::Normal(_)))
        && path
            .components()
            .all(|component| matches!(component, Component::RootDir | Component::Normal(_)))
}

fn is_lowercase_exact_file_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn exact_file_sha256(bytes: &[u8]) -> String {
    use sha2::Digest as _;

    hex::encode(sha2::Sha256::digest(bytes))
}

#[cfg(test)]
#[path = "parts/exact_file_image/tests.rs"]
mod exact_file_image_tests;
