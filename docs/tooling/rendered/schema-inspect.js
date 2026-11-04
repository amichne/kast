// Read only exposed schema facts. Below-fold text remains in the finite scope.
(() => {
  const visible = element => {
    if (!element || element.closest('[hidden],[aria-hidden="true"]')) return false;
    for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
      const style = getComputedStyle(ancestor);
      const rect = ancestor.getBoundingClientRect();
      if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0'
        || (rect.height < 1 && /hidden|clip/.test(style.overflowY))
        || (rect.width < 1 && /hidden|clip/.test(style.overflowX))) return false;
    }
    return element.getClientRects().length > 0;
  };
  const nodes = Array.from(document.querySelectorAll('[data-schema-occurrence]'));
  if (document.querySelector('[data-schema-error]') || nodes.length > 500
    || nodes.some(node => !visible(node) || Array.from(node.querySelectorAll(':scope > .ks-facts > div'))
      .some(fact => !visible(fact) || !visible(fact.querySelector('dt')) || !visible(fact.querySelector('dd code'))))) return null;
  return nodes.map(node => {
    const toggle = node.querySelector(':scope > [data-schema-toggle]');
    return {
      occurrence: node.dataset.schemaOccurrence,
      pointer: node.dataset.schemaPointer,
      depth: Number(node.dataset.schemaDepth),
      expanded: toggle ? toggle.getAttribute('aria-expanded') === 'true' : null,
      facts: Array.from(node.querySelectorAll(':scope > .ks-facts > div'))
        .map(fact => [fact.querySelector('dt').textContent, fact.querySelector('dd code').textContent]),
    };
  });
})();
