struct VerifiedAddFilePaths {
    directory: PathBuf,
    plan: PathBuf,
    lock: PathBuf,
}

enum VerifiedAddFileInitialPublication {
    Stored,
    Replayed,
}

impl VerifiedAddFilePaths {
    fn new(plan_id: &crate::agent::public_protocol::VerifiedAddFilePlanId) -> Self {
        let directory = manifest::default_install_root().join("state/agent-plans");
        Self {
            plan: directory.join(format!("{}.json", plan_id.as_str())),
            lock: directory.join(format!("{}.lock", plan_id.as_str())),
            directory,
        }
    }
}

fn read_verified_add_file_plan(
    path: &Path,
    expected_id: &crate::agent::public_protocol::VerifiedAddFilePlanId,
) -> Result<StoredVerifiedAddFilePlan> {
    let bytes = read_private_file(path, "KAST_PLAN_UNAVAILABLE")?;
    let mut plan: StoredVerifiedAddFilePlan = serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "KAST_PLAN_INVALID",
            format!("The stored verified add-file plan is malformed: {error}"),
        )
    })?;
    if plan.schema_version != VERIFIED_ADD_FILE_STORE_SCHEMA_VERSION
        || &plan.plan_id != expected_id
        || crate::agent::public_protocol::VerifiedAddFilePlanId::parse(plan.plan_id.as_str())
            .as_ref()
            != Some(expected_id)
        || plan.plan_version.value() != VERIFIED_ADD_FILE_INITIAL_VERSION
    {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored verified add-file authority failed closed validation.",
        ));
    }
    let persisted_root = PathBuf::from(&plan.workspace_root);
    let canonical_root = persisted_root.canonicalize().map_err(|error| {
        CliError::new(
            "KAST_PLAN_INVALID",
            format!("The persisted add-file workspace could not be re-admitted: {error}"),
        )
    })?;
    if canonical_root != persisted_root {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The persisted add-file workspace is not the exact canonical root.",
        ));
    }
    let target =
        VerifiedAddFileTarget::readmit(&canonical_root, plan.target_path.as_str().to_string())?;
    let source = VerifiedAddFileSource::readmit(plan.proposed_content.as_str().to_string())?;
    let postimage = VerifiedAddFileSha256::admit(plan.postimage_sha256.as_str().to_string())?;
    let expected_postimage = VerifiedAddFileSha256::from_source(&source);
    let recomputed_plan_id =
        verified_add_file_plan_id(&plan.workspace_root, &target, &source, plan.planned_generation)?;
    if target != plan.target_path
        || source != plan.proposed_content
        || postimage != expected_postimage
        || postimage != plan.postimage_sha256
        || recomputed_plan_id != plan.plan_id
    {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The persisted add-file request authority no longer matches its deterministic identity.",
        ));
    }
    plan.target_path = target;
    plan.proposed_content = source;
    plan.postimage_sha256 = postimage;
    plan.state = match &plan.state {
        StoredVerifiedAddFileState::AwaitingApproval => StoredVerifiedAddFileState::AwaitingApproval,
        StoredVerifiedAddFileState::ApplyOutcomeUnknown { authority } => {
            StoredVerifiedAddFileState::ApplyOutcomeUnknown {
                authority: authority.admit(&plan)?,
            }
        }
        StoredVerifiedAddFileState::RecoveryRequired { result } => {
            StoredVerifiedAddFileState::RecoveryRequired {
                result: result.admit(&plan)?,
            }
        }
        StoredVerifiedAddFileState::ReconciliationRequired { result } => {
            StoredVerifiedAddFileState::ReconciliationRequired {
                result: result.admit(&plan)?,
            }
        }
        StoredVerifiedAddFileState::Terminal { result } => StoredVerifiedAddFileState::Terminal {
            result: result.admit(&plan)?,
        },
    };
    Ok(plan)
}

fn write_verified_add_file_plan(path: &Path, plan: &StoredVerifiedAddFilePlan) -> Result<()> {
    let mut encoded = serde_json::to_vec(plan)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    let result = fs::rename(&temporary, path)
        .map_err(CliError::from)
        .and_then(|_| sync_directory(path.parent().expect("plan path parent")));
    if result.is_err() {
        remove_if_exists(&temporary);
    }
    result
}

fn publish_initial_verified_add_file_plan(
    path: &Path,
    plan: &StoredVerifiedAddFilePlan,
) -> Result<VerifiedAddFileInitialPublication> {
    let mut encoded = serde_json::to_vec(plan)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    let result = match fs::hard_link(&temporary, path) {
        Ok(()) => sync_directory(path.parent().expect("plan path parent"))
            .map(|()| VerifiedAddFileInitialPublication::Stored),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            re_admit_existing_initial_plan(path, plan)
        }
        Err(error) => Err(error.into()),
    };
    remove_if_exists(&temporary);
    result
}

fn re_admit_existing_initial_plan(
    path: &Path,
    expected: &StoredVerifiedAddFilePlan,
) -> Result<VerifiedAddFileInitialPublication> {
    let existing = read_verified_add_file_plan(path, &expected.plan_id)?;
    if existing.schema_version != expected.schema_version
        || existing.workspace_root != expected.workspace_root
        || existing.plan_id != expected.plan_id
        || existing.plan_version != expected.plan_version
        || existing.target_path != expected.target_path
        || existing.proposed_content != expected.proposed_content
        || existing.postimage_sha256 != expected.postimage_sha256
        || existing.planned_generation != expected.planned_generation
    {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The existing verified add-file plan does not match the reissued server authority.",
        ));
    }
    Ok(VerifiedAddFileInitialPublication::Replayed)
}
