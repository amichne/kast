# Architecture diagrams

From the repository root, use the pinned Node version and the locked renderer:

```shell
npm ci --ignore-scripts --prefix docs/tooling/diagrams
npm run render --prefix docs/tooling/diagrams
```

`docs/public/images/architecture/*.mmd` owns the authored diagrams. Beautiful Mermaid `1.1.3` renders a light and a dark SVG for each source. The package lock pins all dependencies. Hosted MDX loads these files without `npm` imports.

Each page has alternative text and a numbered text guide beside the diagram. Links open the full-size SVG and Mermaid source. Labels carry meaning without color. The renderer rejects scripts, external images, animation, and `foreignObject`.

Each diagram explains a limited set of source facts. It does not import or repair DeepWiki diagrams. The review report names source owners. It records changes in meaning apart from how the diagram looks.
