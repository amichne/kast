const [load, setLoad] = useState({ kind: "loading" });
useEffect(() => {
  let disposed = false;
  const controller = new AbortController();
  const timeout = setTimeout(() => { controller.abort(); if (!disposed) setLoad({ kind: "failed" }); }, 20000);
  Promise.resolve()
    .then(() => {
      if (typeof DecompressionStream !== "function") throw new Error("SCHEMA_DECODER_UNAVAILABLE");
      const compressed = Uint8Array.from(atob(compressedGraphBase64), value => value.charCodeAt(0));
      const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream("gzip"), { signal: controller.signal });
      return new Response(stream).arrayBuffer();
    })
    .then(async bytes => {
      if (bytes.byteLength > 20000000) throw new Error("SCHEMA_DATA_INVALID");
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      const sha = Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, "0")).join("");
      if (sha !== expectedDataSha) throw new Error("SCHEMA_DATA_CHANGED");
      return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    })
    .then(graph => {
      if (!graph || graph.schemaVersion !== 1 || Object.keys(graph).sort().join(",") !== "nodes,operations,schemaVersion" || !Array.isArray(graph.nodes) || typeof graph.operations !== "object" || !graph.operations) throw new Error("SCHEMA_DATA_INVALID");
      const validEdge = edge => edge && Object.keys(edge).sort().join(",") === "label,node" && typeof edge.label === "string" && Number.isInteger(edge.node) && edge.node >= 0 && edge.node < graph.nodes.length;
      for (const node of graph.nodes) {
        if (!node || Object.keys(node).sort().join(",") !== "children,facts,parent,path" || typeof node.path !== "string" || !(node.parent === null || Number.isInteger(node.parent) && node.parent >= 0 && node.parent < graph.nodes.length) || !Array.isArray(node.facts) || !Array.isArray(node.children) || !node.children.every(validEdge) || !node.facts.every(fact => fact && Object.keys(fact).sort().join(",") === "keyword,value" && typeof fact.keyword === "string" && typeof fact.value === "string")) throw new Error("SCHEMA_DATA_INVALID");
      }
      for (const entry of Object.values(graph.operations)) if (!entry || Object.keys(entry).join(",") !== "roots" || !Array.isArray(entry.roots) || entry.roots.length !== 2 || !entry.roots.every(validEdge)) throw new Error("SCHEMA_DATA_INVALID");
      // Pointer ancestry is structural; references remain child edges and may cycle.
      const pointers = new Map();
      function pointer(index, active = new Set()) {
        if (pointers.has(index)) return pointers.get(index);
        if (active.has(index)) throw new Error("SCHEMA_DATA_INVALID");
        active.add(index);
        const node = graph.nodes[index];
        const value = (node.parent === null ? "" : pointer(node.parent, active)) + node.path;
        active.delete(index); pointers.set(index, value); return value;
      }
      graph.nodes.forEach((node, index) => pointer(index));
      if (!controller.signal.aborted) setLoad({ kind: "ready", graph, pointers });
    })
    .catch(() => { if (!disposed && !controller.signal.aborted) setLoad({ kind: "failed" }); })
    .finally(() => clearTimeout(timeout));
  return () => { disposed = true; controller.abort(); clearTimeout(timeout); };
}, []);
function SchemaNode({ graph, pointers, index, occurrence, label, depth }) {
  const [expanded, setExpanded] = useState(false);
  const node = graph.nodes[index];
  return <div className="ks-node" data-schema-occurrence={occurrence} data-schema-pointer={pointers.get(index)} data-schema-depth={depth}>
    {node.children.length > 0 ?
      <button type="button" className="ks-toggle" data-schema-toggle={occurrence} aria-expanded={expanded} aria-controls={`schema-${occurrence}`} onClick={() => setExpanded(value => !value)}>
        <span aria-hidden="true">{expanded ? "−" : "+"}</span> {label}
      </button> : <div className="ks-label">{label}</div>}
    {node.facts.length > 0 && <dl className="ks-facts">{node.facts.map(fact => <div key={fact.keyword}><dt>{fact.keyword}</dt><dd><code>{fact.value}</code></dd></div>)}</dl>}
    {node.children.length > 0 && <div id={`schema-${occurrence}`} className="ks-children" hidden={!expanded}>
      {expanded && node.children.map((child, childIndex) => <SchemaNode key={childIndex} graph={graph} pointers={pointers} index={child.node} occurrence={`${occurrence}-${childIndex}`} label={child.label} depth={depth + 1} />)}
    </div>}
  </div>;
}
function Reference({ operation }) {
  if (load.kind === "loading") return <div role="status">Loading schemas…</div>;
  if (load.kind === "failed" || !Object.hasOwn(load.graph.operations, operation)) return <div role="alert" data-schema-error="load-failed">The schema viewer could not load. Use the complete contract download above.</div>;
  const { graph, pointers } = load;
  return <div className="ks-reference" data-schema-operation={operation}>
    {graph.operations[operation].roots.map((root, index) => <section key={root.node} aria-label={root.label}>
      <h2 id={index === 0 ? "inputs" : "response-fields"}>{root.label}</h2>
      <SchemaNode graph={graph} pointers={pointers} index={root.node} occurrence={`root-${index}`} label={root.label + " schema"} depth={0} />
    </section>)}
  </div>;
}
