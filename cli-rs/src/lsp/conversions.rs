#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LspRange {
    start_line: usize,
    start_character: usize,
    end_line: usize,
    end_character: usize,
}

fn offset_for_position(text: &str, line: usize, character: usize) -> LspResult<usize> {
    let line_start = line_start_offset(text, line)?;
    let line_end = text[line_start..]
        .find('\n')
        .map(|relative| line_start + relative)
        .unwrap_or(text.len());
    let line_text = &text[line_start..line_end];
    let mut utf16 = 0;
    for (relative_offset, ch) in line_text.char_indices() {
        if utf16 == character {
            return Ok(line_start + relative_offset);
        }
        if utf16 > character {
            return Err(LspError::invalid_params(
                "position splits a UTF-16 character",
            ));
        }
        utf16 += ch.len_utf16();
    }
    if utf16 == character {
        return Ok(line_end);
    }
    Err(LspError::invalid_params(format!(
        "character {character} is outside line {line}"
    )))
}

fn line_start_offset(text: &str, target_line: usize) -> LspResult<usize> {
    if target_line == 0 {
        return Ok(0);
    }
    let mut line = 0;
    for (offset, byte) in text.bytes().enumerate() {
        if byte == b'\n' {
            line += 1;
            if line == target_line {
                return Ok(offset + 1);
            }
        }
    }
    Err(LspError::invalid_params(format!(
        "line {target_line} is outside document"
    )))
}

fn range_for_offsets(text: &str, start: usize, end: usize) -> LspResult<LspRange> {
    if start > end
        || end > text.len()
        || !text.is_char_boundary(start)
        || !text.is_char_boundary(end)
    {
        return Err(LspError::server_error(
            "LSP_RANGE_INVALID",
            "backend returned invalid byte offsets",
        ));
    }
    let (start_line, start_character) = position_for_offset(text, start)?;
    let (end_line, end_character) = position_for_offset(text, end)?;
    Ok(LspRange {
        start_line,
        start_character,
        end_line,
        end_character,
    })
}

fn position_for_offset(text: &str, offset: usize) -> LspResult<(usize, usize)> {
    if offset > text.len() || !text.is_char_boundary(offset) {
        return Err(LspError::server_error(
            "LSP_RANGE_INVALID",
            "offset is outside the document or not a character boundary",
        ));
    }
    let mut line = 0;
    let mut line_start = 0;
    for (index, byte) in text.bytes().enumerate() {
        if index == offset {
            break;
        }
        if byte == b'\n' {
            line += 1;
            line_start = index + 1;
        }
    }
    let character = text[line_start..offset]
        .chars()
        .map(char::len_utf16)
        .sum::<usize>();
    Ok((line, character))
}

fn file_uri_to_path(uri: &str) -> LspResult<PathBuf> {
    let raw = uri
        .strip_prefix("file://")
        .ok_or_else(|| LspError::invalid_params(format!("unsupported URI `{uri}`")))?;
    let path = if let Some(path) = raw.strip_prefix("localhost/") {
        format!("/{path}")
    } else if raw.starts_with('/') {
        raw.to_string()
    } else {
        return Err(LspError::invalid_params(format!(
            "unsupported file URI authority in `{uri}`"
        )));
    };
    Ok(PathBuf::from(percent_decode(&path)?))
}

fn path_to_file_uri(path: &str) -> String {
    format!("file://{}", percent_encode_path(path))
}

fn percent_decode(value: &str) -> LspResult<String> {
    let bytes = value.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() {
                return Err(LspError::invalid_params("incomplete percent escape in URI"));
            }
            let hex = std::str::from_utf8(&bytes[index + 1..index + 3])
                .map_err(|_| LspError::invalid_params("invalid percent escape"))?;
            let byte = u8::from_str_radix(hex, 16)
                .map_err(|_| LspError::invalid_params("invalid percent escape"))?;
            decoded.push(byte);
            index += 3;
        } else {
            decoded.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(decoded).map_err(|_| LspError::invalid_params("URI path is not UTF-8"))
}

fn percent_encode_path(path: &str) -> String {
    let mut encoded = String::with_capacity(path.len());
    for byte in path.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'/' | b'.' | b'-' | b'_' | b'~' => {
                encoded.push(byte as char)
            }
            _ => encoded.push_str(&format!("%{byte:02X}")),
        }
    }
    encoded
}
