import { fileURLToPath } from 'node:url';
import { coroutineDelayCase } from './coroutine-delay-case.mjs';
import { picnicAnsiCase } from './picnic-ansi-case.mjs';

const fixture = fileURLToPath(new URL('./developer-tasks/parcel-service', import.meta.url));
const sources = [
  'analytics/AuditSink.kt',
  'api/CheckoutEndpoint.kt',
  'api/DispatchEndpoint.kt',
  'audit/AuditSink.kt',
  'carriers/Carriers.kt',
  'checkout/CheckoutModels.kt',
  'checkout/Pricing.kt',
  'dispatch/Dispatch.kt',
  'operations/Booking.kt',
  'operations/Cancellation.kt',
  'replay/ImportReplay.kt',
].map(path => 'src/main/kotlin/parcel/' + path);
const kotlinMain = name => ({
  main: fileURLToPath(new URL('./developer-tasks/oracles/' + name + '.kt', import.meta.url)),
  mainClass: 'oracle.' + name + 'Kt',
  candidateSources: sources,
  expectedStdout: 'PASS',
});
const warmup = { name: 'DispatchQueue', kind: 'class' };

export const developerCases = [
  {
    id: 'locker-pickup',
    kind: 'edit',
    fixture,
    warmup,
    prompt: [
      'Add locker pickup to the parcel checkout flow.',
      'Keep the current checkout request compatible. Append two optional fields named lockerId and lockerZone, with types String? and DeliveryZone?, both defaulting to null.',
      'For mode "locker", return MISSING_LOCKER if the identifier is absent or blank or the locker zone is absent.',
      'Add DeliveryOption.Locker(lockerId: String, zone: DeliveryZone). Route a valid request through Pricing.quote.',
      'Pricing must reject WRONG_ZONE before checking weight if the locker zone differs from the destination zone.',
      'Reject LOCKER_WEIGHT_LIMIT if the total basket weight exceeds 12,000 grams. Include every line quantity.',
      'At or below the limit, return a quote of 299 cents and handoff "locker:<lockerId>". Preserve the existing home and unsupported mode behavior.',
      'Make the changes in the checkout domain and endpoint. Leave the rest of the service unchanged.',
    ].join('\n'),
    allowedChangedPaths: [
      'src/main/kotlin/parcel/api/CheckoutEndpoint.kt',
      'src/main/kotlin/parcel/checkout/CheckoutModels.kt',
      'src/main/kotlin/parcel/checkout/Pricing.kt',
    ],
    allowedNewSourceDirs: ['src/main/kotlin/parcel/api', 'src/main/kotlin/parcel/checkout'],
    oracle: kotlinMain('LockerPickup'),
  },
  {
    id: 'audit-v2',
    kind: 'edit',
    fixture,
    warmup,
    prompt: [
      'Migrate the parcel lifecycle audit API to V2.',
      'In parcel.audit, introduce data class AuditEvent(val kind: AuditKind, val parcelId: ParcelId, val actorId: ActorId).',
      'Replace parcel.audit.AuditSink.record(kind: String, parcelId: String, actorId: String) with record(event: AuditEvent). Remove the old signature.',
      'Migrate each parcel audit caller. Pass the typed identifiers through the new event and keep the existing audit kinds and effect order.',
      'Keep JournalAuditSink.snapshot() compatible, including its string format. The unrelated parcel.analytics.AuditSink and its consumers must remain unchanged.',
    ].join('\n'),
    allowedChangedPaths: [
      'src/main/kotlin/parcel/audit/AuditSink.kt',
      'src/main/kotlin/parcel/operations/Booking.kt',
      'src/main/kotlin/parcel/operations/Cancellation.kt',
      'src/main/kotlin/parcel/replay/ImportReplay.kt',
    ],
    allowedNewSourceDirs: [
      'src/main/kotlin/parcel/audit',
      'src/main/kotlin/parcel/operations',
      'src/main/kotlin/parcel/replay',
    ],
    oracle: kotlinMain('AuditV2'),
  },
  {
    id: 'dispatch-trace',
    kind: 'trace',
    fixture,
    warmup,
    prompt: [
      'Trace this call twice against the same fresh dispatcher built by createDispatcher. Use DispatchEndpoint.dispatch(ParcelId("P-104"), remote = true) both times.',
      'Identify the Dispatcher.dispatch overload selected by the endpoint. Include its qualified method name and use the short Kotlin parameter type in parentheses.',
      'For each call, list the ledger, carrier and queue methods that this overload actually invokes in execution order. Use qualified method names and identify the concrete carrier method.',
      'Return only JSON with keys selectedOverload, first and duplicate. Each of first and duplicate has keys calls and outcome.',
      'Use "Queued(<label>)" for a queued result, with the actual label inside parentheses. Use "Duplicate" for a duplicate result.',
    ].join('\n'),
    allowedChangedPaths: [],
    oracle: {
      expectedTrace: {
        selectedOverload: 'parcel.dispatch.Dispatcher.dispatch(DispatchRequest)',
        first: {
          calls: [
            'parcel.dispatch.DispatchLedger.claim',
            'parcel.carriers.NetworkCarrier.prepare',
            'parcel.dispatch.DispatchQueue.enqueue',
          ],
          outcome: 'Queued(NET-P-104)',
        },
        duplicate: { calls: ['parcel.dispatch.DispatchLedger.claim'], outcome: 'Duplicate' },
      },
      witness: kotlinMain('DispatchWitness'),
    },
  },
  coroutineDelayCase,
  picnicAnsiCase,
];
