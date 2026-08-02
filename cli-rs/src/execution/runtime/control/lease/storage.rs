
fn read_or_create_workspace_lease_secret(path: &Path) -> Result<Vec<u8>> {
    if path.is_file() {
        return read_workspace_lease_secret(path);
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut bytes = Vec::with_capacity(32);
    bytes.extend_from_slice(uuid::Uuid::new_v4().as_bytes());
    bytes.extend_from_slice(uuid::Uuid::new_v4().as_bytes());
    let encoded = hex::encode(&bytes);
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    match options.open(path) {
        Ok(mut file) => {
            file.write_all(encoded.as_bytes())?;
            file.sync_all()?;
            Ok(bytes)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            read_workspace_lease_secret(path)
        }
        Err(error) => Err(error.into()),
    }
}

fn read_workspace_lease_secret(path: &Path) -> Result<Vec<u8>> {
    let encoded = fs::read_to_string(path).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_SECRET_MISSING",
            format!("Workspace lease signing key is unavailable: {error}"),
        )
    })?;
    let bytes = hex::decode(encoded.trim()).map_err(|_| {
        CliError::new(
            "WORKSPACE_LEASE_SECRET_INVALID",
            "Workspace lease signing key is not valid hexadecimal.",
        )
    })?;
    if bytes.len() != 32 {
        return Err(CliError::new(
            "WORKSPACE_LEASE_SECRET_INVALID",
            "Workspace lease signing key must contain exactly 32 bytes.",
        ));
    }
    Ok(bytes)
}

pub(crate) fn sign_install_scoped_token<T: Serialize>(
    version: &str,
    claims: &T,
) -> Result<String> {
    let paths = WorkspaceLeasePaths::resolve()?;
    let secret = read_or_create_workspace_lease_secret(&paths.secret)?;
    let payload = hex::encode(serde_json::to_vec(claims)?);
    let authenticated = format!("{version}.{payload}");
    let signature = workspace_lease_hmac_sha256(&secret, authenticated.as_bytes());
    Ok(format!("{authenticated}.{}", hex::encode(signature)))
}

pub(crate) fn verify_install_scoped_token<T: serde::de::DeserializeOwned>(
    version: &str,
    token: &str,
) -> Result<Option<T>> {
    if token.len() > 16_384 || !token.is_ascii() || token.chars().any(char::is_control) {
        return Ok(None);
    }
    let mut parts = token.split('.');
    let actual_version = parts.next();
    let payload = parts.next();
    let signature = parts.next();
    if actual_version != Some(version)
        || payload.is_none()
        || signature.is_none()
        || parts.next().is_some()
    {
        return Ok(None);
    }
    let payload = payload.expect("checked token payload");
    let signature = match hex::decode(signature.expect("checked token signature")) {
        Ok(signature) => signature,
        Err(_) => return Ok(None),
    };
    let paths = WorkspaceLeasePaths::resolve()?;
    let secret = read_workspace_lease_secret(&paths.secret)?;
    let authenticated = format!("{version}.{payload}");
    let expected = workspace_lease_hmac_sha256(&secret, authenticated.as_bytes());
    if !constant_time_equal(&signature, &expected) {
        return Ok(None);
    }
    let payload = match hex::decode(payload) {
        Ok(payload) => payload,
        Err(_) => return Ok(None),
    };
    Ok(serde_json::from_slice(&payload).ok())
}

fn sign_workspace_lease_token(secret: &[u8], claims: &WorkspaceLeaseTokenClaims) -> Result<String> {
    let payload = serde_json::to_vec(claims)?;
    let signature = workspace_lease_hmac_sha256(secret, &payload);
    Ok(format!(
        "{WORKSPACE_LEASE_TOKEN_VERSION}.{}.{}",
        hex::encode(payload),
        hex::encode(signature)
    ))
}

fn verify_workspace_lease_token(secret: &[u8], token: &str) -> Result<WorkspaceLeaseTokenClaims> {
    let mut parts = token.split('.');
    let version = parts.next();
    let payload = parts.next();
    let signature = parts.next();
    if version != Some(WORKSPACE_LEASE_TOKEN_VERSION)
        || payload.is_none()
        || signature.is_none()
        || parts.next().is_some()
    {
        return Err(tampered_lease_error());
    }
    let payload =
        hex::decode(payload.expect("checked token payload")).map_err(|_| tampered_lease_error())?;
    let signature = hex::decode(signature.expect("checked token signature"))
        .map_err(|_| tampered_lease_error())?;
    let expected = workspace_lease_hmac_sha256(secret, &payload);
    if !constant_time_equal(&signature, &expected) {
        return Err(tampered_lease_error());
    }
    serde_json::from_slice(&payload).map_err(|_| tampered_lease_error())
}

fn tampered_lease_error() -> CliError {
    CliError::new(
        "WORKSPACE_LEASE_TAMPERED",
        "Workspace lease identity is malformed or failed authentication.",
    )
}

fn workspace_lease_hmac_sha256(secret: &[u8], payload: &[u8]) -> [u8; 32] {
    use sha2::{Digest, Sha256};
    const BLOCK: usize = 64;
    let mut key = [0_u8; BLOCK];
    if secret.len() > BLOCK {
        key[..32].copy_from_slice(&Sha256::digest(secret));
    } else {
        key[..secret.len()].copy_from_slice(secret);
    }
    let mut inner_pad = [0x36_u8; BLOCK];
    let mut outer_pad = [0x5c_u8; BLOCK];
    for index in 0..BLOCK {
        inner_pad[index] ^= key[index];
        outer_pad[index] ^= key[index];
    }
    let mut inner = Sha256::new();
    inner.update(inner_pad);
    inner.update(payload);
    let inner = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(outer_pad);
    outer.update(inner);
    outer.finalize().into()
}

fn constant_time_equal(actual: &[u8], expected: &[u8]) -> bool {
    if actual.len() != expected.len() {
        return false;
    }
    actual
        .iter()
        .zip(expected)
        .fold(0_u8, |difference, (left, right)| {
            difference | (left ^ right)
        })
        == 0
}

fn write_workspace_lease_record(path: &Path, record: &WorkspaceLeaseRecord) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("json.tmp-{}", std::process::id()));
    let result = (|| {
        use std::io::Write;
        let mut file = fs::File::create(&temporary)?;
        serde_json::to_writer_pretty(&mut file, record)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn read_workspace_lease_record(path: &Path) -> Result<WorkspaceLeaseRecord> {
    let bytes = fs::read(path).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_UNKNOWN",
            format!("Workspace lease record is unavailable: {error}"),
        )
    })?;
    serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_RECORD_INVALID",
            format!("Workspace lease record is invalid: {error}"),
        )
    })
}

#[cfg(test)]
mod workspace_lease_tests {
    use super::*;

    #[test]
    fn hmac_matches_rfc_4231_case_one() {
        let key = [0x0b_u8; 20];
        let actual = workspace_lease_hmac_sha256(&key, b"Hi There");
        assert_eq!(
            hex::encode(actual),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
    }

    #[test]
    fn signed_token_rejects_tampering() {
        let claims = WorkspaceLeaseTokenClaims {
            authority: WorkspaceLeaseInstallAuthority::ActiveRelease,
            generation: "generation-1".to_string(),
            environment_sha256: "a".repeat(64),
            workspace_root: PathBuf::from("/workspace"),
            backend_name: BackendName::Indexer,
            binding_sha256: "b".repeat(64),
            record_id: uuid::Uuid::new_v4(),
        };
        let secret = [7_u8; 32];
        let token = sign_workspace_lease_token(&secret, &claims).expect("token");
        assert_eq!(
            verify_workspace_lease_token(&secret, &token).expect("verified token"),
            claims
        );
        let mut tampered = token.into_bytes();
        let last = tampered.last_mut().expect("last token byte");
        *last = if *last == b'0' { b'1' } else { b'0' };
        let error = verify_workspace_lease_token(
            &secret,
            std::str::from_utf8(&tampered).expect("UTF-8 token"),
        )
        .expect_err("tamper must fail");
        assert_eq!(error.code, "WORKSPACE_LEASE_TAMPERED");
    }

    #[test]
    fn fake_process_identity_rejects_pid_reuse_shape() {
        let expected = WorkspaceLeaseProcessIdentity {
            pid: 42,
            started_at: "fake-start-1".to_string(),
        };
        let replaced = WorkspaceLeaseProcessIdentity {
            pid: 42,
            started_at: "fake-start-2".to_string(),
        };
        assert!(process_identity_matches(&expected, Some(&expected)));
        assert!(!process_identity_matches(&expected, Some(&replaced)));
        assert!(!process_identity_matches(&expected, None));
    }

    #[test]
    fn fake_runtime_identity_rejects_same_pid_with_replaced_descriptor_or_registry_entry() {
        let descriptor = ServerInstanceDescriptor {
            workspace_root: "/workspace".to_string(),
            backend_name: "indexer".to_string(),
            backend_version: "revision-1".to_string(),
            runtime_instance_id: Some("instance-1".to_string()),
            process_start_epoch_millis: Some(1),
            owner_uid: Some(1),
            socket_file_identity: Some(RuntimeSocketFileIdentity {
                device: 1,
                inode: 1,
            }),
            transport: "uds".to_string(),
            socket_path: "/tmp/runtime-1.sock".to_string(),
            pid: 42,
            schema_version: SCHEMA_VERSION,
        };
        let expected = WorkspaceLeaseRuntimeIdentity {
            descriptor_path: "registry-entry-1".to_string(),
            descriptor: descriptor.clone(),
            process: WorkspaceLeaseProcessIdentity {
                pid: 42,
                started_at: "fake-start-1".to_string(),
            },
        };
        assert!(runtime_descriptor_matches(
            &descriptor,
            "registry-entry-1",
            &expected,
        ));

        let mut replacement = descriptor;
        replacement.socket_path = "/tmp/runtime-2.sock".to_string();
        assert!(!runtime_descriptor_matches(
            &replacement,
            "registry-entry-1",
            &expected,
        ));
        assert!(!runtime_descriptor_matches(
            &expected.descriptor,
            "registry-entry-2",
            &expected,
        ));
    }

    #[test]
    fn token_environment_rejects_a_stale_active_release() {
        let claims = WorkspaceLeaseTokenClaims {
            authority: WorkspaceLeaseInstallAuthority::ActiveRelease,
            generation: "generation-1".to_string(),
            environment_sha256: "a".repeat(64),
            workspace_root: PathBuf::from("/workspace"),
            backend_name: BackendName::Indexer,
            binding_sha256: "b".repeat(64),
            record_id: uuid::Uuid::new_v4(),
        };
        let stale = WorkspaceLeaseInstallationIdentity {
            authority: claims.authority,
            generation: "generation-2".to_string(),
            environment_sha256: claims.environment_sha256.clone(),
        };
        assert_eq!(
            validate_token_environment(&claims, &stale)
                .expect_err("stale generation")
                .code,
            "WORKSPACE_LEASE_STALE_ENVIRONMENT"
        );
    }

    #[test]
    fn retired_backend_claim_cannot_enter_the_active_lease_type() {
        assert!(serde_json::from_str::<BackendName>("\"idea\"").is_err());
    }
}
