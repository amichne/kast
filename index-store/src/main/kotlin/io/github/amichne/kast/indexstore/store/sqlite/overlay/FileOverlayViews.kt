package io.github.amichne.kast.indexstore.store

internal object FileOverlayViews {
    val definitions: List<OverlayViewDefinition> by lazy { listOf(
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_path_prefixes AS
               SELECT prefix_id, dir_path FROM main.path_prefixes
               UNION ALL
               SELECT -base.prefix_id, base.dir_path
               FROM repository_base.path_prefixes base
               WHERE NOT EXISTS (
                   SELECT 1 FROM main.path_prefixes overlay WHERE overlay.dir_path = base.dir_path
               )""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_fq_names AS
               SELECT fq_id, fq_name FROM main.fq_names
               UNION ALL
               SELECT -base.fq_id, base.fq_name
               FROM repository_base.fq_names base
               WHERE NOT EXISTS (
                   SELECT 1 FROM main.fq_names overlay WHERE overlay.fq_name = base.fq_name
               )""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS repository_effective_file_authority AS
               SELECT 'main' AS origin, manifest.prefix_id AS source_prefix_id,
                      manifest.prefix_id AS effective_prefix_id, manifest.filename
               FROM main.file_manifest manifest
               UNION ALL
               SELECT 'base', manifest.prefix_id,
                      COALESCE(overlay_prefix.prefix_id, -manifest.prefix_id), manifest.filename
               FROM repository_base.file_manifest manifest
               JOIN repository_base.path_prefixes base_prefix
                 ON base_prefix.prefix_id = manifest.prefix_id
               LEFT JOIN main.path_prefixes overlay_prefix
                 ON overlay_prefix.dir_path = base_prefix.dir_path
               WHERE NOT EXISTS (
                       SELECT 1 FROM main.repository_overlay_tombstones tombstone
                       WHERE tombstone.path = CASE
                           WHEN base_prefix.dir_path = '' THEN manifest.filename
                           WHEN base_prefix.dir_path = '__kast_rel__' THEN manifest.filename
                           WHEN base_prefix.dir_path LIKE '__kast_rel__/%'
                               THEN substr(base_prefix.dir_path, length('__kast_rel__/') + 1) || '/' || manifest.filename
                           WHEN base_prefix.dir_path LIKE '__kast_abs__/%' THEN NULL
                           ELSE base_prefix.dir_path || '/' || manifest.filename
                       END
                   )
                 AND NOT EXISTS (
                       SELECT 1
                       FROM main.file_manifest overlay
                       JOIN main.path_prefixes candidate_prefix
                         ON candidate_prefix.prefix_id = overlay.prefix_id
                       WHERE candidate_prefix.dir_path = base_prefix.dir_path
                         AND overlay.filename = manifest.filename
                   )""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_file_manifest AS
               SELECT authority.effective_prefix_id AS prefix_id, manifest.filename,
                      manifest.last_modified_millis, manifest.content_hash,
                      manifest.desired_source_version, manifest.desired_relationships_version,
                      manifest.desired_semantic_graph_version, manifest.module_name, manifest.source_set
               FROM repository_effective_file_authority authority
               JOIN main.file_manifest manifest
                 ON authority.origin = 'main'
                AND manifest.prefix_id = authority.source_prefix_id
                AND manifest.filename = authority.filename
               UNION ALL
               SELECT authority.effective_prefix_id, manifest.filename,
                      manifest.last_modified_millis, manifest.content_hash,
                      manifest.desired_source_version, manifest.desired_relationships_version,
                      manifest.desired_semantic_graph_version, manifest.module_name, manifest.source_set
               FROM repository_effective_file_authority authority
               JOIN repository_base.file_manifest manifest
                 ON authority.origin = 'base'
                AND manifest.prefix_id = authority.source_prefix_id
                AND manifest.filename = authority.filename""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_identifier_paths(identifier, prefix_id, filename) AS
               SELECT rows.identifier, authority.effective_prefix_id, rows.filename
               FROM main.identifier_paths rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'main'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename
               UNION ALL
               SELECT rows.identifier, authority.effective_prefix_id, rows.filename
               FROM repository_base.identifier_paths rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'base'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_file_metadata(
                   prefix_id, filename, package_fq_id, package_state,
                   package_unproven_reason, module_path, source_set
               ) AS
               SELECT authority.effective_prefix_id, rows.filename, rows.package_fq_id,
                      rows.package_state, rows.package_unproven_reason, rows.module_path, rows.source_set
               FROM main.file_metadata rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'main'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename
               UNION ALL
               SELECT authority.effective_prefix_id, rows.filename,
                      COALESCE(overlay_fq.fq_id, -rows.package_fq_id), rows.package_state,
                      rows.package_unproven_reason, rows.module_path, rows.source_set
               FROM repository_base.file_metadata rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'base'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename
               LEFT JOIN repository_base.fq_names base_fq ON base_fq.fq_id = rows.package_fq_id
               LEFT JOIN main.fq_names overlay_fq ON overlay_fq.fq_name = base_fq.fq_name""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_file_gradle_projects(
                   prefix_id, filename, build_root, project_path
               ) AS
               SELECT authority.effective_prefix_id, rows.filename, rows.build_root, rows.project_path
               FROM main.file_gradle_projects rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'main'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename
               UNION ALL
               SELECT authority.effective_prefix_id, rows.filename, rows.build_root, rows.project_path
               FROM repository_base.file_gradle_projects rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'base'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_file_gradle_source_sets(
                   prefix_id, filename, build_root, project_path, source_set_name
               ) AS
               SELECT authority.effective_prefix_id, rows.filename, rows.build_root,
                      rows.project_path, rows.source_set_name
               FROM main.file_gradle_source_sets rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'main'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename
               UNION ALL
               SELECT authority.effective_prefix_id, rows.filename, rows.build_root,
                      rows.project_path, rows.source_set_name
               FROM repository_base.file_gradle_source_sets rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'base'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename""",
        ),
        fileImports,
        fileWildcardImports,
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_file_stage_outcomes(
                   prefix_id, filename, stage, content_hash, stage_version,
                   stage_input_fingerprint, outcome_status, limitations_json,
                   failure_id, failure_code, failure_message, failure_attempt_count
               ) AS
               SELECT authority.effective_prefix_id, rows.filename, rows.stage, rows.content_hash,
                      rows.stage_version, rows.stage_input_fingerprint, rows.outcome_status,
                      rows.limitations_json, rows.failure_id, rows.failure_code,
                      rows.failure_message, rows.failure_attempt_count
               FROM main.file_stage_outcomes rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'main'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename
               UNION ALL
               SELECT authority.effective_prefix_id, rows.filename, rows.stage, rows.content_hash,
                      rows.stage_version, rows.stage_input_fingerprint, rows.outcome_status,
                      rows.limitations_json, rows.failure_id, rows.failure_code,
                      rows.failure_message, rows.failure_attempt_count
               FROM repository_base.file_stage_outcomes rows
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'base'
                AND authority.source_prefix_id = rows.prefix_id
                AND authority.filename = rows.filename""",
        ),
    ) }

    private val fileImports = OverlayViewDefinition.schemaOwned(
        """CREATE TEMP VIEW IF NOT EXISTS effective_file_imports(prefix_id, filename, fq_id) AS
           SELECT authority.effective_prefix_id, rows.filename, rows.fq_id
           FROM main.file_imports rows
           JOIN repository_effective_file_authority authority
             ON authority.origin = 'main'
            AND authority.source_prefix_id = rows.prefix_id
            AND authority.filename = rows.filename
           UNION ALL
           SELECT authority.effective_prefix_id, rows.filename,
                  COALESCE(overlay_fq.fq_id, -rows.fq_id)
           FROM repository_base.file_imports rows
           JOIN repository_effective_file_authority authority
             ON authority.origin = 'base'
            AND authority.source_prefix_id = rows.prefix_id
            AND authority.filename = rows.filename
           JOIN repository_base.fq_names base_fq ON base_fq.fq_id = rows.fq_id
           LEFT JOIN main.fq_names overlay_fq ON overlay_fq.fq_name = base_fq.fq_name""",
    )

    private val fileWildcardImports = OverlayViewDefinition.schemaOwned(
        """CREATE TEMP VIEW IF NOT EXISTS effective_file_wildcard_imports(prefix_id, filename, fq_id) AS
           SELECT authority.effective_prefix_id, rows.filename, rows.fq_id
           FROM main.file_wildcard_imports rows
           JOIN repository_effective_file_authority authority
             ON authority.origin = 'main'
            AND authority.source_prefix_id = rows.prefix_id
            AND authority.filename = rows.filename
           UNION ALL
           SELECT authority.effective_prefix_id, rows.filename,
                  COALESCE(overlay_fq.fq_id, -rows.fq_id)
           FROM repository_base.file_wildcard_imports rows
           JOIN repository_effective_file_authority authority
             ON authority.origin = 'base'
            AND authority.source_prefix_id = rows.prefix_id
            AND authority.filename = rows.filename
           JOIN repository_base.fq_names base_fq ON base_fq.fq_id = rows.fq_id
           LEFT JOIN main.fq_names overlay_fq ON overlay_fq.fq_name = base_fq.fq_name""",
    )
}
