# Nesting over prefixes

The current design keeps several small root declarations in one file and uses prefixes to keep them apart:

```kotlin
sealed interface UserQueryResult

data class UserQueryResultSuccess(val users: List<User>) : UserQueryResult
data class UserQueryResultFailure(val message: String) : UserQueryResult

fun parseUserQuery(input: String): UserQuery
fun formatUserQueryResult(result: UserQueryResult): String
```

Problems:

- The flat scope forces repeated `UserQueryResult` prefixes.
- The file has several sibling root declarations instead of one owning root.
- The top-level functions belong to the same vocabulary but sit outside any owner.
- The naming is harder to scan and refer to because the scope is not carrying enough meaning.

The expected guidance should favor one owning root declaration, nested variants or companion-owned APIs, and short names
that rely on scope instead of prefixes.
