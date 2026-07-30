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
        if terms.is_empty() {
            return Ok(matches_by_file);
        }

        self.identifier_matches(terms, eligible_files, &mut matches_by_file)?;
        if table_exists(&self.conn, "file_imports")? {
            self.import_table_matches(
                terms,
                "file_imports",
                eligible_files,
                &mut matches_by_file,
            )?;
        }
        if table_exists(&self.conn, "file_wildcard_imports")? {
            self.import_table_matches(
                terms,
                "file_wildcard_imports",
                eligible_files,
                &mut matches_by_file,
            )?;
        }
        Ok(matches_by_file)
    }

    fn identifier_matches(
        &self,
        terms: &[String],
        eligible_files: &EligibleLexicalFiles,
        matches_by_file: &mut LexicalMatchesByFile,
    ) -> Result<()> {
        let predicate = matching_terms_sql("identifier", terms.len());
        let sql = format!(
            r#"
            SELECT prefix_id, filename, identifier
            FROM identifier_paths
            WHERE {predicate}
            ORDER BY prefix_id ASC, filename ASC, identifier ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let rows = stmt
            .query_map(params_from_iter(terms.iter()), |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(sql_error)?;
        for row in rows {
            let (prefix_id, filename, identifier) = row.map_err(sql_error)?;
            if !eligible_files
                .get(&prefix_id)
                .is_some_and(|files| files.contains(&filename))
            {
                continue;
            }
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
        eligible_files: &EligibleLexicalFiles,
        matches_by_file: &mut LexicalMatchesByFile,
    ) -> Result<()> {
        let predicate = matching_terms_sql("names.fq_name", terms.len());
        let sql = format!(
            r#"
            SELECT imports.prefix_id, imports.filename, names.fq_name
            FROM {table_name} imports
            JOIN fq_names names ON names.fq_id = imports.fq_id
            WHERE {predicate}
            ORDER BY imports.prefix_id ASC, imports.filename ASC, names.fq_name ASC
            "#
        );
        let mut stmt = self.conn.prepare(&sql).map_err(sql_error)?;
        let rows = stmt
            .query_map(params_from_iter(terms.iter()), |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(sql_error)?;
        for row in rows {
            let (prefix_id, filename, import) = row.map_err(sql_error)?;
            if !eligible_files
                .get(&prefix_id)
                .is_some_and(|files| files.contains(&filename))
            {
                continue;
            }
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
