type EligibleLexicalFiles = BTreeMap<i64, BTreeSet<String>>;
type LexicalMatchesByFile =
    HashMap<i64, HashMap<String, BTreeMap<(&'static str, String), LexicalMatch>>>;

impl<'a> SymbolQueryDatabase<'a> {
    fn lexical_matches(
        &self,
        terms: &[String],
        declaration: &DeclarationRow,
        matches_by_file: &LexicalMatchesByFile,
    ) -> Vec<LexicalMatch> {
        let mut matches = Vec::new();
        matches.extend(lexical_field_matches(
            terms,
            "fq_names.fq_name",
            &declaration.fq_name,
        ));
        matches.extend(lexical_field_matches(terms, "file_path", &declaration.path));
        if let Some(file_matches) = matches_by_file
            .get(&declaration.prefix_id)
            .and_then(|files| files.get(&declaration.filename))
        {
            matches.extend(file_matches.values().cloned());
        }
        matches
    }

    fn lexical_matches_by_file(
        &self,
        terms: &[String],
        eligible_files: &EligibleLexicalFiles,
    ) -> Result<LexicalMatchesByFile> {
        let mut matches_by_file = HashMap::new();
        if terms.is_empty() || eligible_files.is_empty() {
            return Ok(matches_by_file);
        }
        let eligible_files_json = Value::Array(
            eligible_files
                .iter()
                .flat_map(|(prefix_id, filenames)| {
                    filenames
                        .iter()
                        .map(move |filename| json!([prefix_id, filename]))
                })
                .collect(),
        )
        .to_string();

        self.identifier_matches(terms, &eligible_files_json, &mut matches_by_file)?;
        if table_exists(&self.conn, "file_imports")? {
            self.import_table_matches(
                terms,
                "file_imports",
                &eligible_files_json,
                &mut matches_by_file,
            )?;
        }
        if table_exists(&self.conn, "file_wildcard_imports")? {
            self.import_table_matches(
                terms,
                "file_wildcard_imports",
                &eligible_files_json,
                &mut matches_by_file,
            )?;
        }
        Ok(matches_by_file)
    }

    fn identifier_matches(
        &self,
        terms: &[String],
        eligible_files_json: &str,
        matches_by_file: &mut LexicalMatchesByFile,
    ) -> Result<()> {
        let predicate = matching_terms_sql("paths.identifier", terms.len());
        let sql = format!(
            r#"
            SELECT paths.prefix_id, paths.filename, paths.identifier
            FROM json_each(?) eligible
            CROSS JOIN identifier_paths paths
            WHERE paths.prefix_id = json_extract(eligible.value, '$[0]')
              AND paths.filename = json_extract(eligible.value, '$[1]')
              AND ({predicate})
            ORDER BY paths.prefix_id ASC, paths.filename ASC, paths.identifier ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let params = std::iter::once(eligible_files_json)
            .chain(terms.iter().map(String::as_str));
        let rows = stmt
            .query_map(params_from_iter(params), |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(sql_error)?;
        for row in rows {
            let (prefix_id, filename, identifier) = row.map_err(sql_error)?;
            let matches =
                lexical_field_matches(terms, "identifier_paths.identifier", &identifier);
            extend_file_matches(matches_by_file, prefix_id, filename, matches);
        }
        Ok(())
    }

    fn import_table_matches(
        &self,
        terms: &[String],
        table_name: &str,
        eligible_files_json: &str,
        matches_by_file: &mut LexicalMatchesByFile,
    ) -> Result<()> {
        let predicate = matching_terms_sql("names.fq_name", terms.len());
        let sql = format!(
            r#"
            SELECT imports.prefix_id, imports.filename, names.fq_name
            FROM json_each(?) eligible
            CROSS JOIN {table_name} imports
            JOIN fq_names names ON names.fq_id = imports.fq_id
            WHERE imports.prefix_id = json_extract(eligible.value, '$[0]')
              AND imports.filename = json_extract(eligible.value, '$[1]')
              AND ({predicate})
            ORDER BY imports.prefix_id ASC, imports.filename ASC, names.fq_name ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let params = std::iter::once(eligible_files_json)
            .chain(terms.iter().map(String::as_str));
        let rows = stmt
            .query_map(params_from_iter(params), |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(sql_error)?;
        for row in rows {
            let (prefix_id, filename, import) = row.map_err(sql_error)?;
            let matches = lexical_field_matches(terms, "import_fq_name", &import);
            extend_file_matches(matches_by_file, prefix_id, filename, matches);
        }
        Ok(())
    }
}

fn matching_terms_sql(field: &str, term_count: usize) -> String {
    (0..term_count)
        .map(|_| format!("instr(lower({field}), ?) > 0"))
        .collect::<Vec<_>>()
        .join(" OR ")
}

fn extend_file_matches(
    matches_by_file: &mut LexicalMatchesByFile,
    prefix_id: i64,
    filename: String,
    matches: Vec<LexicalMatch>,
) {
    if matches.is_empty() {
        return;
    }
    let file_matches = matches_by_file
        .entry(prefix_id)
        .or_default()
        .entry(filename)
        .or_default();
    for candidate in matches {
        let key = (candidate.field, candidate.term.clone());
        if let Some(current) = file_matches.get_mut(&key) {
            if (candidate.match_type != "TOKEN", candidate.evidence.as_str())
                < (current.match_type != "TOKEN", current.evidence.as_str())
            {
                *current = candidate;
            }
        } else {
            file_matches.insert(key, candidate);
        }
    }
}
