struct VerifiedAddDeclarationPaths {
    directory: PathBuf,
    plan: PathBuf,
    lock: PathBuf,
}

impl VerifiedAddDeclarationPaths {
    fn new(plan_id: &crate::agent::public_protocol::VerifiedAddDeclarationPlanId) -> Self {
        let directory = manifest::default_install_root().join("state/agent-plans");
        Self {
            plan: directory.join(format!("{}.json", plan_id.as_str())),
            lock: directory.join(format!("{}.lock", plan_id.as_str())),
            directory,
        }
    }
}

fn read_verified_add_declaration_plan(
    path: &Path,
    expected_id: &crate::agent::public_protocol::VerifiedAddDeclarationPlanId,
) -> Result<StoredVerifiedAddDeclarationPlan> {
    let bytes = read_private_file(path, "KAST_PLAN_UNAVAILABLE")?;
    let plan: StoredVerifiedAddDeclarationPlan = serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "KAST_PLAN_INVALID",
            format!("The stored verified add-declaration plan is malformed: {error}"),
        )
    })?;
    if plan.schema_version != VERIFIED_ADD_DECLARATION_STORE_SCHEMA_VERSION
        || &plan.plan_id != expected_id
        || crate::agent::public_protocol::VerifiedAddDeclarationPlanId::parse(
            plan.plan_id.as_str(),
        )
        .as_ref()
            != Some(expected_id)
        || plan.plan_version.value() != VERIFIED_ADD_DECLARATION_INITIAL_VERSION
    {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored verified add-declaration authority failed closed validation.",
        ));
    }
    if let StoredVerifiedAddDeclarationState::Terminal { receipt } = &plan.state {
        receipt.clone().admit(&plan)?;
    }
    Ok(plan)
}

fn write_verified_add_declaration_plan(
    path: &Path,
    plan: &StoredVerifiedAddDeclarationPlan,
    replace: bool,
) -> Result<()> {
    let mut encoded = serde_json::to_vec(plan)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    let result = if replace {
        fs::rename(&temporary, path)
            .map_err(CliError::from)
            .and_then(|_| sync_directory(path.parent().expect("plan path parent")))
    } else {
        rename_private_file(&temporary, path)
    };
    if result.is_err() {
        remove_if_exists(&temporary);
    }
    result
}

fn canonical_lowercase_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}
