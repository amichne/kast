fn normalize_compiler_diagnostic(
    diagnostic: ProtocolDiagnostic,
    expected_paths: &[&str],
) -> Result<CompilerDiagnosticEvidence> {
    if diagnostic.location.start_offset > diagnostic.location.end_offset
        || diagnostic.location.start_line == 0
        || diagnostic.location.start_column == 0
        || !expected_paths.contains(&diagnostic.location.file_path.as_str())
    {
        return Err(compiler_verification_error(
            "Compiler diagnostic location did not identify one requested exact file.",
        ));
    }
    let normalized_message = diagnostic
        .message
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if normalized_message.is_empty()
        || diagnostic.code.as_ref().is_some_and(|code| code.trim().is_empty())
    {
        return Err(compiler_verification_error(
            "Compiler diagnostic identity contained an empty message or code.",
        ));
    }
    let file_path = diagnostic.location.file_path;
    Ok(CompilerDiagnosticEvidence {
        identity: CompilerDiagnosticIdentity {
            severity: diagnostic.severity,
            code: diagnostic.code.map(|code| code.trim().to_string()),
            canonical_path: file_path.clone(),
            message: normalized_message,
        },
        full_message: diagnostic.message,
        location: CompilerDiagnosticLocationEvidence {
            file_path,
            start_offset: diagnostic.location.start_offset,
            end_offset: diagnostic.location.end_offset,
            start_line: diagnostic.location.start_line,
            start_column: diagnostic.location.start_column,
            preview: diagnostic.location.preview,
        },
    })
}

fn is_normalized_absolute_session_path(raw: &str) -> bool {
    let path = Path::new(raw);
    path.is_absolute()
        && path.components().all(|component| {
            matches!(
                component,
                std::path::Component::RootDir | std::path::Component::Normal(_)
            )
        })
}

fn is_lowercase_session_sha256(raw: &str) -> bool {
    raw.len() == 64
        && raw
            .bytes()
            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
}

fn compiler_verification_error(message: impl Into<String>) -> CliError {
    CliError::new("KAST_COMPILER_VERIFICATION_INVALID", message)
}
