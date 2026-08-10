fn reference_fingerprint(
    runtime: &AgentRuntimeArgs,
    selector: &IssuedSymbolSelector,
) -> Result<String, ProtocolFailure> {
    let root = runtime.workspace_root.as_ref().ok_or_else(|| {
        ProtocolFailure::BackendContractViolation {
            message: "exact operations require one workspace root".to_string(),
        }
    })?;
    let mut digest = Sha256::new();
    digest.update(root.as_os_str().as_encoded_bytes());
    digest.update(b"\nrelation.references\n");
    digest.update(selector.as_str().as_bytes());
    Ok(hex::encode(digest.finalize())[..24].to_string())
}

fn issue_reference_continuation(
    runtime: &AgentRuntimeArgs,
    selector: &IssuedSymbolSelector,
    raw: &str,
) -> Result<String, ProtocolFailure> {
    let raw = canonical_uuid(raw)?;
    Ok(format!(
        "kpc1.references.{}.{}",
        reference_fingerprint(runtime, selector)?,
        raw
    ))
}

fn decode_reference_continuation(
    runtime: &AgentRuntimeArgs,
    selector: &IssuedSymbolSelector,
    value: &str,
) -> Result<String, ProtocolFailure> {
    let fields = value.split('.').collect::<Vec<_>>();
    if fields.len() != 4 || fields[0] != "kpc1" || fields[1] != "references" {
        return Err(ProtocolFailure::ContinuationInvalid);
    }
    if fields[2] != reference_fingerprint(runtime, selector)? {
        return Err(ProtocolFailure::ContinuationMismatch);
    }
    canonical_uuid(fields[3])
}

fn canonical_uuid(value: &str) -> Result<String, ProtocolFailure> {
    let parsed = uuid::Uuid::parse_str(value).map_err(|_| ProtocolFailure::ContinuationInvalid)?;
    let canonical = parsed.hyphenated().to_string();
    if canonical != value {
        return Err(ProtocolFailure::ContinuationInvalid);
    }
    Ok(canonical)
}
