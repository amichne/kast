import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtemp, mkdir, readFile, rm, symlink, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {RepositoryEvidenceResolver} from 'openwiki/dist/claims/evidence/repository/resolver.js';
import {assessEvidence, assessmentAccepted, examinePageAgreement, pageAgreementAccepted} from './evidence-native.ts';

const page = '/openwiki/contracts/example.md';
const text = '---\ntype: Contract\ntitle: Example\n---\n\n# Example\n\nThe source supplies the value.\n';
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
type Evidence = Readonly<{resource: string; version: string}>;

async function fixture(run: (root: string) => Promise<void>): Promise<void> {
  const root = await mkdtemp(join(tmpdir(), 'kast-native-evidence-'));
  try {
    await mkdir(join(root, 'openwiki/contracts'), {recursive: true});
    await mkdir(join(root, 'openwiki/.claims/contracts'), {recursive: true});
    await mkdir(join(root, 'docs'), {recursive: true});
    await writeFile(join(root, page.slice(1)), text);
    await writeFile(join(root, 'source.kt'), 'val answer = 42\n');
    await writeFile(join(root, 'docs/okf-public.json'), JSON.stringify({views: [{concepts: [page.slice(1)]}]}));
    await sidecar(root, [{resource: 'repo://source.kt', version: `repo-file-v1:sha256:${hash('val answer = 42\n')}`}]);
    execFileSync('git', ['init', '-q', root]);
    execFileSync('git', ['-C', root, 'add', '.']);
    execFileSync('git', ['-C', root, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture']);
    await run(root);
  } finally { await rm(root, {recursive: true, force: true}); }
}
async function sidecar(root: string, evidence: readonly Evidence[], empty = false): Promise<void> {
  await writeFile(join(root, 'openwiki/.claims/contracts/example.json'), JSON.stringify({
    schemaVersion: 1, pageVersion: `sha256:${hash(text)}`,
    claims: empty ? [] : [{id: 'source-value', statement: 'The source supplies the value.', evidence}],
    verification: {by: 'fixture@1', at: '2026-01-01T00:00:00Z'},
  }));
}

test('unchanged evidence is current and assessment preserves files and verification', async () => fixture(async root => {
  const before = await readFile(join(root, 'openwiki/.claims/contracts/example.json'), 'utf8');
  const first = await assessEvidence({root, scope: 'public'});
  const second = await assessEvidence({root, scope: 'public'});
  assert.equal(first.status, 'completed');
  if (first.status !== 'completed' || second.status !== 'completed') assert.fail('The native assessment must complete.');
  assert.deepEqual(first.summary, {current: 1, stale: 0, unresolved: 0});
  assert.equal(assessmentAccepted(first), true);
  assert.equal(first.inputsDigest, second.inputsDigest);
  assert.deepEqual(first.pages[0]?.verification, {by: 'fixture@1', at: '2026-01-01T00:00:00Z'});
  assert.equal(await readFile(join(root, 'openwiki/.claims/contracts/example.json'), 'utf8'), before);
  assert.equal(await readFile(join(root, page.slice(1)), 'utf8'), text);
  assert.equal(execFileSync('git', ['-C', root, 'status', '--porcelain'], {encoding: 'utf8'}), '');
}));

test('changed whole-file evidence is stale and binds different candidate bytes', async () => fixture(async root => {
  const before = await assessEvidence({root, scope: 'canonical'});
  await writeFile(join(root, 'source.kt'), 'val answer = 43\n');
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed' || before.status !== 'completed') assert.fail();
  assert.deepEqual(result.summary, {current: 0, stale: 1, unresolved: 0});
  assert.notEqual(result.inputsDigest, before.inputsDigest);
  assert.equal(assessmentAccepted(result), false);
}));

test('native uniquely relocated range remains current', async () => fixture(async root => {
  const original = 'one\ntwo\nthree\nvalue\nfive\nsix\nseven\n';
  await writeFile(join(root, 'source.kt'), original);
  // Native opaque tokens are setup data. Expected content and current status remain independent assertions.
  const native = new RepositoryEvidenceResolver({rootDir: root});
  const evidence = await native.resolve('repo://source.kt#L4-L4');
  assert.ok(evidence);
  assert.equal(evidence.content, 'value\n');
  await sidecar(root, [evidence.evidence]);
  await writeFile(join(root, 'source.kt'), 'inserted\n' + original);
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.deepEqual(result.summary, {current: 1, stale: 0, unresolved: 0});
}));

test('ambiguous relocated range is unresolved', async () => fixture(async root => {
  const block = 'one\ntwo\nthree\nvalue\nfive\nsix\nseven\n';
  await writeFile(join(root, 'source.kt'), block);
  const native = new RepositoryEvidenceResolver({rootDir: root});
  const evidence = await native.resolve('repo://source.kt#L4-L4');
  assert.ok(evidence);
  await sidecar(root, [evidence.evidence]);
  await writeFile(join(root, 'source.kt'), 'inserted\n' + block + block);
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.deepEqual(result.summary, {current: 0, stale: 0, unresolved: 1});
}));

test('unresolved evidence takes precedence over stale within one claim', async () => fixture(async root => {
  await sidecar(root, [
    {resource: 'repo://source.kt', version: `repo-file-v1:sha256:${hash('old\n')}`},
    {resource: 'repo://missing.kt', version: 'recorded-missing-version'},
  ]);
  const result = await assessEvidence({root, scope: 'public'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.deepEqual(result.summary, {current: 0, stale: 0, unresolved: 1});
  assert.deepEqual(result.claims[0]?.resources, ['repo://missing.kt']);
}));

test('native containment refusal stays unresolved', async () => fixture(async root => {
  await symlink(join(root, 'source.kt'), join(root, 'alias.kt'));
  await sidecar(root, [{resource: 'repo://alias.kt', version: 'prior-version'}]);
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.deepEqual(result.summary, {current: 0, stale: 0, unresolved: 1});
}));

for (const resource of ['repo://../outside.kt', 'repo://.git/config', 'repo://source.kt#nonsense']) {
  test(`native invalid resource rejects assessment: ${resource}`, async () => fixture(async root => {
    await sidecar(root, [{resource, version: 'prior-version'}]);
    const result = await assessEvidence({root, scope: 'canonical'});
    assert.equal(result.status, 'failed');
    if (result.status !== 'failed') assert.fail();
    assert.equal(result.stage, 'native-preflight');
    assert.equal(assessmentAccepted(result), false);
  }));
}

test('native ignore refusal remains failed instead of an empty completed assessment', async () => fixture(async root => {
  await writeFile(join(root, '.openwikiignore'), 'source.kt\n');
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'failed');
  if (result.status !== 'failed') assert.fail();
  assert.equal(result.stage, 'native-preflight');
  assert.match(result.error.message, /excluded by .openwikiignore/u);
}));

test('operational read error fails assessment without zero-finding success', async () => fixture(async root => {
  await mkdir(join(root, '.openwikiignore'));
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'failed');
  assert.equal(assessmentAccepted(result), false);
}));

test('missing claims stay explicit and reject admission', async () => fixture(async root => {
  await rm(join(root, 'openwiki/.claims/contracts/example.json'));
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.equal(result.pages[0]?.claims, 'missing');
  assert.equal(assessmentAccepted(result), false);
}));

test('empty claims stay explicit and reject admission', async () => fixture(async root => {
  await sidecar(root, [], true);
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.equal(result.pages[0]?.claims, 'empty');
  assert.equal(assessmentAccepted(result), false);
}));

test('orphan sidecars stay explicit', async () => fixture(async root => {
  await writeFile(join(root, 'openwiki/.claims/contracts/orphan.json'), await readFile(join(root, 'openwiki/.claims/contracts/example.json')));
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.deepEqual(result.orphanSidecars, ['/openwiki/contracts/orphan.md']);
  assert.equal(assessmentAccepted(result), false);
}));

test('canonical prose mismatch rejects pre-update admission without a digest refresh', async () => fixture(async root => {
  const before = await readFile(join(root, 'openwiki/.claims/contracts/example.json'), 'utf8');
  assert.equal(pageAgreementAccepted(await examinePageAgreement({root, scope: 'canonical'})), true);
  await writeFile(join(root, page.slice(1)), text + '\nUnreconciled claim.\n');
  const result = await examinePageAgreement({root, scope: 'canonical'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.equal(result.pages[0]?.digestAgreement, 'mismatched');
  assert.equal(pageAgreementAccepted(result), false);
  assert.equal(await readFile(join(root, 'openwiki/.claims/contracts/example.json'), 'utf8'), before);
}));

test('public scope identifies the unassessed canonical corpus', async () => fixture(async root => {
  await writeFile(join(root, 'openwiki/contracts/other.md'), text);
  const publicResult = await assessEvidence({root, scope: 'public'});
  const canonicalResult = await assessEvidence({root, scope: 'canonical'});
  assert.equal(publicResult.status, 'completed');
  assert.equal(canonicalResult.status, 'completed');
  if (publicResult.status !== 'completed' || canonicalResult.status !== 'completed') assert.fail();
  assert.deepEqual(publicResult.notAssessed, [{page: '/openwiki/contracts/other.md', reason: 'outside-public-scope'}]);
  assert.equal(assessmentAccepted(publicResult), true);
  assert.equal(assessmentAccepted(canonicalResult), false);
}));

test('malformed native sidecar fails assessment instead of a missing-claims result', async () => fixture(async root => {
  await writeFile(join(root, 'openwiki/.claims/contracts/example.json'), '{"schemaVersion":1,"claims":[]}');
  const result = await assessEvidence({root, scope: 'canonical'});
  assert.equal(result.status, 'failed');
  if (result.status !== 'failed') assert.fail();
  assert.equal(result.stage, 'inventory');
}));

test('assessment rejects a relative repository root', async () => {
  const result = await assessEvidence({root: '.', scope: 'canonical'});
  assert.equal(result.status, 'failed');
  if (result.status !== 'failed') assert.fail();
  assert.equal(result.stage, 'input');
});

test('public scope includes canonical Claim owners of authored consumers', async () => fixture(async root => {
  await writeFile(join(root, 'openwiki/contracts/other.md'), text);
  await writeFile(join(root, 'openwiki/.claims/contracts/other.json'), await readFile(join(root, 'openwiki/.claims/contracts/example.json')));
  await writeFile(join(root, 'docs/okf-public.json'), JSON.stringify({
    views: [{concepts: [page.slice(1)]}],
    associations: [{route: 'authored', claims: [{page: 'openwiki/contracts/other.md', claimId: 'source-value'}]}],
  }));
  const result = await assessEvidence({root, scope: 'public'});
  assert.equal(result.status, 'completed');
  if (result.status !== 'completed') assert.fail();
  assert.deepEqual(result.notAssessed, []);
  assert.equal(result.pages.length, 2);
  assert.deepEqual(result.summary, {current: 2, stale: 0, unresolved: 0});
}));

test('unknown associated canonical owner rejects scope assessment', async () => fixture(async root => {
  await writeFile(join(root, 'docs/okf-public.json'), JSON.stringify({
    views: [{concepts: [page.slice(1)]}],
    associations: [{route: 'authored', claims: [{page: 'openwiki/contracts/missing.md', claimId: 'missing'}]}],
  }));
  const result = await assessEvidence({root, scope: 'public'});
  assert.equal(result.status, 'failed');
  if (result.status !== 'failed') assert.fail();
  assert.equal(result.stage, 'inventory');
}));

test('guarded native update admits a clean no-op without model work or semantic restamping', async () => fixture(async root => {
  const {ensureCodeModeRepoSetup} = await import('openwiki/dist/ingestion/code-mode.js');
  const {repairOkfFrontmatter} = await import('openwiki/dist/okf/frontmatter.js');
  const {createRepositorySourceSnapshot, writeLastUpdateMetadata} = await import('openwiki/dist/agent/utils.js');
  const {OpenWikiIgnore} = await import('openwiki/dist/agent/openwiki-ignore.js');
  const {ClaimsStore} = await import('openwiki/dist/claims/brains/code/store.js');
  const {replaceRepositoryPageManifest} = await import('openwiki/dist/generation/page-manifest.js');
  const {beginRepositoryRun} = await import('openwiki/dist/generation/repository-run.js');
  await ensureCodeModeRepoSetup(root);
  const canonical = repairOkfFrontmatter('---\ntype: Contract\ntitle: Example\nverified:\n  - by: openwiki/0.6.1\n    at: 2026-01-01T00:00:00Z\n---\n\n# Example\n\nThe source supplies the value.\n', page).content;
  await writeFile(join(root, page.slice(1)), canonical);
  await new ClaimsStore(root).writePage(page, {
    schemaVersion: 1, pageVersion: `sha256:${hash(canonical)}`,
    claims: [{id: 'source-value', statement: 'The source supplies the value.', evidence: [{resource: 'repo://source.kt', version: `repo-file-v1:sha256:${hash('val answer = 42\n')}`}]}],
    verification: {by: 'openwiki/0.6.1', at: '2026-01-01T00:00:00Z'},
  });
  execFileSync('git', ['-C', root, 'add', '.']);
  execFileSync('git', ['-C', root, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'canonical fixture']);
  const source = await createRepositorySourceSnapshot(root, await OpenWikiIgnore.load(root));
  const gitHead = source.gitHead;
  assert.ok(gitHead);
  await replaceRepositoryPageManifest(root, [page], {gitHead, sourceFingerprint: source.fingerprint});
  await writeLastUpdateMetadata('update', root, 'fixture/no-model', 'repository', 'complete', 'en');
  execFileSync('git', ['-C', root, 'add', '.']);
  execFileSync('git', ['-C', root, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'completed coverage']);
  const sidecarBefore = await readFile(join(root, 'openwiki/.claims/contracts/example.json'), 'utf8');
  const agentsBefore = await readFile(join(root, 'AGENTS.md'), 'utf8');
  assert.equal(pageAgreementAccepted(await examinePageAgreement({root, scope: 'canonical'})), true);
  const result = await beginRepositoryRun({root, mode: 'update', actor: {producerActor: 'fixture/no-model', metadataModel: 'fixture/no-model'}});
  assert.equal(result.view.status, 'noop');
  assert.equal(await readFile(join(root, page.slice(1)), 'utf8'), canonical);
  assert.equal(await readFile(join(root, 'openwiki/.claims/contracts/example.json'), 'utf8'), sidecarBefore);
  assert.equal(await readFile(join(root, 'AGENTS.md'), 'utf8'), agentsBefore);
  assert.equal(await readFile(join(root, 'source.kt'), 'utf8'), 'val answer = 42\n');
  const changed = execFileSync('git', ['-C', root, 'diff', '--name-only'], {encoding: 'utf8'}).trim().split('\n').filter(Boolean);
  assert.ok(changed.every(path => path === 'openwiki/.last-update.json' || path === 'openwiki/.page-manifest.json'));
  await writeFile(join(root, page.slice(1)), canonical + '\nAn unreconciled assertion.\n');
  assert.equal(pageAgreementAccepted(await examinePageAgreement({root, scope: 'canonical'})), false);
  assert.equal(await readFile(join(root, 'openwiki/.claims/contracts/example.json'), 'utf8'), sidecarBefore);
}));
