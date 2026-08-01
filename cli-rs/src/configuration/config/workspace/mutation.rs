use toml_edit::{DocumentMut, Item, Table, TableLike};

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MutableConfigField {
    pub key: &'static str,
    pub value_type: ConfigValueType,
    pub workspace_override: bool,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum ConfigValueType {
    Boolean,
    Integer,
    String,
    StringList,
}

#[derive(Debug, Clone, Copy)]
enum StringListField {
    CriticalPaths,
    IgnoredPaths,
}

#[derive(Debug, Clone, Copy)]
struct ConfigFieldSpec {
    field: MutableConfigField,
    minimum: Option<i64>,
    string_list: Option<StringListField>,
}

impl ConfigFieldSpec {
    const fn new(key: &'static str, value_type: ConfigValueType) -> Self {
        Self {
            field: MutableConfigField {
                key,
                value_type,
                workspace_override: false,
            },
            minimum: None,
            string_list: None,
        }
    }

    const fn positive(key: &'static str) -> Self {
        Self {
            field: MutableConfigField {
                key,
                value_type: ConfigValueType::Integer,
                workspace_override: false,
            },
            minimum: Some(1),
            string_list: None,
        }
    }

    const fn string_list(key: &'static str, string_list: StringListField) -> Self {
        Self {
            field: MutableConfigField {
                key,
                value_type: ConfigValueType::StringList,
                workspace_override: false,
            },
            minimum: None,
            string_list: Some(string_list),
        }
    }
}

const MUTABLE_CONFIG_FIELDS: &[ConfigFieldSpec] = &[
    ConfigFieldSpec::positive("server.maxResults"),
    ConfigFieldSpec::positive("server.requestTimeoutMillis"),
    ConfigFieldSpec::positive("server.maxConcurrentRequests"),
    ConfigFieldSpec::new("runtime.defaultBackend", ConfigValueType::String),
    ConfigFieldSpec::new("runtime.strictPluginMatching", ConfigValueType::Boolean),
    ConfigFieldSpec::new("projectOpen.profileAutoInit", ConfigValueType::Boolean),
    ConfigFieldSpec::new("projectOpen.profile", ConfigValueType::String),
    ConfigFieldSpec::new("projectOpen.autoExcludeGit", ConfigValueType::Boolean),
    ConfigFieldSpec::new("projectOpen.gradleLoadEnabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("codex.hooks.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("codex.hooks.sessionStart", ConfigValueType::Boolean),
    ConfigFieldSpec::new("codex.hooks.postToolUse", ConfigValueType::Boolean),
    ConfigFieldSpec::string_list("indexing.criticalPaths", StringListField::CriticalPaths),
    ConfigFieldSpec::string_list("indexing.ignoredPaths", StringListField::IgnoredPaths),
    ConfigFieldSpec::positive("indexing.graph.batchSize"),
    ConfigFieldSpec::new("indexing.relationships.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::positive("indexing.relationships.batchSize"),
    ConfigFieldSpec::positive("indexing.relationships.parallelism"),
    ConfigFieldSpec::new(
        "indexing.relationships.modulePriorityDepth",
        ConfigValueType::Integer,
    ),
    ConfigFieldSpec::new(
        "indexing.identifierIndexWaitMillis",
        ConfigValueType::Integer,
    ),
    ConfigFieldSpec::new("indexing.remote.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("cache.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("cache.writeDelayMillis", ConfigValueType::Integer),
    ConfigFieldSpec::new(
        "cache.sourceIndexSaveDelayMillis",
        ConfigValueType::Integer,
    ),
    ConfigFieldSpec::new("watcher.debounceMillis", ConfigValueType::Integer),
    ConfigFieldSpec::positive("gradle.toolingApiTimeoutMillis"),
    ConfigFieldSpec::new("telemetry.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("telemetry.scopes", ConfigValueType::String),
    ConfigFieldSpec::new("telemetry.detail", ConfigValueType::String),
    ConfigFieldSpec::new("profiling.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("profiling.modes", ConfigValueType::String),
    ConfigFieldSpec::positive("profiling.durationSeconds"),
    ConfigFieldSpec::new("profiling.emitManifest", ConfigValueType::Boolean),
    ConfigFieldSpec::new("backends.headless.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("backends.idea.enabled", ConfigValueType::Boolean),
    ConfigFieldSpec::new("cli.dynamicOutput", ConfigValueType::Boolean),
];

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigFileState {
    scope: &'static str,
    path: String,
    exists: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceConfigList {
    ok: bool,
    workspace_root: String,
    config_path: String,
    config_files: Vec<ConfigFileState>,
    effective: KastConfig,
    mutable_fields: Vec<MutableConfigField>,
    schema_version: u32,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ConfigMutationStatus {
    Updated,
    Unchanged,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceConfigMutation {
    ok: bool,
    status: ConfigMutationStatus,
    workspace_root: String,
    config_path: String,
    key: String,
    effective_value: serde_json::Value,
    schema_version: u32,
}

pub fn list_workspace_config(workspace_root: PathBuf) -> Result<WorkspaceConfigList> {
    let workspace_root = resolve_workspace_root(Some(workspace_root))?;
    let workspace_config = workspace_config_path(&workspace_root)?;
    let workspace_document = read_workspace_config(&workspace_config)?;
    let global_config = global_config_path();
    Ok(WorkspaceConfigList {
        ok: true,
        workspace_root: workspace_root.display().to_string(),
        config_path: workspace_config.display().to_string(),
        config_files: vec![
            ConfigFileState {
                scope: "global",
                path: global_config.display().to_string(),
                exists: global_config.is_file(),
            },
            ConfigFileState {
                scope: "workspace",
                path: workspace_config.display().to_string(),
                exists: workspace_config.is_file(),
            },
        ],
        effective: KastConfig::load(&workspace_root)?,
        mutable_fields: MUTABLE_CONFIG_FIELDS
            .iter()
            .map(|spec| {
                let path = spec.field.key.split('.').collect::<Vec<_>>();
                MutableConfigField {
                    workspace_override: document_contains_value(
                        workspace_document.as_table(),
                        &path,
                    ),
                    ..spec.field
                }
            })
            .collect(),
        schema_version: SCHEMA_VERSION,
    })
}

pub fn set_workspace_config(
    workspace_root: PathBuf,
    key: String,
    raw_value: String,
) -> Result<WorkspaceConfigMutation> {
    let workspace_root = resolve_workspace_root(Some(workspace_root))?;
    let config_path = workspace_config_path(&workspace_root)?;
    let spec = config_field(&key)?;
    if spec.string_list.is_some() {
        return Err(CliError::new(
            "CONFIG_VALUE_INVALID",
            format!("{key} is a string-list field; use `kast config add` or `kast config remove`"),
        ));
    }
    let mut document = read_workspace_config(&config_path)?;
    let before = document.to_string();
    set_document_value(
        document.as_table_mut(),
        &key.split('.').collect::<Vec<_>>(),
        parse_config_value(spec, &raw_value)?,
    )?;
    let after = document.to_string();
    validate_toml(&after).map_err(|error| {
        CliError::new(
            "CONFIG_VALUE_INVALID",
            format!("Invalid value for {key}: {}", error.message),
        )
    })?;
    let status = if before == after {
        ConfigMutationStatus::Unchanged
    } else {
        write_workspace_config(&config_path, &after)?;
        ConfigMutationStatus::Updated
    };
    mutation_result(workspace_root, config_path, key, status)
}

pub fn add_workspace_config(
    workspace_root: PathBuf,
    key: String,
    pattern: String,
) -> Result<WorkspaceConfigMutation> {
    mutate_workspace_string_list(workspace_root, key, pattern, StringListMutation::Add)
}

pub fn remove_workspace_config(
    workspace_root: PathBuf,
    key: String,
    pattern: String,
) -> Result<WorkspaceConfigMutation> {
    mutate_workspace_string_list(workspace_root, key, pattern, StringListMutation::Remove)
}

#[derive(Debug, Clone, Copy)]
enum StringListMutation {
    Add,
    Remove,
}

fn mutate_workspace_string_list(
    workspace_root: PathBuf,
    key: String,
    pattern: String,
    mutation: StringListMutation,
) -> Result<WorkspaceConfigMutation> {
    let workspace_root = resolve_workspace_root(Some(workspace_root))?;
    let config_path = workspace_config_path(&workspace_root)?;
    let spec = config_field(&key)?;
    let string_list = spec.string_list.ok_or_else(|| {
        CliError::new(
            "CONFIG_VALUE_INVALID",
            format!("{key} is not a string-list field; use `kast config set`"),
        )
    })?;
    validate_string_list_member(spec, &pattern)?;

    let config = KastConfig::load(&workspace_root)?;
    let mut values = effective_string_list(&config, string_list).to_vec();
    let changed = match mutation {
        StringListMutation::Add if !values.contains(&pattern) => {
            values.push(pattern);
            true
        }
        StringListMutation::Remove if values.contains(&pattern) => {
            values.retain(|value| value != &pattern);
            true
        }
        StringListMutation::Add | StringListMutation::Remove => false,
    };
    if !changed {
        return mutation_result(
            workspace_root,
            config_path,
            key,
            ConfigMutationStatus::Unchanged,
        );
    }

    let mut document = read_workspace_config(&config_path)?;
    set_document_value(
        document.as_table_mut(),
        &key.split('.').collect::<Vec<_>>(),
        string_list_item(&values),
    )?;
    let contents = document.to_string();
    validate_toml(&contents)?;
    write_workspace_config(&config_path, &contents)?;
    mutation_result(
        workspace_root,
        config_path,
        key,
        ConfigMutationStatus::Updated,
    )
}

fn effective_string_list(config: &KastConfig, field: StringListField) -> &[String] {
    match field {
        StringListField::CriticalPaths => &config.indexing.critical_paths,
        StringListField::IgnoredPaths => &config.indexing.ignored_paths,
    }
}

fn validate_string_list_member(spec: &ConfigFieldSpec, value: &str) -> Result<()> {
    if value.trim().is_empty() || value.chars().any(char::is_control) {
        return Err(CliError::new(
            "CONFIG_VALUE_INVALID",
            format!(
                "{} requires a non-empty string-list member without control characters",
                spec.field.key,
            ),
        ));
    }
    Ok(())
}

fn string_list_item(values: &[String]) -> Item {
    let mut array = toml_edit::Array::new();
    for value in values {
        array.push(value.as_str());
    }
    Item::Value(toml_edit::Value::Array(array))
}

pub fn unset_workspace_config(
    workspace_root: PathBuf,
    key: String,
) -> Result<WorkspaceConfigMutation> {
    let workspace_root = resolve_workspace_root(Some(workspace_root))?;
    let config_path = workspace_config_path(&workspace_root)?;
    config_field(&key)?;
    let mut document = read_workspace_config(&config_path)?;
    let removed = remove_document_value(
        document.as_table_mut(),
        &key.split('.').collect::<Vec<_>>(),
    );
    let status = if removed {
        let contents = document.to_string();
        validate_toml(&contents)?;
        write_workspace_config(&config_path, &contents)?;
        ConfigMutationStatus::Updated
    } else {
        ConfigMutationStatus::Unchanged
    };
    mutation_result(workspace_root, config_path, key, status)
}

fn mutation_result(
    workspace_root: PathBuf,
    config_path: PathBuf,
    key: String,
    status: ConfigMutationStatus,
) -> Result<WorkspaceConfigMutation> {
    let effective = serde_json::to_value(KastConfig::load(&workspace_root)?)?;
    let effective_value = key.split('.').try_fold(&effective, |value, segment| {
        value.as_object()?.get(segment)
    });
    Ok(WorkspaceConfigMutation {
        ok: true,
        status,
        workspace_root: workspace_root.display().to_string(),
        config_path: config_path.display().to_string(),
        key,
        effective_value: effective_value.cloned().unwrap_or(serde_json::Value::Null),
        schema_version: SCHEMA_VERSION,
    })
}

fn config_field(key: &str) -> Result<&'static ConfigFieldSpec> {
    MUTABLE_CONFIG_FIELDS
        .iter()
        .find(|spec| spec.field.key == key)
        .ok_or_else(|| {
            let mut error = CliError::new(
                "CONFIG_FIELD_UNSUPPORTED",
                format!("Unsupported workspace configuration field: {key}"),
            );
            error.details.insert(
                "supportedFields".to_string(),
                MUTABLE_CONFIG_FIELDS
                    .iter()
                    .map(|spec| spec.field.key)
                    .collect::<Vec<_>>()
                    .join(","),
            );
            error
        })
}

fn parse_config_value(spec: &ConfigFieldSpec, raw: &str) -> Result<Item> {
    let invalid = || {
        CliError::new(
            "CONFIG_VALUE_INVALID",
            format!(
                "{} requires a {} value",
                spec.field.key,
                match spec.field.value_type {
                    ConfigValueType::Boolean => "boolean",
                    ConfigValueType::Integer => "integer",
                    ConfigValueType::String => "string",
                    ConfigValueType::StringList => "string list",
                },
            ),
        )
    };
    let value = match spec.field.value_type {
        ConfigValueType::Boolean => raw
            .parse::<bool>()
            .map(toml_edit::Value::from)
            .map_err(|_| invalid())?,
        ConfigValueType::Integer => {
            let value = raw.parse::<i64>().map_err(|_| invalid())?;
            if spec.minimum.is_some_and(|minimum| value < minimum) {
                return Err(CliError::new(
                    "CONFIG_VALUE_INVALID",
                    format!("{} must be at least {}", spec.field.key, spec.minimum.unwrap()),
                ));
            }
            toml_edit::Value::from(value)
        }
        ConfigValueType::String => toml_edit::Value::from(raw),
        ConfigValueType::StringList => return Err(invalid()),
    };
    Ok(Item::Value(value))
}

fn workspace_config_path(workspace_root: &Path) -> Result<PathBuf> {
    Ok(workspace_data_directory(workspace_root)?.join("config.toml"))
}

fn read_workspace_config(path: &Path) -> Result<DocumentMut> {
    let contents = if path.is_file() {
        fs::read_to_string(path)?
    } else {
        String::new()
    };
    contents.parse::<DocumentMut>().map_err(|error| {
        CliError::new(
            "CONFIG_ERROR",
            format!("Invalid workspace config {}: {error}", path.display()),
        )
    })
}

fn write_workspace_config(path: &Path, contents: &str) -> Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| CliError::new("CONFIG_ERROR", "Workspace config has no parent directory"))?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".config.toml.{}.tmp", uuid::Uuid::new_v4()));
    fs::write(&temporary, contents)?;
    if let Err(error) = fs::rename(&temporary, path) {
        let _ = fs::remove_file(&temporary);
        return Err(error.into());
    }
    Ok(())
}

fn set_document_value(table: &mut dyn TableLike, path: &[&str], value: Item) -> Result<()> {
    let Some((head, tail)) = path.split_first() else {
        return Err(CliError::new("CONFIG_FIELD_UNSUPPORTED", "Empty config key"));
    };
    if tail.is_empty() {
        table.insert(head, value);
        return Ok(());
    }
    if !table.contains_key(head) {
        table.insert(head, Item::Table(Table::new()));
    }
    let child = table
        .get_mut(head)
        .and_then(Item::as_table_like_mut)
        .ok_or_else(|| {
            CliError::new(
                "CONFIG_ERROR",
                format!("{} is already a scalar value", path[0]),
            )
        })?;
    set_document_value(child, tail, value)
}

fn remove_document_value(table: &mut dyn TableLike, path: &[&str]) -> bool {
    let Some((head, tail)) = path.split_first() else {
        return false;
    };
    if tail.is_empty() {
        return table.remove(head).is_some();
    }
    let (removed, empty) = match table.get_mut(head).and_then(Item::as_table_like_mut) {
        Some(child) => {
            let removed = remove_document_value(child, tail);
            (removed, child.is_empty())
        }
        None => return false,
    };
    if empty {
        table.remove(head);
    }
    removed
}

fn document_contains_value(table: &dyn TableLike, path: &[&str]) -> bool {
    let Some((head, tail)) = path.split_first() else {
        return false;
    };
    let Some(item) = table.get(head) else {
        return false;
    };
    tail.is_empty()
        || item
            .as_table_like()
            .is_some_and(|child| document_contains_value(child, tail))
}
