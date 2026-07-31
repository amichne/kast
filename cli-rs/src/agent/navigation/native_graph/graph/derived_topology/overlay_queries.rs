const DERIVED_TOPOLOGY_ORPHAN_SQL: &str = r#"
    SELECT COUNT(*)
    FROM symbol_references edge
    LEFT JOIN file_manifest manifest
      ON manifest.prefix_id = edge.src_prefix_id
     AND manifest.filename = edge.src_filename
    WHERE manifest.filename IS NULL
"#;

const DERIVED_TOPOLOGY_COVERAGE_SQL: &str = r#"
    SELECT manifest.content_hash, manifest.desired_relationships_version,
           outcomes.content_hash, outcomes.stage_version,
           outcomes.outcome_status, outcomes.limitations_json,
           outcomes.failure_code,
           EXISTS (
               SELECT 1 FROM pending_updates pending
               WHERE pending.prefix_id = manifest.prefix_id
                 AND pending.filename = manifest.filename
                 AND pending.applied = 0
           )
    FROM file_manifest manifest
    LEFT JOIN file_stage_outcomes outcomes
      ON outcomes.prefix_id = manifest.prefix_id
     AND outcomes.filename = manifest.filename
     AND outcomes.stage = 'RELATIONSHIPS'
    WHERE manifest.filename LIKE '%.kt'
    ORDER BY manifest.prefix_id, manifest.filename
"#;

const DERIVED_TOPOLOGY_NODE_SQL: &str = r#"
    WITH node_ids(fq_id) AS (
        SELECT fq_id FROM declarations
        UNION SELECT source_fq_id FROM symbol_references WHERE source_fq_id IS NOT NULL
        UNION SELECT target_fq_id FROM symbol_references
    )
    SELECT names.fq_name, declarations.kind, prefixes.dir_path,
           declarations.filename, declarations.module_path, declarations.source_set
    FROM node_ids
    JOIN fq_names names ON names.fq_id = node_ids.fq_id
    LEFT JOIN declarations ON declarations.fq_id = node_ids.fq_id
    LEFT JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
    ORDER BY names.fq_name, declarations.prefix_id, declarations.filename
"#;

const DERIVED_TOPOLOGY_EDGE_SQL: &str = r#"
    SELECT source.fq_name, target.fq_name, edge.edge_kind, COUNT(*),
           0 AS invalidated_target
    FROM symbol_references edge
    JOIN file_manifest manifest
      ON manifest.prefix_id = edge.src_prefix_id
     AND manifest.filename = edge.src_filename
    LEFT JOIN fq_names source ON source.fq_id = edge.source_fq_id
    JOIN fq_names target ON target.fq_id = edge.target_fq_id
    GROUP BY source.fq_name, target.fq_name, edge.edge_kind
    ORDER BY source.fq_name, target.fq_name, edge.edge_kind
"#;

const DERIVED_TOPOLOGY_REPOSITORY_ORPHAN_SQL: &str = r#"
    SELECT
        (SELECT COUNT(*)
         FROM symbol_references edge
         LEFT JOIN file_manifest manifest
           ON manifest.prefix_id = edge.src_prefix_id
          AND manifest.filename = edge.src_filename
         WHERE manifest.filename IS NULL)
      + (SELECT COUNT(*)
         FROM repository_base.symbol_references edge
         LEFT JOIN repository_base.file_manifest manifest
           ON manifest.prefix_id = edge.src_prefix_id
          AND manifest.filename = edge.src_filename
         WHERE manifest.filename IS NULL)
"#;

const DERIVED_TOPOLOGY_REPOSITORY_COVERAGE_SQL: &str = r#"
    WITH relationship_coverage AS (
        SELECT 0 AS origin, manifest.prefix_id, manifest.filename,
               manifest.content_hash, manifest.desired_relationships_version,
               outcomes.content_hash AS outcome_hash,
               outcomes.stage_version, outcomes.outcome_status,
               outcomes.limitations_json, outcomes.failure_code,
               EXISTS (
                   SELECT 1 FROM pending_updates pending
                   WHERE pending.prefix_id = manifest.prefix_id
                     AND pending.filename = manifest.filename
                     AND pending.applied = 0
               ) AS has_pending_update
        FROM file_manifest manifest
        LEFT JOIN file_stage_outcomes outcomes
          ON outcomes.prefix_id = manifest.prefix_id
         AND outcomes.filename = manifest.filename
         AND outcomes.stage = 'RELATIONSHIPS'
        WHERE manifest.filename LIKE '%.kt'
        UNION ALL
        SELECT 1 AS origin, manifest.prefix_id, manifest.filename,
               manifest.content_hash, manifest.desired_relationships_version,
               outcomes.content_hash AS outcome_hash,
               outcomes.stage_version, outcomes.outcome_status,
               outcomes.limitations_json, outcomes.failure_code,
               EXISTS (
                   SELECT 1 FROM repository_base.pending_updates pending
                   WHERE pending.prefix_id = manifest.prefix_id
                     AND pending.filename = manifest.filename
                     AND pending.applied = 0
               ) AS has_pending_update
        FROM repository_base.file_manifest manifest
        LEFT JOIN repository_base.file_stage_outcomes outcomes
          ON outcomes.prefix_id = manifest.prefix_id
         AND outcomes.filename = manifest.filename
         AND outcomes.stage = 'RELATIONSHIPS'
        LEFT JOIN repository_base.path_prefixes prefixes
          ON prefixes.prefix_id = manifest.prefix_id
        WHERE manifest.filename LIKE '%.kt'
          AND NOT EXISTS (
              SELECT 1 FROM repository_overlay_tombstones tombstone
              WHERE tombstone.path = CASE
                  WHEN prefixes.dir_path = '' THEN manifest.filename
                  WHEN prefixes.dir_path = '__kast_rel__' THEN manifest.filename
                  WHEN prefixes.dir_path LIKE '__kast_rel__/%'
                      THEN substr(
                          prefixes.dir_path,
                          length('__kast_rel__/') + 1
                      ) || '/' || manifest.filename
                  WHEN prefixes.dir_path LIKE '__kast_abs__/%' THEN NULL
                  ELSE prefixes.dir_path || '/' || manifest.filename
              END
          )
          AND NOT EXISTS (
              SELECT 1
              FROM file_manifest overlay
              JOIN path_prefixes overlay_prefixes
                ON overlay_prefixes.prefix_id = overlay.prefix_id
              WHERE overlay.filename = manifest.filename
                AND overlay_prefixes.dir_path = prefixes.dir_path
          )
    )
    SELECT content_hash, desired_relationships_version,
           outcome_hash, stage_version, outcome_status,
           limitations_json, failure_code, has_pending_update
    FROM relationship_coverage
    ORDER BY origin, prefix_id, filename
"#;

const DERIVED_TOPOLOGY_REPOSITORY_NODE_SQL: &str = r#"
    WITH
    effective_declarations(
        origin, fq_name, kind, dir_path, filename, module_path, source_set, prefix_id
    ) AS (
        SELECT 0, names.fq_name, declarations.kind, prefixes.dir_path,
               declarations.filename, declarations.module_path,
               declarations.source_set, declarations.prefix_id
        FROM declarations
        JOIN file_manifest manifest
          ON manifest.prefix_id = declarations.prefix_id
         AND manifest.filename = declarations.filename
        JOIN fq_names names ON names.fq_id = declarations.fq_id
        LEFT JOIN path_prefixes prefixes
          ON prefixes.prefix_id = declarations.prefix_id
        UNION ALL
        SELECT 1, names.fq_name, declarations.kind, prefixes.dir_path,
               declarations.filename, declarations.module_path,
               declarations.source_set, declarations.prefix_id
        FROM repository_base.declarations declarations
        JOIN repository_base.file_manifest manifest
          ON manifest.prefix_id = declarations.prefix_id
         AND manifest.filename = declarations.filename
        JOIN repository_base.fq_names names
          ON names.fq_id = declarations.fq_id
        LEFT JOIN repository_base.path_prefixes prefixes
          ON prefixes.prefix_id = declarations.prefix_id
        WHERE NOT EXISTS (
                SELECT 1 FROM repository_overlay_tombstones tombstone
                WHERE tombstone.path = CASE
                    WHEN prefixes.dir_path = '' THEN declarations.filename
                    WHEN prefixes.dir_path = '__kast_rel__'
                        THEN declarations.filename
                    WHEN prefixes.dir_path LIKE '__kast_rel__/%'
                        THEN substr(
                            prefixes.dir_path,
                            length('__kast_rel__/') + 1
                        ) || '/' || declarations.filename
                    WHEN prefixes.dir_path LIKE '__kast_abs__/%' THEN NULL
                    ELSE prefixes.dir_path || '/' || declarations.filename
                END
            )
          AND NOT EXISTS (
                SELECT 1
                FROM file_manifest overlay
                JOIN path_prefixes overlay_prefixes
                  ON overlay_prefixes.prefix_id = overlay.prefix_id
                WHERE overlay.filename = declarations.filename
                  AND overlay_prefixes.dir_path = prefixes.dir_path
            )
    ),
    effective_main_references AS (
        SELECT edge.*
        FROM symbol_references edge
        JOIN file_manifest manifest
          ON manifest.prefix_id = edge.src_prefix_id
         AND manifest.filename = edge.src_filename
    ),
    effective_base_references AS (
        SELECT edge.*
        FROM repository_base.symbol_references edge
        JOIN repository_base.file_manifest manifest
          ON manifest.prefix_id = edge.src_prefix_id
         AND manifest.filename = edge.src_filename
        LEFT JOIN repository_base.path_prefixes prefixes
          ON prefixes.prefix_id = manifest.prefix_id
        WHERE NOT EXISTS (
                SELECT 1 FROM repository_overlay_tombstones tombstone
                WHERE tombstone.path = CASE
                    WHEN prefixes.dir_path = '' THEN manifest.filename
                    WHEN prefixes.dir_path = '__kast_rel__'
                        THEN manifest.filename
                    WHEN prefixes.dir_path LIKE '__kast_rel__/%'
                        THEN substr(
                            prefixes.dir_path,
                            length('__kast_rel__/') + 1
                        ) || '/' || manifest.filename
                    WHEN prefixes.dir_path LIKE '__kast_abs__/%' THEN NULL
                    ELSE prefixes.dir_path || '/' || manifest.filename
                END
            )
          AND NOT EXISTS (
                SELECT 1
                FROM file_manifest overlay
                JOIN path_prefixes overlay_prefixes
                  ON overlay_prefixes.prefix_id = overlay.prefix_id
                WHERE overlay.filename = manifest.filename
                  AND overlay_prefixes.dir_path = prefixes.dir_path
            )
          AND (
                edge.tgt_prefix_id IS NULL
                OR EXISTS (
                    SELECT 1
                    FROM repository_base.file_manifest target
                    LEFT JOIN repository_base.path_prefixes target_prefixes
                      ON target_prefixes.prefix_id = target.prefix_id
                    WHERE target.prefix_id = edge.tgt_prefix_id
                      AND target.filename = edge.tgt_filename
                      AND NOT EXISTS (
                          SELECT 1
                          FROM repository_overlay_tombstones tombstone
                          WHERE tombstone.path = CASE
                              WHEN target_prefixes.dir_path = '' THEN target.filename
                              WHEN target_prefixes.dir_path = '__kast_rel__'
                                  THEN target.filename
                              WHEN target_prefixes.dir_path LIKE '__kast_rel__/%'
                                  THEN substr(
                                      target_prefixes.dir_path,
                                      length('__kast_rel__/') + 1
                                  ) || '/' || target.filename
                              WHEN target_prefixes.dir_path LIKE '__kast_abs__/%' THEN NULL
                              ELSE target_prefixes.dir_path || '/' || target.filename
                          END
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM file_manifest overlay
                          JOIN path_prefixes overlay_prefixes
                            ON overlay_prefixes.prefix_id = overlay.prefix_id
                          WHERE overlay.filename = target.filename
                            AND overlay_prefixes.dir_path = target_prefixes.dir_path
                      )
                )
            )
    ),
    effective_reference_names(fq_name) AS (
        SELECT names.fq_name
        FROM effective_main_references edge
        JOIN fq_names names ON names.fq_id = edge.source_fq_id
        UNION
        SELECT names.fq_name
        FROM effective_main_references edge
        JOIN fq_names names ON names.fq_id = edge.target_fq_id
        UNION
        SELECT names.fq_name
        FROM effective_base_references edge
        JOIN repository_base.fq_names names
          ON names.fq_id = edge.source_fq_id
        UNION
        SELECT names.fq_name
        FROM effective_base_references edge
        JOIN repository_base.fq_names names
          ON names.fq_id = edge.target_fq_id
    ),
    node_keys(fq_name) AS (
        SELECT fq_name FROM effective_declarations
        UNION
        SELECT fq_name FROM effective_reference_names
    )
    SELECT keys.fq_name, declarations.kind, declarations.dir_path,
           declarations.filename, declarations.module_path,
           declarations.source_set
    FROM node_keys keys
    LEFT JOIN effective_declarations declarations
      ON declarations.fq_name = keys.fq_name
    ORDER BY keys.fq_name, declarations.origin,
             declarations.prefix_id, declarations.filename
"#;

const DERIVED_TOPOLOGY_REPOSITORY_EDGE_SQL: &str = r#"
    WITH effective_reference_rows(source, target, edge_kind, invalidated_target) AS (
        SELECT source.fq_name, target.fq_name, edge.edge_kind, 0
        FROM symbol_references edge
        JOIN file_manifest manifest
          ON manifest.prefix_id = edge.src_prefix_id
         AND manifest.filename = edge.src_filename
        LEFT JOIN fq_names source ON source.fq_id = edge.source_fq_id
        JOIN fq_names target ON target.fq_id = edge.target_fq_id
        UNION ALL
        SELECT source.fq_name, target.fq_name, edge.edge_kind,
               edge.tgt_prefix_id IS NOT NULL
               AND NOT EXISTS (
                   SELECT 1
                   FROM repository_base.file_manifest target
                   LEFT JOIN repository_base.path_prefixes target_prefixes
                     ON target_prefixes.prefix_id = target.prefix_id
                   WHERE target.prefix_id = edge.tgt_prefix_id
                     AND target.filename = edge.tgt_filename
                     AND NOT EXISTS (
                         SELECT 1
                         FROM repository_overlay_tombstones tombstone
                         WHERE tombstone.path = CASE
                             WHEN target_prefixes.dir_path = '' THEN target.filename
                             WHEN target_prefixes.dir_path = '__kast_rel__'
                                 THEN target.filename
                             WHEN target_prefixes.dir_path LIKE '__kast_rel__/%'
                                 THEN substr(
                                     target_prefixes.dir_path,
                                     length('__kast_rel__/') + 1
                                 ) || '/' || target.filename
                             WHEN target_prefixes.dir_path LIKE '__kast_abs__/%' THEN NULL
                             ELSE target_prefixes.dir_path || '/' || target.filename
                         END
                     )
                     AND NOT EXISTS (
                         SELECT 1
                         FROM file_manifest overlay
                         JOIN path_prefixes overlay_prefixes
                           ON overlay_prefixes.prefix_id = overlay.prefix_id
                         WHERE overlay.filename = target.filename
                           AND overlay_prefixes.dir_path = target_prefixes.dir_path
                     )
               )
        FROM repository_base.symbol_references edge
        JOIN repository_base.file_manifest manifest
          ON manifest.prefix_id = edge.src_prefix_id
         AND manifest.filename = edge.src_filename
        LEFT JOIN repository_base.path_prefixes prefixes
          ON prefixes.prefix_id = manifest.prefix_id
        LEFT JOIN repository_base.fq_names source
          ON source.fq_id = edge.source_fq_id
        JOIN repository_base.fq_names target
          ON target.fq_id = edge.target_fq_id
        WHERE NOT EXISTS (
                SELECT 1 FROM repository_overlay_tombstones tombstone
                WHERE tombstone.path = CASE
                    WHEN prefixes.dir_path = '' THEN manifest.filename
                    WHEN prefixes.dir_path = '__kast_rel__'
                        THEN manifest.filename
                    WHEN prefixes.dir_path LIKE '__kast_rel__/%'
                        THEN substr(
                            prefixes.dir_path,
                            length('__kast_rel__/') + 1
                        ) || '/' || manifest.filename
                    WHEN prefixes.dir_path LIKE '__kast_abs__/%' THEN NULL
                    ELSE prefixes.dir_path || '/' || manifest.filename
                END
            )
          AND NOT EXISTS (
                SELECT 1
                FROM file_manifest overlay
                JOIN path_prefixes overlay_prefixes
                  ON overlay_prefixes.prefix_id = overlay.prefix_id
                WHERE overlay.filename = manifest.filename
                  AND overlay_prefixes.dir_path = prefixes.dir_path
            )
    )
    SELECT source, target, edge_kind, COUNT(*), invalidated_target
    FROM effective_reference_rows
    GROUP BY source, target, edge_kind, invalidated_target
    ORDER BY source, target, edge_kind, invalidated_target
"#;
