# Interface metadata

Interface metadata is optional. Keep the core skill portable and add UI-facing metadata only when the target platform supports it and the user wants it.

## Current adapter

The bundled helper currently emits `agents/openai.yaml`, because some runtimes use that file for skill-list presentation.

Generate it with:

```bash
python3 scripts/generate_ui_metadata.py /path/to/skill --target openai --interface default_prompt="Use $skill-name to ..."
```

## Example

```yaml
interface:
  display_name: "Skill Creator"
  short_description: "Create, test, and improve skills"
  default_prompt: "Use $skill-creator to turn this workflow into a reusable skill with an eval suite."
```

## Rules

- Keep this metadata optional.
- Do not depend on it for core skill behavior.
- Keep frontmatter portable; put runtime-specific metadata in `agents/`.
- Quote all string values.
- Generate metadata from the actual skill content, not from a hand-wavy label.

## Supported keys

- `display_name`
- `short_description`
- `default_prompt`
- `icon_small`
- `icon_large`
- `brand_color`

The generator enforces the currently supported keys for the `openai` target.
