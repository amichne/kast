impl<'a> MetricsDatabase<'a> {
    fn impact_rows<I>(&self, rows: rusqlite::Result<I>) -> DirectResult<Vec<ChangeImpactNode>>
    where
        I: Iterator<Item = rusqlite::Result<ChangeImpactNode>>,
    {
        let mut values = Vec::new();
        for row in rows.map_err(sql_error)? {
            values.push(row.map_err(sql_error)?);
        }
        Ok(values)
    }

    fn impact_row(
        &self,
        row: &Row<'_>,
        confidence: &Confidence,
    ) -> rusqlite::Result<ChangeImpactNode> {
        Ok(ChangeImpactNode {
            source_path: self.compose_path(row.get::<_, String>(0)?, row.get::<_, String>(1)?),
            depth: row.get::<_, i64>(2)? as usize,
            via_target_fq_name: row.get(3)?,
            edge_kind: row.get(4)?,
            occurrence_count: row.get(5)?,
            confidence: confidence.clone(),
        })
    }

    fn popular_symbols(&self, limit: usize) -> DirectResult<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name
                FROM fq_names names
                JOIN symbol_references refs ON refs.target_fq_id = names.fq_id
                GROUP BY names.fq_id
                ORDER BY COUNT(*) DESC, names.fq_name ASC
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![limit as i64], |row| row.get(0)))
    }

    fn exact_symbol_match(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut stmt = self
            .conn
            .prepare(
                r#"
                SELECT names.fq_name
                FROM fq_names names
                WHERE names.fq_name = ?
                LIMIT ?
                "#,
            )
            .map_err(sql_error)?;
        string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
    }

    fn short_symbol_matches(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        {
            let needle = source_index_db::escape_like(&query.to_lowercase());
            let fq_prefix = format!("{needle}%");
            let segment_prefix = format!("%.{}%", needle);
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    SELECT names.fq_name
                    FROM fq_names names
                    WHERE LOWER(names.fq_name) LIKE ? ESCAPE '\'
                       OR LOWER(names.fq_name) LIKE ? ESCAPE '\'
                    ORDER BY
                        CASE
                            WHEN LOWER(names.fq_name) LIKE ? ESCAPE '\' THEN 0
                            ELSE 1
                        END,
                        LENGTH(names.fq_name),
                        names.fq_name
                    LIMIT ?
                    "#,
                )
                .map_err(sql_error)?;
            string_column(stmt.query_map(
                params![fq_prefix, segment_prefix, fq_prefix, limit as i64],
                |row| row.get(0),
            ))
        }
    }

    fn fts_symbol_matches(&self, query: &str, limit: usize) -> DirectResult<Vec<String>> {
        {
            let query = source_index_db::trigram_fts_query(query);
            let mut stmt = self
                .conn
                .prepare(
                    r#"
                    SELECT fq_name
                    FROM fq_names_fts
                    WHERE fq_names_fts MATCH ?
                    ORDER BY rank, LENGTH(fq_name), fq_name
                    LIMIT ?
                    "#,
                )
                .map_err(sql_error)?;
            string_column(stmt.query_map(params![query, limit as i64], |row| row.get(0)))
        }
    }

    fn schema_is_current(&self) -> rusqlite::Result<bool> {
        let version = self
            .conn
            .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
                row.get::<_, i64>(0)
            })
            .optional()?;
        Ok(version == Some(SOURCE_INDEX_SCHEMA_VERSION)
            && self.required_tables_exist()?
            && source_index_db::persistent_symbol_fts_exists(&self.conn)?)
    }

    fn required_tables_exist(&self) -> rusqlite::Result<bool> {
        let required = [
            "path_prefixes",
            "fq_names",
            "symbol_references",
            "identifier_paths",
            "file_metadata",
            "file_manifest",
            "declarations",
        ];
        for table in required {
            let exists = self
                .conn
                .query_row(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                    params![table],
                    |_| Ok(true),
                )
                .optional()?
                .unwrap_or(false);
            if !exists {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn nullable_path(
        &self,
        row: &Row<'_>,
        dir_column: usize,
        filename_column: usize,
    ) -> rusqlite::Result<Option<String>> {
        let filename: Option<String> = row.get(filename_column)?;
        Ok(filename.map(|filename| {
            let dir = row
                .get::<_, Option<String>>(dir_column)
                .ok()
                .flatten()
                .unwrap_or_default();
            self.compose_path(dir, filename)
        }))
    }

    fn compose_path(&self, relative_dir: String, filename: String) -> String {
        let path = if let Some(absolute) = relative_dir.strip_prefix("__kast_abs__/") {
            PathBuf::from(absolute).join(filename)
        } else {
            let relative = relative_dir
                .strip_prefix("__kast_rel__/")
                .unwrap_or(&relative_dir);
            relative
                .split('/')
                .filter(|segment| !segment.is_empty())
                .fold(
                    self.request.workspace_root().to_path_buf(),
                    |path, segment| path.join(segment),
                )
                .join(filename)
        };
        config::normalize(path).display().to_string()
    }
}
