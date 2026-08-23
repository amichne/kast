# PR 633 executable program

This directory owns the checked program projection, the expected operation registry, and the
policies and schemas used by GATE-001 through GATE-070. The executable Gradle wiring is provided by
the `kast.pr633-*` convention plugins. Reusable task types remain in `build-logic`. PR 633 must stop
at GATE-070 and remain unmerged.

Run `./gradlew verifyPr633ProgramArtifacts` after changing this directory.
