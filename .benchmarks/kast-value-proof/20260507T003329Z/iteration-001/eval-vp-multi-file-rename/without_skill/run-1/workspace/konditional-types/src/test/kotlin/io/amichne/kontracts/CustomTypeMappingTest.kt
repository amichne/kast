package io.amichne.kontracts

import io.amichne.kontracts.dsl.asBoolean
import io.amichne.kontracts.dsl.asDouble
import io.amichne.kontracts.dsl.asInt
import io.amichne.kontracts.dsl.asString
import io.amichne.kontracts.dsl.of
import io.amichne.kontracts.dsl.schema
import io.amichne.kontracts.schema.DoubleSchema
import io.amichne.kontracts.schema.IntSchema
import io.amichne.kontracts.schema.JsonSchema
import io.amichne.kontracts.schema.StringSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for custom type mapping in JsonSchema DSL.
 *
 * Demonstrates how to map custom types to primitive schema representations
 * with inline conversion specifications.
 */
class CustomTypeMappingTest {

    // Custom domain types
    data class UserId(val value: String)
    data class Email(val value: String)
    data class Count(val value: Int)
    data class Percentage(val value: Double)
    data class Agent(val isAgent: Boolean)

    // Configuration using custom type mapping
    data class UserConfig(
        val agent: Agent,
        val userId: UserId,
        val email: Email,
        val loginAttempts: Count,
        val completionRate: Percentage,
        val nickname: String,
    ) {
        val schema = schema {
            ::agent asBoolean {
                description = "Boolean flag test"
                example = true
            }

            // Custom type mapped to String with validation rules
            ::userId asString {
                represent = { this.value }
                pattern = "[A-Z0-9]{8}"
                minLength = 8
                maxLength = 8
                description = "Unique 8-character user identifier"
            }

            // Email custom type with format specification
            ::email asString {
                represent = { this.value }
                format = "email"
                pattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
                description = "User email address"
            }

            // Custom type mapped to Int with constraints
            ::loginAttempts asInt {
                represent = { this.value }
                minimum = 0
                maximum = 5
                description = "Number of failed login attempts"
            }

            // Custom type mapped to Double
            ::completionRate asDouble {
                represent = { this.value }
                minimum = 0.0
                maximum = 100.0
                format = "double"
                description = "Task completion percentage"
            }

            // Regular primitive type (for comparison)
            ::nickname of {
                minLength = 1
                maxLength = 50
                description = "User's display name"
            }
        }
    }

    @Test
    fun `custom type mapping creates correct schema types`() {
        val config = UserConfig(
            agent = Agent(true),
            userId = UserId("ABC12345"),
            email = Email("user@example.com"),
            loginAttempts = Count(0),
            completionRate = Percentage(75.5),
            nickname = "testuser"
        )

        // Verify userId schema
        val userIdField = config.schema.fields["userId"]!!
        assertIs<StringSchema>(userIdField.schema)
        assertEquals("[A-Z0-9]{8}", userIdField.schema.pattern)
        assertEquals(8, userIdField.schema.minLength)
        assertEquals(8, userIdField.schema.maxLength)
        assertEquals("Unique 8-character user identifier", userIdField.schema.description)

        // Verify email schema
        val emailField = config.schema.fields["email"]!!
        assertIs<StringSchema>(emailField.schema)
        assertEquals("email", emailField.schema.format)

        // Verify loginAttempts schema
        val attemptsField = config.schema.fields["loginAttempts"]!!
        assertIs<IntSchema>(attemptsField.schema)
        assertEquals(0, attemptsField.schema.minimum)
        assertEquals(5, attemptsField.schema.maximum)

        // Verify completionRate schema
        val rateField = config.schema.fields["completionRate"]!!
        assertIs<DoubleSchema>(rateField.schema)
        assertEquals(0.0, rateField.schema.minimum)
        assertEquals(100.0, rateField.schema.maximum)

        // Verify regular primitive type
        val nicknameField = config.schema.fields["nickname"]!!
        assertIs<StringSchema>(nicknameField.schema)
    }

    @Test
    fun `custom type conversion functions are captured`() {
        val config = UserConfig(
            agent = Agent(true),
            userId = UserId("XYZ98765"),
            email = Email("test@test.com"),
            loginAttempts = Count(2),
            completionRate = Percentage(88.5),
            nickname = "demo"
        )

        // The schema is created successfully
        assertEquals(6, config.schema.fields.size)

        // All fields have their schemas defined
        config.schema.fields.values.forEach { fieldSchema ->
            assertIs<JsonSchema<*>>(fieldSchema.schema)
        }
    }
}
