package io.github.amichne.kast.indexstore.store

import java.sql.Connection

internal object RepositoryOverlaySemanticViews {
    fun install(connection: Connection) {
        connection.createStatement().use { statement ->
            semanticViews.forEach { view -> view.install(statement) }
        }
    }

    private val semanticViews = OverlayViewDefinition.schemaOwnedAll(
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_file_authority AS
           SELECT files.path, files.id AS effective_id
           FROM main.semantic_files files
           WHERE NOT EXISTS (
               SELECT 1 FROM main.repository_overlay_tombstones tombstone
               WHERE tombstone.path = files.path
           )
             AND (
                   files.refresh_status != 'CACHED'
                   OR NOT EXISTS (
                       SELECT 1 FROM repository_base.semantic_files base
                       WHERE base.path = files.path
                   )
               )
           UNION ALL
           SELECT files.path, -files.id
           FROM repository_base.semantic_files files
           WHERE NOT EXISTS (
                   SELECT 1 FROM main.repository_overlay_tombstones tombstone
                   WHERE tombstone.path = files.path
               )
             AND NOT EXISTS (
                   SELECT 1 FROM main.semantic_files overlay
                   WHERE overlay.path = files.path AND overlay.refresh_status != 'CACHED'
               )""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_files AS
           SELECT authority.effective_id AS id, files.path, files.package_name, files.module_name,
                  files.content_hash, files.refresh_status, files.diagnostics_json,
                  files.boundary_failure_id, files.boundary_failure_code
           FROM effective_semantic_file_authority authority
           JOIN main.semantic_files files
             ON files.id = authority.effective_id
           UNION ALL
           SELECT authority.effective_id, files.path, files.package_name, files.module_name,
                  files.content_hash, files.refresh_status, files.diagnostics_json,
                  files.boundary_failure_id, files.boundary_failure_code
           FROM effective_semantic_file_authority authority
           JOIN repository_base.semantic_files files
             ON -files.id = authority.effective_id""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_types AS
           SELECT types.* FROM main.semantic_types types
           UNION ALL
           SELECT -types.id, types.stable_key, types.kind, types.classifier,
                  types.nullability, types.debug_text,
                  COALESCE(main_lower.id, -types.flexible_lower_id),
                  COALESCE(main_upper.id, -types.flexible_upper_id),
                  COALESCE(main_receiver.id, -types.receiver_type_id),
                  COALESCE(main_returned.id, -types.return_type_id)
           FROM repository_base.semantic_types types
           LEFT JOIN repository_base.semantic_types base_lower
             ON base_lower.id = types.flexible_lower_id
           LEFT JOIN main.semantic_types main_lower
             ON main_lower.stable_key = base_lower.stable_key
           LEFT JOIN repository_base.semantic_types base_upper
             ON base_upper.id = types.flexible_upper_id
           LEFT JOIN main.semantic_types main_upper
             ON main_upper.stable_key = base_upper.stable_key
           LEFT JOIN repository_base.semantic_types base_receiver
             ON base_receiver.id = types.receiver_type_id
           LEFT JOIN main.semantic_types main_receiver
             ON main_receiver.stable_key = base_receiver.stable_key
           LEFT JOIN repository_base.semantic_types base_returned
             ON base_returned.id = types.return_type_id
           LEFT JOIN main.semantic_types main_returned
             ON main_returned.stable_key = base_returned.stable_key
           WHERE NOT EXISTS (
               SELECT 1 FROM main.semantic_types overlay
               WHERE overlay.stable_key = types.stable_key
           )""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_symbol_authority AS
           SELECT symbols.stable_key, symbols.id AS effective_id
           FROM main.semantic_symbols symbols
           JOIN effective_semantic_files files ON files.id = symbols.file_id
           WHERE files.refresh_status != 'CACHED'
              OR NOT EXISTS (
                   SELECT 1
                   FROM repository_base.semantic_symbols base
                   JOIN effective_semantic_files base_file ON base_file.id = -base.file_id
                   WHERE base.stable_key = symbols.stable_key
               )
           UNION ALL
           SELECT symbols.stable_key, -symbols.id
           FROM repository_base.semantic_symbols symbols
           JOIN effective_semantic_files files ON files.id = -symbols.file_id
           WHERE NOT EXISTS (
               SELECT 1
               FROM main.semantic_symbols overlay
               JOIN effective_semantic_files overlay_file ON overlay_file.id = overlay.file_id
               WHERE overlay.stable_key = symbols.stable_key
                 AND overlay_file.refresh_status != 'CACHED'
           )""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_symbols AS
           SELECT symbols.id, symbols.stable_key, symbols.file_id, owner.effective_id AS owner_id,
                  symbols.kind, symbols.name, symbols.fq_name, symbols.signature,
                  symbols.visibility, symbols.modality, symbols.origin,
                  symbols.is_expect, symbols.is_actual, symbols.is_override,
                  symbols.is_sealed, symbols.is_delegated,
                  symbols.declared_type_id, symbols.receiver_type_id, symbols.return_type_id,
                  symbols.start_offset, symbols.end_offset, symbols.line
           FROM main.semantic_symbols symbols
           JOIN effective_semantic_symbol_authority authority ON authority.effective_id = symbols.id
           JOIN effective_semantic_files files ON files.id = symbols.file_id
           LEFT JOIN main.semantic_symbols raw_owner ON raw_owner.id = symbols.owner_id
           LEFT JOIN effective_semantic_symbol_authority owner ON owner.stable_key = raw_owner.stable_key
           UNION ALL
           SELECT -symbols.id, symbols.stable_key, -symbols.file_id,
                  owner.effective_id, symbols.kind, symbols.name,
                  symbols.fq_name, symbols.signature, symbols.visibility, symbols.modality,
                  symbols.origin, symbols.is_expect, symbols.is_actual, symbols.is_override,
                  symbols.is_sealed, symbols.is_delegated,
                  COALESCE(main_declared.id, -symbols.declared_type_id),
                  COALESCE(main_receiver.id, -symbols.receiver_type_id),
                  COALESCE(main_returned.id, -symbols.return_type_id),
                  symbols.start_offset, symbols.end_offset, symbols.line
           FROM repository_base.semantic_symbols symbols
           JOIN effective_semantic_symbol_authority authority ON authority.effective_id = -symbols.id
           JOIN effective_semantic_files files ON files.id = -symbols.file_id
           LEFT JOIN repository_base.semantic_symbols raw_owner ON raw_owner.id = symbols.owner_id
           LEFT JOIN effective_semantic_symbol_authority owner ON owner.stable_key = raw_owner.stable_key
           LEFT JOIN repository_base.semantic_types base_declared
             ON base_declared.id = symbols.declared_type_id
           LEFT JOIN main.semantic_types main_declared
             ON main_declared.stable_key = base_declared.stable_key
           LEFT JOIN repository_base.semantic_types base_receiver
             ON base_receiver.id = symbols.receiver_type_id
           LEFT JOIN main.semantic_types main_receiver
             ON main_receiver.stable_key = base_receiver.stable_key
           LEFT JOIN repository_base.semantic_types base_returned
             ON base_returned.id = symbols.return_type_id
           LEFT JOIN main.semantic_types main_returned
             ON main_returned.stable_key = base_returned.stable_key""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_type_edges AS
           SELECT edges.*
           FROM main.semantic_type_edges edges
           JOIN effective_semantic_types parent ON parent.id = edges.parent_type_id
           LEFT JOIN effective_semantic_types child ON child.id = edges.child_type_id
           WHERE edges.child_type_id IS NULL OR child.id IS NOT NULL
           UNION ALL
           SELECT -edges.id, parent.id, child.id, edges.role, edges.position, edges.variance
           FROM repository_base.semantic_type_edges edges
           JOIN repository_base.semantic_types base_parent ON base_parent.id = edges.parent_type_id
           JOIN effective_semantic_types parent ON parent.stable_key = base_parent.stable_key
           LEFT JOIN repository_base.semantic_types base_child ON base_child.id = edges.child_type_id
           LEFT JOIN effective_semantic_types child ON child.stable_key = base_child.stable_key
           WHERE edges.child_type_id IS NULL OR child.id IS NOT NULL""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_symbol_annotations AS
           SELECT annotations.*
           FROM main.semantic_symbol_annotations annotations
           JOIN effective_semantic_symbols symbols ON symbols.id = annotations.symbol_id
           UNION ALL
           SELECT symbols.id, annotations.annotation_name
           FROM repository_base.semantic_symbol_annotations annotations
           JOIN repository_base.semantic_symbols base_symbol ON base_symbol.id = annotations.symbol_id
           JOIN effective_semantic_symbols symbols ON symbols.id = -base_symbol.id""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_edge_occurrences AS
           SELECT edges.id, source.id AS source_id, target.id AS target_id,
                  source_file.id AS source_file_id, edges.kind, edges.context,
                  resolved.id AS resolved_target_id,
                  edges.start_offset, edges.end_offset, edges.line
           FROM main.semantic_edge_occurrences edges
           JOIN effective_semantic_files source_file ON source_file.id = edges.source_file_id
           JOIN main.semantic_symbols raw_source ON raw_source.id = edges.source_id
           JOIN effective_semantic_symbols source ON source.stable_key = raw_source.stable_key
           JOIN main.semantic_symbols raw_target ON raw_target.id = edges.target_id
           JOIN effective_semantic_symbols target ON target.stable_key = raw_target.stable_key
           LEFT JOIN main.semantic_symbols raw_resolved ON raw_resolved.id = edges.resolved_target_id
           LEFT JOIN effective_semantic_symbols resolved ON resolved.stable_key = raw_resolved.stable_key
           WHERE edges.resolved_target_id IS NULL OR resolved.id IS NOT NULL
           UNION ALL
           SELECT -edges.id, source.id, target.id, source_file.id,
                  edges.kind, edges.context, resolved.id,
                  edges.start_offset, edges.end_offset, edges.line
           FROM repository_base.semantic_edge_occurrences edges
           JOIN repository_base.semantic_files base_file ON base_file.id = edges.source_file_id
           JOIN effective_semantic_files source_file ON source_file.id = -base_file.id
           JOIN repository_base.semantic_symbols base_source ON base_source.id = edges.source_id
           JOIN effective_semantic_symbols source ON source.stable_key = base_source.stable_key
           JOIN repository_base.semantic_symbols base_target ON base_target.id = edges.target_id
           JOIN effective_semantic_symbols target ON target.stable_key = base_target.stable_key
           LEFT JOIN repository_base.semantic_symbols base_resolved
             ON base_resolved.id = edges.resolved_target_id
           LEFT JOIN effective_semantic_symbols resolved
             ON resolved.stable_key = base_resolved.stable_key
           WHERE edges.resolved_target_id IS NULL OR resolved.id IS NOT NULL""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_file_quotient AS
           SELECT source.file_id AS source_container_id,
                  target.file_id AS target_container_id,
                  edges.kind, edges.context, COUNT(*) AS weight
           FROM effective_semantic_edge_occurrences edges
           JOIN effective_semantic_symbols source ON source.id = edges.source_id
           JOIN effective_semantic_symbols target ON target.id = edges.target_id
           GROUP BY source.file_id, target.file_id, edges.kind, edges.context""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_package_quotient AS
           SELECT source_file.package_name AS source_container,
                  target_file.package_name AS target_container,
                  edges.kind, edges.context, COUNT(*) AS weight
           FROM effective_semantic_edge_occurrences edges
           JOIN effective_semantic_symbols source ON source.id = edges.source_id
           JOIN effective_semantic_symbols target ON target.id = edges.target_id
           JOIN effective_semantic_files source_file ON source_file.id = source.file_id
           JOIN effective_semantic_files target_file ON target_file.id = target.file_id
           WHERE source_file.package_name IS NOT NULL AND target_file.package_name IS NOT NULL
           GROUP BY source_file.package_name, target_file.package_name, edges.kind, edges.context""",
        """CREATE TEMP VIEW IF NOT EXISTS effective_semantic_module_quotient AS
           SELECT source_file.module_name AS source_container,
                  target_file.module_name AS target_container,
                  edges.kind, edges.context, COUNT(*) AS weight
           FROM effective_semantic_edge_occurrences edges
           JOIN effective_semantic_symbols source ON source.id = edges.source_id
           JOIN effective_semantic_symbols target ON target.id = edges.target_id
           JOIN effective_semantic_files source_file ON source_file.id = source.file_id
           JOIN effective_semantic_files target_file ON target_file.id = target.file_id
           WHERE source_file.module_name IS NOT NULL AND target_file.module_name IS NOT NULL
           GROUP BY source_file.module_name, target_file.module_name, edges.kind, edges.context""",
    )
}
