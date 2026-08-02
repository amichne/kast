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
                    format!(
                        "{} must be at least {}",
                        spec.field.key,
                        spec.minimum.unwrap()
                    ),
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
        return Err(CliError::new(
            "CONFIG_FIELD_UNSUPPORTED",
            "Empty config key",
        ));
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
