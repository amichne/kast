(() => {
  const installedMarker = "__kastLikeC4MountInstalled";
  if (window[installedMarker]) return;
  window[installedMarker] = true;

  const mount = (container) => {
    if (container.dataset.kastMounted === "true") return;

    const viewId = container.dataset.kastView;
    if (!viewId) return;

    const view = document.createElement("kast-view");
    view.setAttribute("view-id", viewId);
    view.setAttribute("browser", container.dataset.kastBrowser || "false");

    const dynamicVariant = container.dataset.kastDynamicVariant;
    if (dynamicVariant) view.setAttribute("dynamic-variant", dynamicVariant);

    container.replaceChildren(view);
    container.dataset.kastMounted = "true";
  };

  const mountAll = () => {
    document.querySelectorAll("[data-kast-view]").forEach(mount);
  };

  const begin = () => {
    mountAll();
    new MutationObserver(mountAll).observe(document.body, {
      childList: true,
      subtree: true,
    });
  };

  customElements.whenDefined("kast-view").then(() => {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", begin, { once: true });
    } else {
      begin();
    }
  });
})();
