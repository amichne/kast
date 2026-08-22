const FLAT_DIAGRAM_CSS = `
  *, *::before, *::after {
    box-shadow: none !important;
    text-shadow: none !important;
  }

  .likec4-element-shape {
    filter: none !important;
  }

  .likec4-navigation-panel__root {
    display: none !important;
  }

  .likec4-edge-label__contents,
  .likec4-edge-label__text {
    background-color: var(--kast-diagram-label-background) !important;
    color: var(--kast-diagram-label-text) !important;
  }

  .likec4-edge-label__contents {
    border: 1px solid var(--kast-diagram-label-border) !important;
  }

  .likec4-edge-label__technology {
    display: none !important;
  }

  [data-likec4-color="primary"],
  [data-likec4-color="blue"] {
    --likec4-palette-fill: var(--kast-diagram-discovery) !important;
    --likec4-palette-hiContrast: #ffffff !important;
  }

  [data-likec4-color="secondary"],
  [data-likec4-color="indigo"] {
    --likec4-palette-fill: var(--kast-diagram-identity) !important;
    --likec4-palette-hiContrast: #ffffff !important;
  }

  [data-likec4-color="green"] {
    --likec4-palette-fill: var(--kast-diagram-evidence) !important;
    --likec4-palette-hiContrast: #ffffff !important;
  }

  [data-likec4-color="amber"] {
    --likec4-palette-fill: var(--kast-diagram-effect) !important;
    --likec4-palette-hiContrast: #ffffff !important;
  }

  [data-likec4-color="muted"] {
    --likec4-palette-fill: var(--kast-diagram-muted) !important;
    --likec4-palette-hiContrast: #ffffff !important;
  }

  .likec4-element-title,
  .likec4-markdown-block,
  .likec4-markdown-block p {
    background-color: var(--likec4-palette-fill) !important;
    color: var(--likec4-palette-hiContrast) !important;
    opacity: 1 !important;
  }

  @media screen and (max-width: 44.984375em) {
    .likec4-edge-label {
      display: none !important;
    }
  }
`

const observedRoots = new WeakSet()
const retryDelays = [0, 50, 250, 1000]

const flattenElement = (element) => {
  element.style.setProperty("box-shadow", "none", "important")
  element.style.setProperty("text-shadow", "none", "important")

  if (element.classList.contains("likec4-element-shape")) {
    element.style.setProperty("filter", "none", "important")
  }
}

const styleShadowRoot = (root) => {
  if (!root) return

  if (!root.querySelector("style[data-kast-flat-surfaces]")) {
    const style = document.createElement("style")
    style.dataset.kastFlatSurfaces = ""
    style.textContent = FLAT_DIAGRAM_CSS
    root.append(style)
  }

  root.querySelectorAll("*").forEach((element) => {
    flattenElement(element)
    styleShadowRoot(element.shadowRoot)
  })

  if (!observedRoots.has(root)) {
    const observer = new MutationObserver(() => styleShadowRoot(root))
    observer.observe(root, { childList: true, subtree: true })
    observedRoots.add(root)
  }
}

const flattenDiagram = (diagram) => {
  const apply = () => styleShadowRoot(diagram.shadowRoot)
  apply()
  retryDelays.forEach((delay) => window.setTimeout(apply, delay))
}

const flattenDiagrams = () => {
  document.querySelectorAll("kast-view").forEach(flattenDiagram)
}

if (typeof document$ === "undefined") {
  document.addEventListener("DOMContentLoaded", flattenDiagrams)
} else {
  document$.subscribe(flattenDiagrams)
}
