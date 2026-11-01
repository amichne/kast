# Public diagrams

The `.mmd` files in `docs/public/images/architecture` own the diagram source.
The manifest `diagrams.json` links each source to its page and caption.
Beautiful Mermaid renders the source at build time. The package lock pins version `1.1.3` and its dependencies.

From the repository root, run:

```shell
npm ci --ignore-scripts --prefix docs/tooling/diagrams
npm run render --prefix docs/tooling/diagrams
npm run check --prefix docs/tooling/diagrams
```

The render command writes one JSX snippet and two SVG files for each source.
Commit these files with the source. The check command rejects missing or changed output.

Pages import the snippets from `docs/public/snippets/diagrams`.
Inline SVG files use the site's SUSE font and colors from `diagram-theme.css`.
The theme changes with the page. Each figure has a text label, an enlarge control, and links to its source and SVG.
Standalone SVG files use local SUSE when present, then the system sans-serif font.

The renderer removes font downloads and rejects active or remote SVG content.
It keeps node labels, edges, and class assignments from the source.
The renderer reads the site font and base colors from `docs.json`.
It owns SVG layout and class colors. It also writes `diagram-theme.css`.
The file `style.css` owns the figure layout. The `proof`, `result`, and `stop` classes use blue, green, and red.
Text explains each outcome without color.

After an edit, run `mint validate` and `mint broken-links` from `docs/public`.
Inspect the page in light and dark themes and on a narrow screen.
The renderer does not prove the facts that a diagram describes.
