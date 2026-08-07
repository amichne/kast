package io.github.amichne.kast.indexstore.store

internal object SymbolOverlayViews {
    val definitions: List<OverlayViewDefinition> = listOf(
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_declarations(
                   fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set
               ) AS
               SELECT declarations.fq_id, declarations.kind, declarations.visibility,
                      authority.effective_prefix_id, declarations.filename,
                      declarations.declaration_offset, declarations.module_path, declarations.source_set
               FROM main.declarations declarations
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'main'
                AND authority.source_prefix_id = declarations.prefix_id
                AND authority.filename = declarations.filename
               UNION ALL
               SELECT COALESCE(overlay_fq.fq_id, -declarations.fq_id),
                      declarations.kind, declarations.visibility, authority.effective_prefix_id,
                      declarations.filename, declarations.declaration_offset,
                      declarations.module_path, declarations.source_set
               FROM repository_base.declarations declarations
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'base'
                AND authority.source_prefix_id = declarations.prefix_id
                AND authority.filename = declarations.filename
               JOIN repository_base.fq_names base_fq ON base_fq.fq_id = declarations.fq_id
               LEFT JOIN main.fq_names overlay_fq ON overlay_fq.fq_name = base_fq.fq_name""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_declaration_supertypes(
                   declaration_fq_id, supertype_fq_id
               ) AS
               SELECT declaration_fq_id, supertype_fq_id FROM main.declaration_supertypes
               UNION
               SELECT COALESCE(main_declaration.fq_id, -base_edge.declaration_fq_id),
                      COALESCE(main_supertype.fq_id, -base_edge.supertype_fq_id)
               FROM repository_base.declaration_supertypes base_edge
               JOIN repository_base.fq_names base_declaration
                 ON base_declaration.fq_id = base_edge.declaration_fq_id
               JOIN repository_base.fq_names base_supertype
                 ON base_supertype.fq_id = base_edge.supertype_fq_id
               LEFT JOIN main.fq_names main_declaration
                 ON main_declaration.fq_name = base_declaration.fq_name
               LEFT JOIN main.fq_names main_supertype
                 ON main_supertype.fq_name = base_supertype.fq_name
               WHERE EXISTS (
                   SELECT 1 FROM effective_declarations declaration
                   WHERE declaration.fq_id = COALESCE(main_declaration.fq_id, -base_edge.declaration_fq_id)
               )""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_symbol_references(
                   src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                   tgt_prefix_id, tgt_filename, target_offset, edge_kind
               ) AS
               SELECT authority.effective_prefix_id, edge.src_filename, edge.source_offset,
                      edge.source_fq_id, edge.target_fq_id, edge.tgt_prefix_id,
                      edge.tgt_filename, edge.target_offset, edge.edge_kind
               FROM main.symbol_references edge
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'main'
                AND authority.source_prefix_id = edge.src_prefix_id
                AND authority.filename = edge.src_filename
               UNION ALL
               SELECT authority.effective_prefix_id, edge.src_filename, edge.source_offset,
                      COALESCE(main_source.fq_id, -edge.source_fq_id),
                      COALESCE(main_target.fq_id, -edge.target_fq_id),
                      CASE WHEN target_authority.origin = 'base' THEN target_authority.effective_prefix_id END,
                      CASE WHEN target_authority.origin = 'base' THEN edge.tgt_filename END,
                      CASE WHEN target_authority.origin = 'base' THEN edge.target_offset END,
                      edge.edge_kind
               FROM repository_base.symbol_references edge
               JOIN repository_effective_file_authority authority
                 ON authority.origin = 'base'
                AND authority.source_prefix_id = edge.src_prefix_id
                AND authority.filename = edge.src_filename
               LEFT JOIN repository_base.fq_names base_source ON base_source.fq_id = edge.source_fq_id
               LEFT JOIN main.fq_names main_source ON main_source.fq_name = base_source.fq_name
               JOIN repository_base.fq_names base_target ON base_target.fq_id = edge.target_fq_id
               LEFT JOIN main.fq_names main_target ON main_target.fq_name = base_target.fq_name
               LEFT JOIN repository_effective_file_authority target_authority
                 ON target_authority.origin = 'base'
                AND target_authority.source_prefix_id = edge.tgt_prefix_id
                AND target_authority.filename = edge.tgt_filename""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_module_index_progress AS
               SELECT module_name, relationship_index_status, indexed_file_count,
                      total_file_count, last_indexed_epoch_ms
               FROM main.module_index_progress
               UNION ALL
               SELECT base.module_name, base.relationship_index_status, base.indexed_file_count,
                      base.total_file_count, base.last_indexed_epoch_ms
               FROM repository_base.module_index_progress base
               WHERE NOT EXISTS (
                   SELECT 1 FROM main.module_index_progress overlay
                   WHERE overlay.module_name = base.module_name
               )""",
        ),
        OverlayViewDefinition.schemaOwned(
            """CREATE TEMP VIEW IF NOT EXISTS effective_workspace_discovery AS
               SELECT cache_key, schema_version, payload
               FROM main.workspace_discovery
               UNION ALL
               SELECT base.cache_key, base.schema_version, base.payload
               FROM repository_base.workspace_discovery base
               WHERE NOT EXISTS (
                   SELECT 1 FROM main.workspace_discovery overlay
                   WHERE overlay.cache_key = base.cache_key
               )""",
        ),
    )
}
