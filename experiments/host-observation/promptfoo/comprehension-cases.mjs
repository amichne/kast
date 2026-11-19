import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const root = new URL('./comprehension/', import.meta.url);
export const comprehensionManifest = Object.freeze(JSON.parse(readFileSync(new URL('manifest.json', root), 'utf8')));
export const comprehensionCaseIds = Object.freeze(comprehensionManifest.cases.map(item => item.id));
// Both configurations receive this text. It must not reveal that another
// configuration exists or that the answer is measured.
export const comprehensionSystem = 'You are a Kotlin developer interpreting supplied evidence. Follow the exact response format requested. Answering only from the supplied evidence takes precedence over general workflow guidance.';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

// A frozen input keeps curator provenance next to the evidence. Only the
// manifest-declared fields reach the participant; provenance, owner paths,
// archived run identifiers and model names stay with the curator record.
export function participantPacket(packet, fields) {
  if (!packet || typeof packet !== 'object' || Array.isArray(packet) || !Array.isArray(fields) || !fields.length) {
    throw Error('COMPREHENSION_PROJECTION_REJECTED');
  }
  const projected = {};
  for (const field of fields) {
    const { key, from, omit = [] } = field ?? {};
    if (typeof key !== 'string' || !key || typeof from !== 'string' || !Object.hasOwn(packet, from) ||
        Object.hasOwn(projected, key) || !Array.isArray(omit) || Object.keys(field).some(name => !['key', 'from', 'omit'].includes(name))) {
      throw Error('COMPREHENSION_PROJECTION_REJECTED');
    }
    const value = packet[from];
    if (omit.length) {
      if (!value || typeof value !== 'object' || Array.isArray(value) || omit.some(name => !Object.hasOwn(value, name))) {
        throw Error('COMPREHENSION_PROJECTION_REJECTED');
      }
      projected[key] = Object.fromEntries(Object.entries(value).filter(([name]) => !omit.includes(name)));
    } else {
      projected[key] = value;
    }
  }
  return JSON.stringify(projected, null, 2) + '\n';
}

// This owner reads only participant inputs. Private answer files stay with the grader.
export function comprehensionCases(caseIds = comprehensionCaseIds) {
  if (!Array.isArray(caseIds) || !caseIds.length || new Set(caseIds).size !== caseIds.length ||
      caseIds.some(id => !comprehensionCaseIds.includes(id))) throw Error('COMPREHENSION_CASES_REJECTED');
  return caseIds.map(id => {
    const metadata = comprehensionManifest.cases.find(item => item.id === id);
    const input = readFileSync(new URL(`inputs/${id}.json`, root), 'utf8');
    if (sha256(input) !== metadata.inputSha256) throw Error('COMPREHENSION_INPUT_IDENTITY_CHANGED');
    const prompt = participantPacket(JSON.parse(input), metadata.participantFields);
    if (sha256(prompt) !== metadata.participantSha256) throw Error('COMPREHENSION_PARTICIPANT_IDENTITY_CHANGED');
    return Object.freeze({ ...metadata, packet: Object.freeze(JSON.parse(input)), prompt });
  });
}
