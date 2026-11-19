import assert from 'node:assert/strict';
import test from 'node:test';
import { participantPrompt, gradeName, worlds } from './retained-state.mjs';

test('identical source and question require different answers in the two retained states', () => {
  const fixture = 'fun alphaSink() = "alpha"\nfun betaSink() = "beta"';
  const [a, b] = worlds;
  assert.notEqual(a, b);
  assert.equal(participantPrompt('source', fixture, a), participantPrompt('source', fixture, b));
  assert.equal(participantPrompt('source', fixture, a), participantPrompt('kast', fixture, b));
  assert.notEqual(
    participantPrompt('materialized', fixture, a),
    participantPrompt('materialized', fixture, b),
  );
  assert.equal(gradeName(a, a).pass, true);
  assert.equal(gradeName(b, a).pass, false);
  assert.equal(gradeName('UNKNOWN', b).pass, false);
});

test('a lucky correct name earns answer credit but cannot establish result access', () => {
  assert.equal(gradeName('alphaSink', 'alphaSink', []).namedScores?.supportedByKast, 0);
  const guide = { tool: 'read_kast_guidance', path: 'skills/kast/SKILL.md' };
  const read = { tool: 'read_previous_result', complete: true, name: 'alphaSink' };
  const qualified = gradeName('alphaSink', 'alphaSink', [guide, read]);
  assert.equal(qualified.pass, true);
  assert.equal(qualified.namedScores?.supportedByKast, 1);
  assert.equal(gradeName('alphaSink', 'alphaSink', [guide]).namedScores?.supportedByKast, 0);
  assert.equal(gradeName('betaSink', 'betaSink', [read]).namedScores?.supportedByKast, 0);
});
