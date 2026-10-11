/** Invocation-owned sequential query transport. No child survives a logical query. */
function createQuerySession(command, cwd, policy, signal, spawnProcess, environment) {
  let child, pending, timer, closed = false, stopped = null, closing = false;
  let record = Buffer.alloc(0), responseBytes = 0;
  const childEnvironment = { ...environment };
  delete childEnvironment.KAST_TOOL_RPC_COMMAND;
  delete childEnvironment.KAST_EVALUATION_DEADLINE_MS;
  let finishExit;
  const exit = new Promise(resolve => { finishExit = resolve; });
  const failure = reason => new Error(`Kast query session outcome=${reason}; submission may be ambiguous, do not resubmit RUN`);
  const stop = reason => {
    stopped ??= reason;
    if (pending) { const current = pending; pending = null;
      current.policy.onResponseBytes?.(responseBytes); responseBytes = 0; current.reject(failure(stopped)); }
    child?.kill('SIGKILL');
    // Release owned pipes even if another process inherited a descriptor; close must not await that peer.
    child?.stdin.destroy?.(); child?.stdout.destroy?.(); child?.stderr.destroy?.();
  };
  const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
  const abort = () => stop('CANCELLED');
  const launch = () => {
    if (signal?.aborted) throw failure('CANCELLED');
    child = spawnProcess(command, ['query-session'], { cwd, env: childEnvironment, stdio: ['pipe', 'pipe', 'pipe'] });
    timer = setTimeout(() => stop('TIMEOUT'), policy.callTimeoutMillis);
    signal?.addEventListener('abort', abort, { once: true });
    child.stderr.on('data', () => {}); // No arbitrary process diagnostics or source payload retained.
    child.stdin.on('error', () => stop('INPUT_FAILED'));
    child.on('error', () => { stop('LAUNCH_FAILED'); cleanup(); finishExit(); });
    child.on('close', code => {
      closed = true;
      if (pending) stop(stopped ?? (code === 0 ? 'MISSING_REPLY' : 'NONZERO_EXIT'));
      cleanup(); finishExit();
    });
    child.stdout.on('data', chunk => {
      if (stopped || closing) return;
      if (!pending) return stop('UNEXPECTED_REPLY');
      responseBytes += chunk.length;
      if (responseBytes > pending.policy.maxResponseBytes) return stop('OUTPUT_OVERFLOW');
      record = Buffer.concat([record, chunk]);
      const boundary = record.indexOf(10);
      if (boundary === -1) return;
      // Exactly one reply per admitted request; never buffer unsolicited records.
      if (boundary !== record.length - 1) return stop('UNEXPECTED_REPLY');
      let reply;
      try { reply = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(record.subarray(0, boundary))); }
      catch { return stop('INVALID_REPLY'); }
      if (!isToolResult(reply)) return stop('INVALID_RESULT');
      const current = pending;
      pending = null; record = Buffer.alloc(0);
      current.policy.onResponseBytes?.(responseBytes); responseBytes = 0;
      current.resolve(reply);
    });
  };
  return {
    invoke(arguments_, deliveryPolicy) {
      if (closed || stopped || closing) return Promise.reject(failure(stopped ?? 'CLOSED'));
      if (pending) return Promise.reject(failure('CONCURRENT_REQUEST'));
      try { if (!child) launch(); } catch (error) { return Promise.reject(error); }
      return new Promise((resolve, reject) => {
        pending = { resolve, reject, policy: deliveryPolicy };
        child.stdin.write(JSON.stringify(arguments_) + '\n');
      });
    },
    async close() {
      closing = true;
      if (!child) return;
      if (!closed) {
        if (pending || stopped || signal?.aborted) stop(stopped ?? 'CANCELLED');
        else child.stdin.end();
        // Child cleanup has a finite ownership bound inside the original session deadline.
        const reap = setTimeout(() => stop('CLOSE_TIMEOUT'), Math.min(1000, policy.callTimeoutMillis));
        try { await exit; } finally { clearTimeout(reap); cleanup(); }
      }
    },
  };
}
