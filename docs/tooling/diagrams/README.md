# Architecture diagrams

From the repository root, use the pinned Node version and the locked renderer:

```shell
npm ci --ignore-scripts --prefix docs/tooling/diagrams
npm run render --prefix docs/tooling/diagrams
```

`docs/public/images/architecture/*.mmd` owns the authored diagrams. Beautiful Mermaid `1.1.3` renders separate light and dark SVGs. The lockfile pins transitive packages. Hosted MDX loads these assets without npm imports.

Each page supplies alternative text, an adjacent ordered explanation, and links to the full-size SVG and Mermaid source. Labels carry meaning without color. The renderer rejects scripts, external images, animation, and `foreignObject`.

These are bounded source-backed summaries. They do not import or repair DeepWiki diagrams. The review report records source owners and semantic changes separately from rendering.
