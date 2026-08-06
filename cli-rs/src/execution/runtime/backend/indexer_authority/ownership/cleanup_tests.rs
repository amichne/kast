use super::*;
use super::super::process::ManagedProcessIdentity;
use super::super::registration::{
    ServiceManagerRegistration, ServiceProcessClaim, ServiceRegistrationReceipt,
};

const LAUNCH_SHA256: &str =
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

#[test]
fn dead_process_claim_temporary_is_recovered_final_registration_review_regression() {
    let fixture = ProcessClaimTemporaryFixture::new(gone_process_identity());

    verify_registration_directory_entries(&fixture.registration)
        .expect("canonical process temporary");
    let proven_dead = prove_dead_process_claim_publication_temporary(&fixture.registration)
        .expect("dead process temporary proof");
    recover_dead_process_claim_publication_temporary(&fixture.registration, &proven_dead)
        .expect("dead process temporary recovery");

    assert!(!fixture.temporary.exists());
}

#[test]
fn live_process_claim_temporary_blocks_before_manager_mutation_final_registration_review_regression()
{
    let process = super::observe_process(u64::from(std::process::id()))
        .expect("process observation")
        .expect("current process")
        .identity;
    let fixture = ProcessClaimTemporaryFixture::new(process);
    let ServiceManagerRegistration::Test { state_path, .. } =
        &fixture.registration.receipt.manager
    else {
        panic!("test manager registration");
    };
    let manager_state = Path::new(state_path);
    let registered_state = serde_json::to_vec(&serde_json::json!({"pid": 0}))
        .expect("registered manager state JSON");
    fs::write(manager_state, &registered_state).expect("registered manager state");

    let error = unregister_dead_service_manager(&fixture.registration)
        .expect_err("live process temporary must block cleanup");

    assert_eq!(error.code, "RUNTIME_OWNERSHIP_CHANGED");
    assert_eq!(
        fs::read(manager_state).expect("manager state"),
        registered_state
    );
    assert!(fixture.temporary.exists());
}

#[test]
fn reused_process_claim_temporary_is_recovered_final_registration_review_regression() {
    let fixture = ProcessClaimTemporaryFixture::new(reused_process_identity());

    let proven_dead = prove_dead_process_claim_publication_temporary(&fixture.registration)
        .expect("reused process temporary proof");
    recover_dead_process_claim_publication_temporary(&fixture.registration, &proven_dead)
        .expect("reused process temporary recovery");

    assert!(!fixture.temporary.exists());
}

#[test]
fn duplicate_process_claim_temporaries_stay_blocking_final_registration_review_regression() {
    let fixture = ProcessClaimTemporaryFixture::new(gone_process_identity());
    fixture.write_temporary("22222222-2222-4222-8222-222222222222", gone_process_identity());

    let error = verify_registration_directory_entries(&fixture.registration)
        .expect_err("duplicate process temporaries must block cleanup");

    assert_eq!(error.code, "RUNTIME_OWNERSHIP_CHANGED");
    assert!(fixture.temporary.exists());
}

struct ProcessClaimTemporaryFixture {
    _temp: tempfile::TempDir,
    registration: ValidatedServiceRegistration,
    temporary: PathBuf,
}

impl ProcessClaimTemporaryFixture {
    fn new(process: ManagedProcessIdentity) -> Self {
        let temp = tempfile::tempdir().expect("registration root");
        let directory = temp.path().join("registration");
        fs::create_dir(&directory).expect("registration directory");
        let registration = validated_registration(&directory);
        let temporary = Self::temporary_path(&directory, "11111111-1111-4111-8111-111111111111");
        write_process_claim_temporary(&temporary, process);
        Self {
            _temp: temp,
            registration,
            temporary,
        }
    }

    fn write_temporary(&self, id: &str, process: ManagedProcessIdentity) {
        write_process_claim_temporary(&Self::temporary_path(&self.registration.directory, id), process);
    }

    fn temporary_path(directory: &Path, id: &str) -> PathBuf {
        directory.join(format!(".runtime-{id}.tmp"))
    }
}

fn write_process_claim_temporary(path: &Path, process: ManagedProcessIdentity) {
    let claim = ServiceProcessClaim {
        schema_version: 1,
        launch_sha256: LAUNCH_SHA256.to_string(),
        process,
    };
    fs::write(
        path,
        serde_json::to_vec_pretty(&claim).expect("process claim JSON"),
    )
    .expect("process claim temporary");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))
            .expect("private process claim temporary");
    }
}

fn gone_process_identity() -> ManagedProcessIdentity {
    ManagedProcessIdentity {
        pid: u64::MAX,
        start_key: "gone-process".to_string(),
        start_epoch_millis: 1,
        owner_uid: u64::from(unsafe { libc::geteuid() }),
    }
}

fn reused_process_identity() -> ManagedProcessIdentity {
    ManagedProcessIdentity {
        pid: u64::from(std::process::id()),
        start_key: "reused-process".to_string(),
        start_epoch_millis: 1,
        owner_uid: u64::from(unsafe { libc::geteuid() }),
    }
}

fn validated_registration(directory: &Path) -> ValidatedServiceRegistration {
    let runtime_instance_id = uuid::Uuid::nil();
    let launch = serde_json::from_value(serde_json::json!({
        "schemaVersion": 1,
        "workspaceRoot": "/workspace",
        "workspaceKey": "workspace-key",
        "runtimeInstanceId": runtime_instance_id,
        "ownerUid": u64::from(unsafe { libc::geteuid() }),
        "workingDirectory": "/workspace",
        "command": ["/bin/true"],
        "environment": [],
        "logFile": "/runtime.log",
        "descriptorDirectory": "/descriptors",
        "socketPath": "/runtime.sock",
        "launcherPath": "/bin/true",
        "launcherSha256": "0".repeat(64),
        "runtimeConfigPath": directory.join("runtime-config.json"),
        "runtimeConfigSha256": "0".repeat(64)
    }))
    .expect("service launch registration");
    ValidatedServiceRegistration {
        directory: directory.to_path_buf(),
        receipt_path: directory.join("receipt.json"),
        receipt_sha256: "0".repeat(64),
        receipt: ServiceRegistrationReceipt {
            schema_version: 1,
            workspace_root: "/workspace".to_string(),
            workspace_key: "workspace-key".to_string(),
            runtime_instance_id,
            launch_path: directory.join("launch.json").display().to_string(),
            launch_sha256: LAUNCH_SHA256.to_string(),
            definition_sha256: "0".repeat(64),
            manager: ServiceManagerRegistration::Test {
                state_path: directory.join("manager.json").display().to_string(),
                definition_path: directory.join("service.test.json").display().to_string(),
            },
        },
        launch,
    }
}
