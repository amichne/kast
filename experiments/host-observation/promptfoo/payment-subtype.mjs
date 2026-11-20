// Held-out subtype twin, authored before any trial ran with the revised product or
// skill. The tracked code declares two same-named extensions: Payment.describe() in
// Descriptions.kt and Any.describe() in Fallbacks.kt. In `north`, the jar's Card
// implements Payment, so checkout(card) resolves to the more specific
// Payment.describe(). In `south`, Card does not implement Payment, so the same call
// resolves to Any.describe(). refund(payment) calls Payment.describe() in both.
import * as twin from './binary-twin.mjs';

export const spec = Object.freeze({
  code: 'PAYMENT',
  worlds: Object.freeze(['north', 'south']),
  jar: 'payments.jar',
  libraryClasses: Object.freeze(['payments.Payment', 'payments.Card']),
  descriptor: 'billing/DescriptionsKt.describe:(Lpayments/Payment;)Ljava/lang/String;',
  request: 'I am changing the text returned by the Payment.describe() extension in src/main/kotlin/billing/Descriptions.kt. ' +
    'List the name of each function in this checkout’s main source set whose call uses that extension.',
  // Qualification selects the declaration by position, so it needs no file-scope support.
  queryArgs: Object.freeze([
    'query', '--file', 'src/main/kotlin/billing/Descriptions.kt', '--offset', '54',
    '--relation', 'callers', '--fields', 'name,location', '--json',
  ]),
  library: Object.freeze({
    north: {
      'payments/Payment.kt': 'package payments\n\ninterface Payment\n',
      'payments/Card.kt': 'package payments\n\nclass Card(val last4: String) : Payment\n',
    },
    south: {
      'payments/Payment.kt': 'package payments\n\ninterface Payment\n',
      'payments/Card.kt': 'package payments\n\nclass Card(val last4: String)\n',
    },
  }),
  fixture: Object.freeze({
    'settings.gradle.kts': 'rootProject.name = "billing"\n',
    'build.gradle.kts': twin.buildScript('payments.jar'),
    'src/main/kotlin/billing/Descriptions.kt': 'package billing\n\nimport payments.Payment\n\n' +
      'fun Payment.describe(): String = "payment"\n',
    'src/main/kotlin/billing/Fallbacks.kt': 'package billing\n\nfun Any.describe(): String = "value"\n',
    'src/main/kotlin/billing/Checkout.kt': 'package billing\n\nimport payments.Card\nimport payments.Payment\n\n' +
      'fun checkout(card: Card): String = card.describe()\n\n' +
      'fun refund(payment: Payment): String = payment.describe()\n\n' +
      'fun audit(value: Int): String = value.describe()\n',
  }),
});
