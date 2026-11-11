import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {realpathSync} from 'node:fs';
import {readFile} from 'node:fs/promises';
import {isAbsolute, join} from 'node:path';
import {ClaimsStore} from 'openwiki/dist/claims/brains/code/store.js';
import {OpenWikiIgnore} from 'openwiki/dist/agent/openwiki-ignore.js';
import {RepositoryEvidenceResolver} from 'openwiki/dist/claims/evidence/repository/resolver.js';
import {runClaimsPreflight} from 'openwiki/dist/claims/brains/code/preflight.js';
import type {EvidenceResolver} from 'openwiki/dist/claims/core/types.js';

// All production imports from OpenWiki's implementation reside in this module.
// The package lock and this adapter qualify that implementation-level interface.
export type Scope = 'public' | 'canonical';
export type ClaimStatus = 'current' | 'stale' | 'unresolved';
export type AssessmentStage = 'input' | 'identity' | 'inventory' | 'native-preflight' | 'snapshot';
export type Failure = Readonly<{
  status: 'failed'; scope: Scope; stage: AssessmentStage;
  error: Readonly<{name: string; message: string}>;
}>;
export type PageAssessment = Readonly<{
  page: string; pageVersion: string; sidecarDigest: string | null;
  recordedPageVersion: string | null;
  digestAgreement: 'matched' | 'mismatched' | 'missing';
  claims: 'present' | 'empty' | 'missing';
  verification: Readonly<{by: string; at: string}> | null;
}>;
export type ClaimAssessment = Readonly<{
  page: string; id: string; statement: string; statementDigest: string;
  status: ClaimStatus; resources: readonly string[];
  evidence: readonly Readonly<{resource: string; version: string}>[];
}>;
export type CompletedAssessment = Readonly<{
  status: 'completed'; scope: Scope; sourceRevision: string;
  producer: Readonly<{adapter: 'kast-native-claims-v1'; openwiki: '0.9.0'; lockDigest: string}>;
  inputsDigest: string;
  inputs: readonly Readonly<{resource: string; digest: string}>[];
  pages: readonly PageAssessment[]; claims: readonly ClaimAssessment[];
  orphanSidecars: readonly string[];
  notAssessed: readonly Readonly<{page: string; reason: 'outside-public-scope'}>[];
  summary: Readonly<Record<ClaimStatus, number>>;
}>;
export type Assessment = CompletedAssessment | Failure;
export type PageAgreement = Readonly<{
  status: 'completed'; scope: Scope; sourceRevision: string;
  pages: readonly PageAssessment[]; orphanSidecars: readonly string[];
}> | Failure;
export type Options = Readonly<{root: string; scope: Scope}>;

function digest(value: string | Uint8Array): string {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`;
}
function failure(scope: Scope, stage: AssessmentStage, error: unknown): Failure {
  return {status: 'failed', scope, stage, error: {
    name: error instanceof Error ? error.name : 'UnknownError',
    message: (error instanceof Error ? error.message : 'Non-Error native failure').slice(0, 1024),
  }};
}
function sourceRevision(root: string): string {
  const top = execFileSync('git', ['-C', root, 'rev-parse', '--show-toplevel'], {encoding: 'utf8'}).trim();
  if (realpathSync(top) !== realpathSync(root)) throw new Error('The assessment root must be the absolute Git top-level.');
  return execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], {encoding: 'utf8'}).trim();
}
async function selectedPages(root: string, scope: Scope, all: readonly string[]): Promise<readonly string[]> {
  if (scope === 'canonical') return all;
  const raw: unknown = JSON.parse(await readFile(join(root, 'docs/okf-public.json'), 'utf8'));
  if (typeof raw !== 'object' || raw === null || !('views' in raw) || !Array.isArray(raw.views)) {
    throw new Error('The public manifest has no views.');
  }
  const pages = new Set<string>();
  for (const view of raw.views as unknown[]) {
    if (typeof view !== 'object' || view === null || !('concepts' in view) || !Array.isArray(view.concepts)) {
      throw new Error('A public view has no concepts.');
    }
    for (const page of view.concepts as unknown[]) {
      if (typeof page !== 'string' || !all.includes(`/${page}`)) throw new Error('A public concept is not a native canonical page.');
      pages.add(`/${page}`);
    }
  }
  if ('associations' in raw) {
    if (!Array.isArray(raw.associations)) throw new Error('The public associations must be an array.');
    for (const association of raw.associations as unknown[]) {
      if (typeof association !== 'object' || association === null || !('claims' in association) || !Array.isArray(association.claims)) {
        throw new Error('A public association has no canonical Claim references.');
      }
      for (const claim of association.claims as unknown[]) {
        if (typeof claim !== 'object' || claim === null || !('page' in claim) || typeof claim.page !== 'string'
          || !all.includes(`/${claim.page}`)) throw new Error('An associated Claim owner is not a native canonical page.');
        pages.add(`/${claim.page}`);
      }
    }
  }
  if (pages.size === 0) throw new Error('The public scope is empty.');
  return [...pages].sort();
}
class ScopedClaimsStore extends ClaimsStore {
  readonly #pages: readonly string[];
  constructor(root: string, pages: readonly string[]) { super(root); this.#pages = pages; }
  override async discoverPages(): Promise<string[]> { return [...this.#pages]; }
}
async function inventory(root: string, scope: Scope) {
  const store = new ClaimsStore(root);
  const all = await store.discoverPages();
  if (all.length === 0) throw new Error('The canonical corpus contains no factual pages.');
  const selected = await selectedPages(root, scope, all);
  const sidecars = await store.discoverSidecarPages();
  const orphanSidecars = sidecars.filter(page => !all.includes(page));
  const pages: PageAssessment[] = [];
  const inputs: {resource: string; digest: string}[] = [{resource: 'openwiki:native-inventory', digest: digest(JSON.stringify({all, sidecars}))}];
  for (const page of orphanSidecars) {
    await store.loadPage(page);
    const path = `openwiki/.claims/${page.slice('/openwiki/'.length).replace(/\.md$/u, '.json')}`;
    inputs.push({resource: path, digest: digest(await readFile(join(root, path)))});
  }
  for (const page of selected) {
    const sidecar = await store.loadPage(page);
    const pageVersion = await store.hashPage(page);
    const sidecarPath = `openwiki/.claims/${page.slice('/openwiki/'.length).replace(/\.md$/u, '.json')}`;
    const sidecarDigest = sidecar === null ? null : digest(await readFile(join(root, sidecarPath)));
    inputs.push({resource: page.slice(1), digest: pageVersion});
    inputs.push({resource: sidecarPath, digest: sidecarDigest ?? 'missing'});
    pages.push({page, pageVersion, sidecarDigest, recordedPageVersion: sidecar?.pageVersion ?? null,
      digestAgreement: sidecar === null ? 'missing' : sidecar.pageVersion === pageVersion ? 'matched' : 'mismatched',
      claims: sidecar === null ? 'missing' : sidecar.claims.length === 0 ? 'empty' : 'present',
      verification: sidecar?.verification ?? null});
  }
  return {store: new ScopedClaimsStore(root, selected), all, selected, orphanSidecars, pages, inputs};
}
export async function examinePageAgreement(options: Options): Promise<PageAgreement> {
  let stage: AssessmentStage = 'input';
  try {
    if (!isAbsolute(options.root)) throw new Error('The repository root must be absolute.');
    stage = 'identity';
    const revision = sourceRevision(options.root);
    stage = 'inventory';
    const result = await inventory(options.root, options.scope);
    return {status: 'completed', scope: options.scope, sourceRevision: revision,
      pages: result.pages, orphanSidecars: result.orphanSidecars};
  } catch (error) { return failure(options.scope, stage, error); }
}
export function pageAgreementAccepted(result: PageAgreement): boolean {
  return result.status === 'completed' && result.pages.length > 0 && result.orphanSidecars.length === 0
    && result.pages.every(page => page.digestAgreement === 'matched' && page.claims === 'present');
}
export function assessmentAccepted(result: Assessment): boolean {
  return result.status === 'completed' && pageAgreementAccepted(result)
    && result.claims.length > 0 && result.claims.every(claim => claim.status === 'current');
}
export async function assessEvidence(options: Options): Promise<Assessment> {
  let stage: AssessmentStage = 'input';
  try {
    if (!isAbsolute(options.root)) throw new Error('The repository root must be absolute.');
    stage = 'identity';
    const revision = sourceRevision(options.root);
    const lockDigest = digest(await readFile(new URL('./package-lock.json', import.meta.url)));
    const packageIdentity: unknown = JSON.parse(await readFile(new URL('./node_modules/openwiki/package.json', import.meta.url), 'utf8'));
    if (typeof packageIdentity !== 'object' || packageIdentity === null || !('version' in packageIdentity) || packageIdentity.version !== '0.9.0') {
      throw new Error('The native OpenWiki producer differs from the qualified 0.9.0 package.');
    }
    stage = 'inventory';
    const result = await inventory(options.root, options.scope);
    const inputs = [...result.inputs];
    const adapterDigest = digest(await readFile(new URL('./evidence-native.ts', import.meta.url)));
    const manifestDigest = options.scope === 'public' ? digest(await readFile(join(options.root, 'docs/okf-public.json'))) : null;
    inputs.push({resource: 'docs/tooling/markdown/package-lock.json', digest: lockDigest});
    inputs.push({resource: 'docs/tooling/markdown/evidence-native.ts', digest: adapterDigest});
    if (manifestDigest !== null) inputs.push({resource: 'docs/okf-public.json', digest: manifestDigest});
    const ignore = await OpenWikiIgnore.load(options.root);
    inputs.push({resource: '.openwikiignore:effective-patterns', digest: digest(JSON.stringify(ignore.patterns))});
    const native = new RepositoryEvidenceResolver({rootDir: options.root, openWikiIgnore: ignore});
    const resolutions: {resource: string; previousVersion: string | undefined; digest: string}[] = [];
    function resolutionDigest(value: Awaited<ReturnType<EvidenceResolver['resolve']>>): string {
      return value === null ? 'unresolved' : digest(JSON.stringify({evidence: value.evidence, content: value.content}));
    }
    const resolver: EvidenceResolver = {async resolve(resource, previousVersion) {
      try {
        const value = await native.resolve(resource, previousVersion);
        const currentDigest = resolutionDigest(value);
        inputs.push({resource: JSON.stringify({resource, previousVersion}), digest: currentDigest});
        resolutions.push({resource, previousVersion, digest: currentDigest});
        return value;
      } catch (error) {
        inputs.push({resource: JSON.stringify({resource, previousVersion}),
          digest: `native-refusal:${error instanceof Error ? error.name : 'UnknownError'}`});
        throw error;
      }
    }};
    stage = 'native-preflight';
    const preflight = await runClaimsPreflight(result.store, resolver);
    const claims: ClaimAssessment[] = [];
    for (const [page, sidecar] of preflight.persisted) {
      for (const claim of sidecar.claims) {
        const issue = preflight.issues.find(item => item.page === page && item.claimId === claim.id);
        claims.push({page, id: claim.id, statement: claim.statement, statementDigest: digest(claim.statement),
          status: issue?.kind ?? 'current', resources: issue?.resources ?? [], evidence: claim.evidence});
      }
    }
    stage = 'snapshot';
    for (const resolution of resolutions) {
      const current = await native.resolve(resolution.resource, resolution.previousVersion);
      if (resolutionDigest(current) !== resolution.digest) throw new Error('Evidence inputs changed during assessment.');
    }
    const after = await inventory(options.root, options.scope);
    const currentIgnore = await OpenWikiIgnore.load(options.root);
    if (sourceRevision(options.root) !== revision || JSON.stringify(after.inputs) !== JSON.stringify(result.inputs)
      || JSON.stringify(currentIgnore.patterns) !== JSON.stringify(ignore.patterns)
      || digest(await readFile(new URL('./package-lock.json', import.meta.url))) !== lockDigest
      || digest(await readFile(new URL('./evidence-native.ts', import.meta.url))) !== adapterDigest
      || (manifestDigest !== null && digest(await readFile(join(options.root, 'docs/okf-public.json'))) !== manifestDigest)) {
      throw new Error('Canonical inputs changed during assessment.');
    }
    inputs.sort((a, b) => a.resource.localeCompare(b.resource) || a.digest.localeCompare(b.digest));
    return {status: 'completed', scope: options.scope, sourceRevision: revision,
      producer: {adapter: 'kast-native-claims-v1', openwiki: '0.9.0', lockDigest},
      inputsDigest: digest(JSON.stringify(inputs)), inputs, pages: result.pages, claims,
      orphanSidecars: result.orphanSidecars,
      notAssessed: result.all.filter(page => !result.selected.includes(page)).map(page => ({page, reason: 'outside-public-scope'})),
      summary: {current: claims.filter(c => c.status === 'current').length,
        stale: claims.filter(c => c.status === 'stale').length,
        unresolved: claims.filter(c => c.status === 'unresolved').length}};
  } catch (error) { return failure(options.scope, stage, error); }
}
