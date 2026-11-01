// Progressive enhancement for Maple's desktop sidebar. Native mobile navigation stays in charge.
(() => {
  const preference = 'kast.sidebar.collapsed';
  const root = document.documentElement;
  let collapsed = false;
  try {
    collapsed = sessionStorage.getItem(preference) === 'true';
  } catch {
    // The toggle still works when browser storage is unavailable.
  }

  function install() {
    if (!document.getElementById('sidebar')) return false;
    if (document.getElementById('kast-sidebar-toggle')) return true;

    const button = document.createElement('button');
    button.id = 'kast-sidebar-toggle';
    button.type = 'button';
    button.setAttribute('aria-controls', 'sidebar');
    button.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16M16 9l-3 3 3 3"/></svg>';

    function render() {
      root.classList.toggle('kast-sidebar-collapsed', collapsed);
      button.setAttribute('aria-expanded', String(!collapsed));
      button.setAttribute('aria-label', collapsed ? 'Show navigation' : 'Hide navigation');
      button.title = collapsed ? 'Show navigation' : 'Hide navigation';
    }

    button.addEventListener('click', () => {
      collapsed = !collapsed;
      render();
      try {
        sessionStorage.setItem(preference, String(collapsed));
      } catch {
        // Storage is optional. The current page keeps the selected state.
      }
    });
    document.body.append(button);
    render();
    return true;
  }

  if (!install()) {
    // Mintlify can mount the layout after global scripts load.
    const observer = new MutationObserver(() => {
      if (install()) observer.disconnect();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
