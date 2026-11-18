import assert from 'node:assert/strict';
import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { completeDeclarationPath, declarationPath, examineWorlds, gradeImportedAnswer, participantPrompt, prepareWorlds } from './imported-model.mjs';

test('both worlds present identical source and different independent selections', async t => {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'kast-imported-model-control-')));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const root = join(directory, 'worlds');
  await prepareWorlds(root);
  const { cases, sourceSha256 } = await examineWorlds(root);
  assert.match(sourceSha256, /^[a-f0-9]{64}$/);
  const a = cases['adapter-a'];
  const b = cases['adapter-b'];
  assert.equal(participantPrompt(a.snapshot), participantPrompt(b.snapshot));
  assert.notEqual(participantPrompt(a.snapshot, a.selection), participantPrompt(b.snapshot, b.selection));

  await writeFile(join(root, 'adapter-b', 'selection.txt'), 'adapter-a\n');
  await assert.rejects(examineWorlds(root), /IMPORTED_MODEL_SELECTION_MISMATCH/);
});

test('an exact location needs a complete single-row result and the independent path', () => {
  const workspace = '/fixture';
  const path = declarationPath('adapter-b');
  const reply = {
    type: 'complete', document: {
      status: 'complete', coverage: { exhaustive: true }, original_evidence_summary: { failure_count: 0, omission_count: 0 },
      items: [{ name: 'formatInvoiceReference', location: { file: `${workspace}/${path}` } }],
    },
  };
  assert.equal(completeDeclarationPath(reply, workspace), path);
  for (const changed of [{ coverage: { exhaustive: false } }, { original_evidence_summary: { failure_count: 1, omission_count: 0 } },
    { items: [...reply.document.items, ...reply.document.items] }]) {
    assert.equal(completeDeclarationPath({ ...reply, document: { ...reply.document, ...changed } }, workspace), null);
  }
  assert.equal(completeDeclarationPath(reply, '/other-fixture'), null);
  assert.equal(completeDeclarationPath({ type: 'complete' }, workspace), null);

  assert.equal(gradeImportedAnswer(path, 'adapter-b').pass, true);
  assert.equal(gradeImportedAnswer(path, 'adapter-a').reason, 'WRONG_DECLARATION');
  assert.equal(gradeImportedAnswer('UNKNOWN', 'adapter-a').reason, 'ABSTAINED');
});
