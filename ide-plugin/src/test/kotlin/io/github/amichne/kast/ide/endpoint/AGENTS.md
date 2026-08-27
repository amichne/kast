# Endpoint publication tests

These two selectors are the executable KVP-024 product proof.

- The negative selector names every canonical rejection and verifies occupied artifacts remain
  untouched and duplicate publication occurs before a second bind.
- The positive selector uses the real JDK Unix-domain socket and atomic descriptor publisher,
  connects physically, reads canonical bytes, and re-admits descriptor v2.
- Keep shared fixtures private inside the two receipt-bound selector files.

Run `./gradlew :ide-plugin:verifyIdeEndpointPublicationNegative
:ide-plugin:verifyIdeEndpointPublication`.
