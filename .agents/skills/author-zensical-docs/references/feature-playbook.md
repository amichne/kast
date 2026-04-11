# Zensical feature playbook

Use this file to decide which Zensical feature improves the document instead of
just making it busier.

## Fast selection matrix

| Need | Reach for | Use it well |
| --- | --- | --- |
| Give a page identity in nav and metadata | Front matter | Set `title`, `description`, `icon`, and `status` deliberately. Reach for `template` and `hide` only with intent. |
| Offer alternative environments, languages, or config dialects | Content tabs | Keep labels stable so linked tabs remain useful across pages. |
| Create an impressive section index or landing page | Grids | Keep each card focused on one reader intent or destination. |
| Surface warnings, tips, or side constraints | Admonitions | Keep callouts short and secondary to the main narrative. |
| Explain exact commands, configs, or source edits | Code blocks | Prefer minimal working examples. Enable copy, selection, or annotations only when they improve learning. |
| Compare options or provide lookup information | Data tables | Keep cells short. Switch to prose when the table starts carrying paragraphs. |
| Explain flow, architecture, or state | Mermaid diagrams | Keep node labels terse and let the surrounding prose explain stakes and nuance. |
| Show UI, before/after visuals, or conceptual illustrations | Images | Always add alt text. Add captions when the image carries a specific takeaway. |
| Change branding, layout, or behavior | Customization | Start with theme configuration, then use extra CSS or JS, then use template overrides. |

## Feature notes

### Front matter

- Use `title` to sharpen navigation labels without forcing a matching H1.
- Use `description` to improve social metadata and page summaries.
- Use `icon` and `status` when they help navigation scanning.
- Use `template` for exceptional layouts, not routine pages.
- Use `hide` sparingly; if readers still need navigation or a table of contents,
  keep them visible.

### Content tabs

- Use tabs for mutually exclusive choices such as `zensical.toml` vs
  `mkdocs.yml`, or Python vs JavaScript examples.
- Avoid tabs when readers need to compare options at the same time; use a table
  instead.
- Keep tab labels identical across pages when the same choice recurs. This
  makes linked tabs feel intentional.

### Grids

- Use grids on homepages, section indexes, and comparison pages.
- Keep card copy short and action-oriented.
- Do not put long explanations inside cards. Let cards route into deeper pages.

### Admonitions

- Use `note`, `info`, `tip`, and `warning` to control emphasis.
- Put the main path in normal prose and steps. Put exceptions or supporting
  context in admonitions.
- Avoid stacking many admonitions back to back.

### Code blocks

- Match the user's existing dialect unless comparison is the point.
- Show the smallest runnable or copyable example first.
- Use annotations only when the explanation depends on a specific line or token.
- Use selection or copy affordances when the example is likely to be reused.

### Data tables

- Use tables for option matrices, feature comparisons, key mappings, and status
  overviews.
- Keep cells parallel and terse.
- Consider sortable tables only when readers genuinely benefit from
  re-ordering.

### Diagrams

- Use Mermaid for process, sequence, state, and architecture views.
- Prefer one strong diagram over multiple weak ones.
- Keep labels short enough to survive mobile viewports.

### Images

- Use captions when the image is evidence, not decoration.
- Use `#only-light` and `#only-dark` variants when the same illustration needs
  separate light and dark versions.
- Use lazy loading for large screenshots or long pages.

### Customization

- Prefer configuration before overrides.
- Prefer overrides before custom JavaScript.
- When adding JavaScript to a site with instant navigation, bind behavior via
  `document$`.
- Explain where the asset lives, how it is wired in, and what problem it solves.

## Configuration reminders

- Admonitions: `admonition`, `pymdownx.details`, `pymdownx.superfences`
- Tabs: `pymdownx.superfences`, `pymdownx.tabbed`
- Grids: `attr_list`, `md_in_html`
- Images with captions: `attr_list`, `md_in_html`,
  `pymdownx.blocks.caption`
- Code blocks: `pymdownx.highlight`, `pymdownx.inlinehilite`,
  `pymdownx.snippets`, `pymdownx.superfences`
- Mermaid diagrams: custom `mermaid` fence in `pymdownx.superfences`
- Tables: `tables`

Note the feature requirements in the doc when readers would otherwise copy a
pattern that fails without configuration.
