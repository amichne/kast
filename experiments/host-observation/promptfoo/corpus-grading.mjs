export function exactAnswer(output, expected) {
  let items;
  try { items = JSON.parse(output); } catch { return false; }
  return Array.isArray(items) && items.length === expected.length &&
    new Set(items).size === items.length && expected.every(value => items.includes(value));
}
