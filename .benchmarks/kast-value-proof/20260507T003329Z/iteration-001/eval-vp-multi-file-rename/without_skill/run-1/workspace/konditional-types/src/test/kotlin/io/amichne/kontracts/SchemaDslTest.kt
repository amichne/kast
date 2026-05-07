package io.amichne.kontracts

import io.amichne.kontracts.dsl.asBoolean
import io.amichne.kontracts.dsl.asDouble
import io.amichne.kontracts.dsl.asInt
import io.amichne.kontracts.dsl.asString
import io.amichne.kontracts.dsl.of
import io.amichne.kontracts.dsl.schema
import io.amichne.kontracts.schema.BooleanSchema
import io.amichne.kontracts.schema.DoubleSchema
import io.amichne.kontracts.schema.IntSchema
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.schema.SchemaProvider
import io.amichne.kontracts.schema.StringSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Comprehensive tests for the Schema DSL functionality.
 * Tests type-inferred DSL, custom type mapping, nullable handling, and schema construction.
 */
class SchemaDslTest {

    // ========== Type-Inferred DSL ==========

    @Test
    fun `schemaRoot creates schema for String property`() {
        data class Config(val name: String) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::name of {
                    minLength = 1
                    maxLength = 100
                    description = "User name"
                }
            }
        }

        val config = Config("test")
        val nameField = config.schema.fields["name"]!!
        assertIs<StringSchema>(nameField.schema)
        assertEquals(1, nameField.schema.minLength)
        assertEquals(100, nameField.schema.maxLength)
        assertEquals("User name", nameField.schema.description)
        assertTrue(nameField.required)
    }

    @Test
    fun `schemaRoot creates schema for Int property`() {
        data class Config(val count: Int) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::count of {
                    minimum = 0
                    maximum = 1000
                    description = "Item count"
                }
            }
        }

        val config = Config(10)
        val countField = config.schema.fields["count"]!!
        assertIs<IntSchema>(countField.schema)
        assertEquals(0, countField.schema.minimum)
        assertEquals(1000, countField.schema.maximum)
        assertEquals("Item count", countField.schema.description)
        assertTrue(countField.required)
    }

    @Test
    fun `schemaRoot creates schema for Double property`() {
        data class Config(val rate: Double) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::rate of {
                    minimum = 0.0
                    maximum = 100.0
                    description = "Success rate"
                }
            }
        }

        val config = Config(50.0)
        val rateField = config.schema.fields["rate"]!!
        assertIs<DoubleSchema>(rateField.schema)
        assertEquals(0.0, rateField.schema.minimum)
        assertEquals(100.0, rateField.schema.maximum)
        assertEquals("Success rate", rateField.schema.description)
        assertTrue(rateField.required)
    }

    @Test
    fun `schemaRoot creates schema for Boolean property`() {
        data class Config(val enabled: Boolean) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::enabled of {
                    description = "Feature enabled flag"
                    default = false
                }
            }
        }

        val config = Config(true)
        val enabledField = config.schema.fields["enabled"]!!
        assertIs<BooleanSchema>(enabledField.schema)
        assertEquals("Feature enabled flag", enabledField.schema.description)
        assertEquals(false, enabledField.schema.default)
        assertTrue(enabledField.required)
    }

    @Test
    fun `schemaRoot creates schema for multiple properties`() {
        data class Config(
            val name: String,
            val age: Int,
            val rate: Double,
            val active: Boolean
        ) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::name of { description = "Name" }
                ::age of { description = "Age" }
                ::rate of { description = "Rate" }
                ::active of { description = "Active" }
            }
        }

        val config = Config("Alice", 30, 0.95, true)
        assertEquals(4, config.schema.fields.size)
        assertTrue(config.schema.fields.containsKey("name"))
        assertTrue(config.schema.fields.containsKey("age"))
        assertTrue(config.schema.fields.containsKey("rate"))
        assertTrue(config.schema.fields.containsKey("active"))
    }

    // ========== Nullable Properties ==========

    @Test
    fun `schemaRoot handles nullable String property`() {
        data class Config(val nickname: String?) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::nickname of {
                    description = "Optional nickname"
                }
            }
        }

        val config = Config(null)
        val nicknameField = config.schema.fields["nickname"]!!
        assertIs<StringSchema>(nicknameField.schema)
        assertTrue(nicknameField.schema.nullable)
        assertFalse(nicknameField.required)
    }

    @Test
    fun `schemaRoot handles nullable Int property`() {
        data class Config(val maxRetries: Int?) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::maxRetries of {
                    description = "Optional max retries"
                }
            }
        }

        val config = Config(null)
        val retriesField = config.schema.fields["maxRetries"]!!
        assertIs<IntSchema>(retriesField.schema)
        assertTrue(retriesField.schema.nullable)
        assertFalse(retriesField.required)
    }

    @Test
    fun `schemaRoot handles nullable Double property`() {
        data class Config(val threshold: Double?) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::threshold of {
                    description = "Optional threshold"
                }
            }
        }

        val config = Config(null)
        val thresholdField = config.schema.fields["threshold"]!!
        assertIs<DoubleSchema>(thresholdField.schema)
        assertTrue(thresholdField.schema.nullable)
        assertFalse(thresholdField.required)
    }

    @Test
    fun `schemaRoot handles nullable Boolean property`() {
        data class Config(val verified: Boolean?) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::verified of {
                    description = "Optional verification status"
                }
            }
        }

        val config = Config(null)
        val verifiedField = config.schema.fields["verified"]!!
        assertIs<BooleanSchema>(verifiedField.schema)
        assertTrue(verifiedField.schema.nullable)
        assertFalse(verifiedField.required)
    }

    // ========== Custom Type Mapping ==========

    data class UserId(val value: String)
    data class Email(val value: String)
    data class Count(val value: Int)
    data class Percentage(val value: Double)
    data class Verified(val value: Boolean)

    @Test
    fun `asString maps custom type to StringSchema`() {
        data class Config(val userId: UserId) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::userId asString {
                    represent = { this.value }
                    pattern = "[A-Z0-9]{8}"
                    minLength = 8
                    maxLength = 8
                    description = "User ID"
                }
            }
        }

        val config = Config(UserId("ABC12345"))
        val userIdField = config.schema.fields["userId"]!!
        assertIs<StringSchema>(userIdField.schema)
        assertEquals("[A-Z0-9]{8}", userIdField.schema.pattern)
        assertEquals(8, userIdField.schema.minLength)
        assertEquals(8, userIdField.schema.maxLength)
        assertEquals("User ID", userIdField.schema.description)
    }

    @Test
    fun `asInt maps custom type to IntSchema`() {
        data class Config(val loginAttempts: Count) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::loginAttempts asInt {
                    represent = { this.value }
                    minimum = 0
                    maximum = 5
                    description = "Login attempts"
                }
            }
        }

        val config = Config(Count(0))
        val attemptsField = config.schema.fields["loginAttempts"]!!
        assertIs<IntSchema>(attemptsField.schema)
        assertEquals(0, attemptsField.schema.minimum)
        assertEquals(5, attemptsField.schema.maximum)
        assertEquals("Login attempts", attemptsField.schema.description)
    }

    @Test
    fun `asDouble maps custom type to DoubleSchema`() {
        data class Config(val completionRate: Percentage) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::completionRate asDouble {
                    represent = { this.value }
                    minimum = 0.0
                    maximum = 100.0
                    description = "Completion percentage"
                }
            }
        }

        val config = Config(Percentage(50.0))
        val rateField = config.schema.fields["completionRate"]!!
        assertIs<DoubleSchema>(rateField.schema)
        assertEquals(0.0, rateField.schema.minimum)
        assertEquals(100.0, rateField.schema.maximum)
        assertEquals("Completion percentage", rateField.schema.description)
    }

    @Test
    fun `asBoolean maps custom type to BooleanSchema`() {
        data class Config(val verified: Verified) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::verified asBoolean {
                    represent = { this.value }
                    description = "Verification status"
                }
            }
        }

        val config = Config(Verified(true))
        val verifiedField = config.schema.fields["verified"]!!
        assertIs<BooleanSchema>(verifiedField.schema)
        assertEquals("Verification status", verifiedField.schema.description)
    }

    @Test
    fun `custom type mapping preserves all constraints`() {
        data class Config(val email: Email) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::email asString {
                    represent = { this.value }
                    format = "email"
                    pattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
                    minLength = 5
                    maxLength = 255
                    description = "Email address"
                    example = "user@example.com"
                }
            }
        }

        val config = Config(Email("userexampl.com"))
        val emailField = config.schema.fields["email"]!!
        emailField.schema
        assertIs<StringSchema>(emailField.schema)
        assertEquals("email", emailField.schema.format)
        assertEquals("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", emailField.schema.pattern)
        assertEquals(5, emailField.schema.minLength)
        assertEquals(255, emailField.schema.maxLength)
        assertEquals("Email address", emailField.schema.description)
        assertEquals("user@example.com", emailField.schema.example)
    }

    // ========== Mixed Standard and Custom Types ==========

    @Test
    fun `schemaRoot handles mix of standard and custom types`() {
        data class Config(
            val userId: UserId,
            val name: String,
            val loginAttempts: Count,
            val active: Boolean
        ) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::userId asString {
                    represent = { this.value }
                    pattern = "[A-Z0-9]{8}"
                }
                ::name of {
                    minLength = 1
                }
                ::loginAttempts asInt {
                    represent = { this.value }
                    minimum = 0
                }
                ::active of {
                    description = "Active status"
                }
            }
        }

        val config = Config(UserId("ABC12345"), "Alice", Count(0), true)
        assertEquals(4, config.schema.fields.size)
        assertIs<StringSchema>(config.schema.fields["userId"]!!.schema)
        assertIs<StringSchema>(config.schema.fields["name"]!!.schema)
        assertIs<IntSchema>(config.schema.fields["loginAttempts"]!!.schema)
        assertIs<BooleanSchema>(config.schema.fields["active"]!!.schema)
    }

    // ========== Schema Metadata ==========

    @Test
    fun `schemaRoot captures default values`() {
        data class Config(
            val theme: String = "light",
            val maxRetries: Int = 3
        ) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::theme of {
                    default = "light"
                }
                ::maxRetries of {
                    default = 3
                }
            }
        }

        val config = Config()
        assertEquals("light", config.schema.fields["theme"]!!.schema.default)
        assertEquals(3, config.schema.fields["maxRetries"]!!.schema.default)
    }

    @Test
    fun `schemaRoot captures examples`() {
        data class Config(val email: String) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::email of {
                    example = "user@example.com"
                }
            }
        }

        val config = Config("test@test.com")
        assertEquals("user@example.com", config.schema.fields["email"]!!.schema.example)
    }

    @Test
    fun `schemaRoot captures deprecation status`() {
        data class Config(val oldField: String) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::oldField of {
                    deprecated = true
                    description = "Deprecated field, use newField instead"
                }
            }
        }

        val config = Config("test")
        assertTrue(config.schema.fields["oldField"]!!.schema.deprecated)
    }

    @Test
    fun `schemaRoot captures title`() {
        data class Config(val username: String) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::username of {
                    title = "Username"
                    description = "The user's login name"
                }
            }
        }

        val config = Config("alice")
        assertEquals("Username", config.schema.fields["username"]!!.schema.title)
    }

    // ========== Enum Support ==========

    @Test
    fun `schemaRoot handles string enum constraints`() {
        data class Config(val theme: String) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::theme of {
                    enum = listOf("light", "dark", "auto")
                    description = "UI theme"
                }
            }
        }

        val config = Config("light")
        val themeField = config.schema.fields["theme"]!!
        assertIs<StringSchema>(themeField.schema)
        assertEquals(listOf("light", "dark", "auto"), themeField.schema.enum)
    }

    @Test
    fun `schemaRoot handles int enum constraints`() {
        data class Config(val priority: Int) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::priority of {
                    enum = listOf(1, 2, 3, 4, 5)
                    description = "Priority level"
                }
            }
        }

        val config = Config(1)
        val priorityField = config.schema.fields["priority"]!!
        assertIs<IntSchema>(priorityField.schema)
        assertEquals(listOf(1, 2, 3, 4, 5), priorityField.schema.enum)
    }

    // ========== Empty Schema ==========

    @Test
    fun `schemaRoot creates empty schema when no properties defined`() {
        val schema = schema { }

        assertEquals(0, schema.fields.size)
    }

    // ========== Complex Real-World Example ==========

    @Test
    fun `schemaRoot handles complex configuration with all features`() {
        data class UserConfig(
            val userId: UserId,
            val email: Email,
            val displayName: String,
            val age: Int?,
            val loginAttempts: Count,
            val completionRate: Percentage,
            val isVerified: Boolean,
            val theme: String,
            val notificationsEnabled: Boolean?
        ) : SchemaProvider<ObjectSchema> {
            override val schema = schema {
                ::userId asString {
                    represent = { this.value }
                    pattern = "[A-Z0-9]{8}"
                    description = "Unique user identifier"
                }
                ::email asString {
                    represent = { this.value }
                    format = "email"
                    description = "Email address"
                }
                ::displayName of {
                    minLength = 1
                    maxLength = 100
                    description = "Display name"
                }
                ::age of {
                    minimum = 13
                    maximum = 120
                    description = "User age"
                }
                ::loginAttempts asInt {
                    represent = { this.value }
                    minimum = 0
                    maximum = 5
                    description = "Failed login attempts"
                }
                ::completionRate asDouble {
                    represent = { this.value }
                    minimum = 0.0
                    maximum = 100.0
                    description = "Completion percentage"
                }
                ::isVerified of {
                    description = "Account verification status"
                }
                ::theme of {
                    enum = listOf("light", "dark", "auto")
                    default = "auto"
                    description = "UI theme preference"
                }
                ::notificationsEnabled of {
                    description = "Push notifications setting"
                }
            }
        }

        val config = UserConfig(
            userId = UserId("ABC12345"),
            email = Email("user@example.com"),
            displayName = "Alice",
            age = 30,
            loginAttempts = Count(0),
            completionRate = Percentage(75.0),
            isVerified = true,
            theme = "dark",
            notificationsEnabled = true
        )

        assertEquals(9, config.schema.fields.size)

        // Verify custom types
        assertIs<StringSchema>(config.schema.fields["userId"]!!.schema)
        assertIs<StringSchema>(config.schema.fields["email"]!!.schema)

        // Verify standard types
        assertIs<StringSchema>(config.schema.fields["displayName"]!!.schema)
        assertIs<IntSchema>(config.schema.fields["age"]!!.schema)

        // Verify nullable handling
        assertTrue(config.schema.fields["age"]!!.schema.nullable)
        assertTrue(config.schema.fields["notificationsEnabled"]!!.schema.nullable)
        assertFalse(config.schema.fields["isVerified"]!!.schema.nullable)

        // Verify constraints
        assertEquals(listOf("light", "dark", "auto"), (config.schema.fields["theme"]!!.schema as StringSchema).enum)
    }
}
