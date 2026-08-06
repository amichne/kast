#[derive(Debug)]
enum UserCommandInstallFailure {
    BeforeReceipt(CliError),
    AfterReceipt(CliError),
}

fn install_user_commands(
    targets: &ActivationTargetPaths,
    profile: manifest::SetupProfile,
    path_projection_authority: &PathProjectionAuthority,
    existing_agent_projection: Option<AgentCommandProjection>,
) -> std::result::Result<(), UserCommandInstallFailure> {
    let local_bin = manifest::home_dir().join(".local/bin");
    let obsolete_control = local_bin.join("_kastctl");
    if managed_user_command(&obsolete_control, &targets.resolved.install_root, &[]) {
        manifest::remove_path(&obsolete_control)
            .map_err(UserCommandInstallFailure::BeforeReceipt)?;
    }
    let user_command = local_bin.join("kast");
    let agent_binary = targets.current_link.join(AGENT_CLI_BUNDLE_PATH);
    let control_command = local_bin.join("kastctl");
    let control_binary = path_projection_authority.control_target().to_path_buf();
    let receipt_path = targets.current_link.join(manifest::INSTALL_MANIFEST_FILE);
    let mut receipt = manifest_from_file(&receipt_path).map_err(|error| {
        let mut error = at_setup_step(error, "READ_INSTALL_RECEIPT");
        error.details.insert(
            "receiptPath".to_string(),
            receipt_path.display().to_string(),
        );
        error.details.insert(
            "currentTarget".to_string(),
            fs::read_link(&targets.current_link)
                .map(|path| path.display().to_string())
                .unwrap_or_else(|read_error| format!("unavailable: {read_error}")),
        );
        error.details.insert(
            "versionReceiptExists".to_string(),
            targets
                .version_dir
                .join(manifest::INSTALL_MANIFEST_FILE)
                .is_file()
                .to_string(),
        );
        UserCommandInstallFailure::BeforeReceipt(error)
    })?;
    receipt.owned_paths.retain(|path| {
        let path = Path::new(path);
        path != obsolete_control && path != user_command && path != control_command
    });
    let user_command_state = user_command.display().to_string();
    receipt.owned_paths.push(user_command_state);
    receipt.setup_profile = profile;
    receipt.schema_version = crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION;
    receipt.updated_at = manifest::current_timestamp();
    receipt.path_projections = vec![manifest::PathProjectionReceipt {
        command: manifest::PathProjectionCommand::Kast,
        path: user_command.display().to_string(),
        target: agent_binary.display().to_string(),
    }];
    if profile.projects_control_command() {
        receipt
            .owned_paths
            .push(control_command.display().to_string());
        receipt
            .path_projections
            .push(manifest::PathProjectionReceipt {
                command: manifest::PathProjectionCommand::Kastctl,
                path: control_command.display().to_string(),
                target: control_binary.display().to_string(),
            });
    }
    #[cfg(unix)]
    {
        let agent_projection = match existing_agent_projection {
            Some(projection) => projection,
            None => {
                project_agent_command(targets).map_err(UserCommandInstallFailure::BeforeReceipt)?
            }
        };
        let receipt_publication = (|| {
            let mut transaction = path_projection_authority
                .begin_transaction(
                    profile,
                    &receipt_path,
                    &receipt.release_digest,
                    &targets.resolved.install_root,
                )
                .map_err(UserCommandInstallFailure::BeforeReceipt)?;
            if let Some(transaction) = transaction.as_mut() {
                transaction.apply().map_err(|error| {
                    UserCommandInstallFailure::BeforeReceipt(at_setup_step(
                        error,
                        "PROJECT_CONTROL_COMMAND",
                    ))
                })?;
            }
            test_path_projection_crash("after-control-apply");
            let receipt_write = test_path_projection_failure("before-receipt-write")
                .and_then(|()| manifest::write_manifest_atomic(&receipt_path, &receipt));
            if let Err(mut error) = receipt_write {
                if exact_install_receipt_is_visible(&receipt_path, &receipt) {
                    error
                        .details
                        .insert("receiptVisible".to_string(), "true".to_string());
                    error
                        .details
                        .insert("receiptDurability".to_string(), "AMBIGUOUS".to_string());
                    return Err(UserCommandInstallFailure::AfterReceipt(at_setup_step(
                        error,
                        "WRITE_INSTALL_RECEIPT",
                    )));
                }
                if let Some(transaction) = transaction.take()
                    && let Err(rollback_error) = transaction.rollback_preserving_journal()
                {
                    error.details.insert(
                        "pathProjectionRollbackError".to_string(),
                        rollback_error.to_string(),
                    );
                }
                return Err(UserCommandInstallFailure::BeforeReceipt(at_setup_step(
                    error,
                    "WRITE_INSTALL_RECEIPT",
                )));
            }
            Ok(transaction)
        })();
        let transaction = match receipt_publication {
            Ok(transaction) => transaction,
            Err(UserCommandInstallFailure::BeforeReceipt(mut error)) => {
                if let Err(rollback_error) = agent_projection.rollback() {
                    error.details.insert(
                        "agentProjectionRollbackError".to_string(),
                        rollback_error.to_string(),
                    );
                }
                return Err(UserCommandInstallFailure::BeforeReceipt(error));
            }
            Err(UserCommandInstallFailure::AfterReceipt(error)) => {
                return Err(UserCommandInstallFailure::AfterReceipt(error));
            }
        };
        test_path_projection_crash("after-receipt-commit");
        if let Err(mut error) = test_path_projection_failure("before-control-transaction-finalize")
        {
            error
                .details
                .insert("receiptCommitted".to_string(), "true".to_string());
            return Err(UserCommandInstallFailure::AfterReceipt(at_setup_step(
                error,
                "COMMIT_CONTROL_PROJECTION",
            )));
        }
        if let Some(transaction) = transaction
            && let Err(mut error) = transaction.commit()
        {
            error
                .details
                .insert("receiptCommitted".to_string(), "true".to_string());
            return Err(UserCommandInstallFailure::AfterReceipt(at_setup_step(
                error,
                "COMMIT_CONTROL_PROJECTION",
            )));
        }
    }
    #[cfg(not(unix))]
    {
        let _ = (
            user_command,
            agent_binary,
            control_command,
            control_binary,
            existing_agent_projection,
        );
        if let Err(mut error) = manifest::write_manifest_atomic(&receipt_path, &receipt) {
            let failure = if exact_install_receipt_is_visible(&receipt_path, &receipt) {
                error
                    .details
                    .insert("receiptVisible".to_string(), "true".to_string());
                error
                    .details
                    .insert("receiptDurability".to_string(), "AMBIGUOUS".to_string());
                UserCommandInstallFailure::AfterReceipt(error)
            } else {
                UserCommandInstallFailure::BeforeReceipt(error)
            };
            return Err(failure);
        }
    }
    Ok(())
}

fn exact_install_receipt_is_visible(path: &Path, receipt: &manifest::KastInstallManifest) -> bool {
    let Ok(mut expected) = serde_json::to_vec_pretty(receipt) else {
        return false;
    };
    expected.push(b'\n');
    fs::read(path).is_ok_and(|contents| contents == expected)
}

#[cfg(unix)]
fn project_agent_command(targets: &ActivationTargetPaths) -> Result<AgentCommandProjection> {
    let path = manifest::home_dir().join(".local/bin/kast");
    let target = targets.current_link.join(AGENT_CLI_BUNDLE_PATH);
    AgentCommandProjection::project(&path, &target)
        .map_err(|error| at_setup_step(error, "PROJECT_USER_COMMAND"))
}

#[cfg(not(unix))]
fn project_agent_command(_targets: &ActivationTargetPaths) -> Result<AgentCommandProjection> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "PATH projection requires a Unix platform.",
    ))
}
