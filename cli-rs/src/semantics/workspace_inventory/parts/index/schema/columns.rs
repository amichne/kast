fn table_info(
    transaction: &Transaction<'_>,
    table: &str,
) -> Result<BTreeMap<String, TableColumn>, ReadDatabaseError> {
    let mut statement = transaction
        .prepare(&format!("PRAGMA table_info({table})"))
        .map_err(incompatible_sql)?;
    statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(1)?,
                TableColumn {
                    not_null: row.get::<_, i64>(3)? != 0,
                    primary_key_position: row.get(5)?,
                },
            ))
        })
        .map_err(incompatible_sql)?
        .collect::<rusqlite::Result<BTreeMap<_, _>>>()
        .map_err(incompatible_sql)
}

fn verify_primary_key(
    transaction: &Transaction<'_>,
    table: &str,
    expected: &[&str],
) -> Result<(), ReadDatabaseError> {
    let columns = table_info(transaction, table)?;
    let mut actual: Vec<_> = columns
        .iter()
        .filter(|(_, column)| column.primary_key_position > 0)
        .map(|(name, column)| (column.primary_key_position, name.as_str()))
        .collect();
    actual.sort_unstable();
    let actual: Vec<_> = actual.into_iter().map(|(_, name)| name).collect();
    if actual != expected {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index table `{table}` has incompatible primary key"
        )));
    }
    Ok(())
}

fn verify_not_null(
    transaction: &Transaction<'_>,
    table: &str,
    required: &[&str],
) -> Result<(), ReadDatabaseError> {
    let columns = table_info(transaction, table)?;
    if let Some(column) = required
        .iter()
        .find(|column| !columns.get(**column).is_some_and(|shape| shape.not_null))
    {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index column `{table}.{column}` must be NOT NULL"
        )));
    }
    Ok(())
}

fn verify_unique_key(
    transaction: &Transaction<'_>,
    table: &str,
    expected: &[&str],
) -> Result<(), ReadDatabaseError> {
    let mut statement = transaction
        .prepare(&format!("PRAGMA index_list({table})"))
        .map_err(incompatible_sql)?;
    let indexes = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)? != 0,
                row.get::<_, i64>(4)? != 0,
            ))
        })
        .map_err(incompatible_sql)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(incompatible_sql)?;
    for (index, unique, partial) in indexes {
        if !unique || partial {
            continue;
        }
        let quoted_index = index.replace('\'', "''");
        let mut index_statement = transaction
            .prepare(&format!("PRAGMA index_info('{quoted_index}')"))
            .map_err(incompatible_sql)?;
        let columns = index_statement
            .query_map([], |row| row.get::<_, String>(2))
            .map_err(incompatible_sql)?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(incompatible_sql)?;
        if columns
            .iter()
            .map(String::as_str)
            .eq(expected.iter().copied())
        {
            return Ok(());
        }
    }
    Err(ReadDatabaseError::Incompatible(format!(
        "source-index table `{table}` lacks the required unique key ({})",
        expected.join(", ")
    )))
}

fn verify_foreign_key(
    transaction: &Transaction<'_>,
    table: &str,
    target_table: &str,
    expected_columns: &[(&str, &str)],
    expected_delete_action: &str,
) -> Result<(), ReadDatabaseError> {
    let mut statement = transaction
        .prepare(&format!("PRAGMA foreign_key_list({table})"))
        .map_err(incompatible_sql)?;
    let rows = statement
        .query_map([], |row| {
            Ok(ForeignKeyColumn {
                id: row.get(0)?,
                sequence: row.get(1)?,
                target_table: row.get(2)?,
                from_column: row.get(3)?,
                to_column: row.get(4)?,
                delete_action: row.get(6)?,
            })
        })
        .map_err(incompatible_sql)?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(incompatible_sql)?;
    let mut grouped = BTreeMap::<i64, Vec<ForeignKeyColumn>>::new();
    for row in rows {
        grouped.entry(row.id).or_default().push(row);
    }
    let matches = grouped.values_mut().any(|columns| {
        columns.sort_by_key(|column| column.sequence);
        columns.first().is_some_and(|column| {
            column.target_table == target_table && column.delete_action == expected_delete_action
        }) && columns.len() == expected_columns.len()
            && columns
                .iter()
                .zip(expected_columns)
                .all(|(actual, expected)| {
                    actual.from_column == expected.0 && actual.to_column == expected.1
                })
    });
    if !matches {
        return Err(ReadDatabaseError::Incompatible(format!(
            "source-index table `{table}` has an incompatible foreign key to `{target_table}`"
        )));
    }
    Ok(())
}
