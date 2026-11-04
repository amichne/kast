// Read-only DOM evidence. This script does not invoke Kast or change page content.
(async () => {
  await document.fonts.ready;
  const roots = [
    document.querySelector("#page-title"),
    document.querySelector("#header > .prose"),
    document.querySelector("#content"),
  ].filter(Boolean);
  if (roots.length < 2) throw new Error("RENDERED_CONTENT_MISSING");
  const visible = (element) => {
    if (element.closest('[hidden],[aria-hidden="true"]')) return false;
    for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
      const style = getComputedStyle(ancestor);
      const rect = ancestor.getBoundingClientRect();
      if (style.display === "none" || style.visibility === "hidden" || (rect.height < 1 && /hidden|clip/.test(style.overflowY)) || (rect.width < 1 && /hidden|clip/.test(style.overflowX))) return false;
    }
    const style = getComputedStyle(element);
    return style.visibility !== "hidden" && style.display !== "none" && element.getClientRects().length > 0;
  };
  const segments = [];
  const code = [];
  const walker = document.createTreeWalker(document.querySelector("#content"), NodeFilter.SHOW_TEXT);
  for (const root of roots) {
    const walk = root === document.querySelector("#content") ? walker : document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walk.nextNode()) {
      const node = walk.currentNode;
      const parent = node.parentElement;
      if (!parent || !visible(parent) || parent.closest("script,style,[data-testid=code-block-copy-button]")) continue;
      if (parent.closest("pre,.vj-code")) continue;
      const text = node.textContent.trim();
      if (!text) continue;
      const range = document.createRange();
      range.selectNodeContents(node);
      const rectangles = [...range.getClientRects()];
      segments.push({ text, initialViewport: rectangles.some(r => r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth) });
    }
  }
  for (const element of document.querySelectorAll("#content pre,#content .vj-code")) {
    if (visible(element)) code.push(element.innerText);
  }
  const target = document.activeElement;
  const style = target ? getComputedStyle(target) : null;
  const fonts = [];
  for (const name of [...new Set(performance.getEntriesByType("resource").map(entry => entry.name).filter(name => /\.(woff2?|ttf)(\?|$)/.test(name)))].sort()) {
    const response = await fetch(name);
    if (!response.ok) throw new Error("FONT_INPUT_FAILED");
    const digest = await crypto.subtle.digest("SHA-256", await response.arrayBuffer());
    fonts.push({ url: name.replace(location.origin, "{origin}"), sha256: [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("") });
  }
  return {
    title: document.querySelector("#page-title").textContent,
    route: location.pathname,
    segments,
    code,
    fonts,
    browser: navigator.userAgent,
    viewport: { width: innerWidth, height: innerHeight, deviceScaleFactor: devicePixelRatio, visualScale: visualViewport.scale },
    theme: document.documentElement.classList.contains("dark") ? "dark" : "light",
    documentOverflow: document.documentElement.scrollWidth > innerWidth + 1,
    headingCount: document.querySelectorAll("h1").length,
    missingImageAlternatives: [...document.querySelectorAll("#content img")].filter(img => !img.hasAttribute("alt")).length,
    focus: target ? { tag: target.tagName, name: target.matches('button,a,input,[role=button],[role=tab]') ? (target.getAttribute("aria-label") || target.textContent.trim()).slice(0,160) : "", visible: visible(target), focusVisible: target.matches(":focus-visible"), outline: style.outline, boxShadow: style.boxShadow } : null,
    controls: [...document.querySelectorAll("#content button,#content [role=button],#content [role=tab]")].filter(visible).map(element => ({ label: element.getAttribute("aria-label") || element.textContent.trim(), pressed: element.getAttribute("aria-pressed"), expanded: element.getAttribute("aria-expanded"), selected: element.getAttribute("aria-selected") })),
  };
})();
