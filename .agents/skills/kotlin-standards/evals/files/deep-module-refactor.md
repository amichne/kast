# Deep module refactor

The current design split one workflow into many thin public pieces:

```kotlin
interface InputNormalizer {
    fun normalize(input: String): String
}
interface InputValidator {
    fun validate(input: String): List<String>
}
interface CommandPlanner {
    fun plan(input: String): List<Command>
}
interface CommandRunner {
    fun run(commands: List<Command>): Result<Unit>
}

class WorkflowService(
    private val normalizer: InputNormalizer,
    private val validator: InputValidator,
    private val planner: CommandPlanner,
    private val runner: CommandRunner,
) {
    fun execute(raw: String): Result<Unit> {
        val normalized = normalizer.normalize(raw)
        val errors = validator.validate(normalized)
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString()))
        val commands = planner.plan(normalized)
        return runner.run(commands)
    }
}
```

Problems:

- Callers still have to understand nearly every step of the workflow.
- Each interface has one implementation and little independent leverage.
- Tests target helpers instead of the module interface.
- The orchestration is broad and shallow rather than concentrated behind a
  smaller seam.

The expected guidance should favor a deeper module with a smaller interface,
internal helpers where needed, and tests that cross the same seam as callers.
