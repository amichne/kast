#[path = "../../support/mod.rs"]
mod support;

include!("install_and_migration.rs");
include!("legacy_runtime_fixtures.rs");
include!("force_reset.rs");
include!("migration_and_rollback.rs");
include!("activation_and_concurrency.rs");
