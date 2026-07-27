pub fn workspace_lease_acquire(args: AgentLeaseAcquireArgs) -> Result<WorkspaceLeaseResult> {
    let requested_root = exact_lease_root(&args.workspace_root)?;
    let admission = admitted_lease_workspace(requested_root, Some(BackendName::Idea))?;
    let initial_installation =
        lease_installation_identity(&admission.workspace_root, admission.backend_name)?;
    let paths = WorkspaceLeasePaths::resolve()?;

    with_workspace_lease_lock(&paths, || {
        let secret = read_or_create_workspace_lease_secret(&paths.secret)?;
        recover_or_reject_existing_lease(&paths, &secret, &admission, &initial_installation)?;
        let locked_installation =
            lease_installation_identity(&admission.workspace_root, admission.backend_name)?;
        if locked_installation != initial_installation {
            return Err(stale_environment_error(
                "The effective agent environment changed before workspace lease acquisition began.",
            ));
        }

        let ensured = ensure_lease_runtime(&admission, args.wait_timeout_ms)?;
        let ownership = if ensured.started {
            WorkspaceLeaseOwnership::Started
        } else {
            WorkspaceLeaseOwnership::Borrowed
        };
        let runtime = runtime_identity(&ensured.selected)?;
        let owner = caller_process_identity()?;
        let acquired_at = crate::manifest::current_timestamp();

        let finalization = (|| {
            let final_installation =
                lease_installation_identity(&admission.workspace_root, admission.backend_name)?;
            if final_installation != initial_installation {
                return Err(stale_environment_error(
                    "The effective agent environment changed while the semantic runtime settled.",
                ));
            }
            require_exact_ready_runtime(
                &admission.workspace_root,
                admission.backend_name,
                &runtime,
            )?;
            let record_id = uuid::Uuid::new_v4();
            let binding = WorkspaceLeaseBinding {
                schema_version: WORKSPACE_LEASE_SCHEMA_VERSION,
                record_id,
                workspace_root: admission.workspace_root.clone(),
                workspace_kind: admission.workspace_kind,
                backend_name: admission.backend_name,
                runtime: runtime.clone(),
                installation: final_installation,
                ownership,
                owner: owner.clone(),
                acquired_at: acquired_at.clone(),
            };
            let claims = WorkspaceLeaseTokenClaims {
                authority: binding.installation.authority,
                generation: binding.installation.generation.clone(),
                environment_sha256: binding.installation.environment_sha256.clone(),
                workspace_root: binding.workspace_root.clone(),
                backend_name: binding.backend_name,
                binding_sha256: workspace_lease_binding_digest(&binding)?,
                record_id,
            };
            let lease_id = sign_workspace_lease_token(&secret, &claims)?;
            write_workspace_lease_record(
                &paths.record(record_id),
                &active_workspace_lease_record(&secret, binding.clone())?,
            )?;
            Ok(workspace_lease_result(
                lease_id,
                WorkspaceLeaseState::Ready,
                binding,
                None,
                None,
            ))
        })();

        if finalization.is_err() && ownership == WorkspaceLeaseOwnership::Started {
            let _ = stop_exact_runtime(&admission.workspace_root, admission.backend_name, &runtime);
        }
        finalization
    })
}

pub fn workspace_lease_status(args: AgentLeaseAccessArgs) -> Result<WorkspaceLeaseResult> {
    access_workspace_lease(args, WorkspaceLeaseAccess::Status)
}

pub fn workspace_lease_release(args: AgentLeaseAccessArgs) -> Result<WorkspaceLeaseResult> {
    access_workspace_lease(args, WorkspaceLeaseAccess::Release)
}

pub fn validate_workspace_lease_for_command(
    lease_id: &AgentWorkspaceLeaseId,
    workspace_root: Option<&Path>,
    backend_name: Option<BackendName>,
) -> Result<()> {
    let workspace_root = workspace_root.ok_or_else(|| {
        CliError::new(
            "WORKSPACE_LEASE_ROOT_REQUIRED",
            "Leased semantic commands require an explicit --workspace-root.",
        )
    })?;
    if backend_name.is_some_and(|backend| backend != BackendName::Idea) {
        return Err(CliError::new(
            "WORKSPACE_LEASE_BACKEND_MISMATCH",
            "Workspace leases bind IntelliJ plugin instances; leased commands cannot select a headless backend.",
        ));
    }
    let args = AgentLeaseAccessArgs {
        lease_id: lease_id.clone(),
        workspace_root: workspace_root.to_path_buf(),
    };
    let result = access_workspace_lease(args, WorkspaceLeaseAccess::Validate)?;
    if result.state != WorkspaceLeaseState::Ready {
        return Err(lease_state_error(result.state, result.failure_reason));
    }
    Ok(())
}

#[derive(Clone, Copy)]
enum WorkspaceLeaseAccess {
    Status,
    Release,
    Validate,
}

fn access_workspace_lease(
    args: AgentLeaseAccessArgs,
    access: WorkspaceLeaseAccess,
) -> Result<WorkspaceLeaseResult> {
    let requested_root = exact_lease_root(&args.workspace_root)?;
    let paths = WorkspaceLeasePaths::resolve()?;
    with_workspace_lease_lock(&paths, || {
        let secret = read_workspace_lease_secret(&paths.secret)?;
        let claims = verify_workspace_lease_token(&secret, args.lease_id.as_str())?;
        validate_token_request_identity(&claims, &requested_root)?;
        let installation =
            lease_installation_identity(&claims.workspace_root, claims.backend_name)?;
        validate_token_environment(&claims, &installation)?;
        let record_path = paths.record(claims.record_id);
        let record = read_workspace_lease_record(&record_path)?;
        validate_workspace_lease_record_mac(&secret, &record)?;
        validate_lease_binding_identity(
            record.binding(),
            &claims,
            &requested_root,
        )?;
        validate_lease_binding_environment(record.binding(), &installation)?;

        match record {
            WorkspaceLeaseRecord::Released {
                binding, receipt, ..
            } => {
                require_current_lease_owner(&binding.owner)?;
                Ok(workspace_lease_result(
                    args.lease_id.as_str().to_string(),
                    WorkspaceLeaseState::Released,
                    binding,
                    None,
                    Some(receipt),
                ))
            }
            WorkspaceLeaseRecord::Active { binding, .. }
                if !owner_identity_is_live(&binding.owner) =>
            {
                match access {
                    WorkspaceLeaseAccess::Release => Err(lease_state_error(
                        WorkspaceLeaseState::Abandoned,
                        Some(WorkspaceLeaseFailureReason::OwnerAbandoned),
                    )),
                    WorkspaceLeaseAccess::Status | WorkspaceLeaseAccess::Validate => {
                        Ok(workspace_lease_result(
                            args.lease_id.as_str().to_string(),
                            WorkspaceLeaseState::Abandoned,
                            binding,
                            Some(WorkspaceLeaseFailureReason::OwnerAbandoned),
                            None,
                        ))
                    }
                }
            }
            WorkspaceLeaseRecord::Active { binding, .. } => {
                require_current_lease_owner(&binding.owner)?;
                match access {
                    WorkspaceLeaseAccess::Release => {
                        let receipt = release_active_binding(&binding)?;
                        write_workspace_lease_record(
                            &record_path,
                            &released_workspace_lease_record(
                                &secret,
                                binding.clone(),
                                receipt.clone(),
                            )?,
                        )?;
                        Ok(workspace_lease_result(
                            args.lease_id.as_str().to_string(),
                            WorkspaceLeaseState::Released,
                            binding,
                            None,
                            Some(receipt),
                        ))
                    }
                    WorkspaceLeaseAccess::Status | WorkspaceLeaseAccess::Validate => {
                        let (state, failure) = observe_active_binding(&binding)?;
                        Ok(workspace_lease_result(
                            args.lease_id.as_str().to_string(),
                            state,
                            binding,
                            failure,
                            None,
                        ))
                    }
                }
            }
        }
    })
}

fn admitted_lease_workspace(
    workspace_root: PathBuf,
    backend_name: Option<BackendName>,
) -> Result<SemanticWorkspaceAdmission> {
    match semantic_workspace_route(Some(workspace_root), backend_name)? {
        SemanticWorkspaceRoute::Admitted(admission) => Ok(admission),
        SemanticWorkspaceRoute::Rejected(rejection) => {
            let mut error = CliError::new(rejection.code, rejection.message);
            error.details.insert(
                "semanticWorkspace".to_string(),
                serde_json::to_string(&rejection.evidence).unwrap_or_default(),
            );
            Err(error)
        }
    }
}

fn exact_lease_root(requested: &Path) -> Result<PathBuf> {
    if !requested.is_absolute() {
        return Err(CliError::new(
            "WORKSPACE_LEASE_ROOT_REQUIRED",
            "Workspace leases require an absolute --workspace-root.",
        ));
    }
    fs::canonicalize(requested).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_ROOT_INVALID",
            format!(
                "Workspace lease root {} could not be canonicalized: {error}",
                requested.display()
            ),
        )
    })
}

fn lease_installation_identity(
    workspace_root: &Path,
    backend_name: BackendName,
) -> Result<WorkspaceLeaseInstallationIdentity> {
    let doctor = self_mgmt::doctor(crate::cli::ReadyTarget::Agent, Some(workspace_root))?;
    let environment = doctor.agent_environment.as_ref().ok_or_else(|| {
        CliError::new(
            "WORKSPACE_LEASE_ENVIRONMENT_NOT_READY",
            "Agent readiness did not produce effective environment evidence.",
        )
    })?;
    if !doctor.ok || !environment.ok {
        let mut error = CliError::new(
            "WORKSPACE_LEASE_ENVIRONMENT_NOT_READY",
            "The effective agent environment is not ready for lease acquisition or use.",
        );
        error
            .details
            .insert("issues".to_string(), doctor.issues.join(" | "));
        return Err(error);
    }
    if environment.backend.kind.as_deref() != Some(backend_name.canonical()) {
        return Err(CliError::new(
            "WORKSPACE_LEASE_BACKEND_MISMATCH",
            format!(
                "Effective agent backend {:?} does not match requested backend {}.",
                environment.backend.kind,
                backend_name.canonical()
            ),
        ));
    }
    let serialized = serde_json::to_vec(environment)?;
    let environment_sha256 = crate::manifest::sha256_bytes(&serialized);
    let (authority, generation) = match doctor.install_authority {
        self_mgmt::InstallAuthority::ActiveRelease => (
            WorkspaceLeaseInstallAuthority::ActiveRelease,
            doctor
                .install
                .as_ref()
                .map(|install| install.release_digest.clone()),
        ),
        self_mgmt::InstallAuthority::Missing => {
            return Err(CliError::new(
                "WORKSPACE_LEASE_AUTHORITY_MISSING",
                "Workspace leases require one effective install authority.",
            ));
        }
    };
    let generation = generation
        .filter(|value| !value.is_empty())
        .ok_or_else(|| {
            CliError::new(
                "WORKSPACE_LEASE_GENERATION_MISSING",
                "The effective install authority did not provide a generation identity.",
            )
        })?;
    Ok(WorkspaceLeaseInstallationIdentity {
        authority,
        generation,
        environment_sha256,
    })
}
