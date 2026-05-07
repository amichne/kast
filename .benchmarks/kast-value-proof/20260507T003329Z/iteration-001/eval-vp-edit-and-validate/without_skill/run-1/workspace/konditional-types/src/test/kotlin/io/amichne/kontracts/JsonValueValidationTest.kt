package io.amichne.kontracts

import io.amichne.kontracts.dsl.booleanSchema
import io.amichne.kontracts.dsl.doubleSchema
import io.amichne.kontracts.dsl.intSchema
import io.amichne.kontracts.dsl.nullSchema
import io.amichne.kontracts.dsl.stringSchema
import io.amichne.kontracts.value.JsonBoolean
import io.amichne.kontracts.value.JsonNull
import io.amichne.kontracts.value.JsonNumber
import io.amichne.kontracts.value.JsonString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive tests for JsonValue validation against schemas.
 * Tests validation logic for all primitive JsonValue types.
 */
class JsonValueValidationTest {

    // ========== JsonString Validation Tests ==========

    @Test
    fun `JsonString validates successfully against basic string schema`() {
        val schema = stringSchema { }
        val value = JsonString("hello")

        val result = value.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonString enforces minLength constraint`() {
        val schema = stringSchema { minLength = 5 }
        val valid = JsonString("hello")
        val invalid = JsonString("hi")

        assertTrue(valid.validate(schema).isValid)
        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("String length 2 is less than minimum length 5", result.getErrorMessage())
    }

    @Test
    fun `JsonString enforces maxLength constraint`() {
        val schema = stringSchema { maxLength = 10 }
        val valid = JsonString("hello")
        val invalid = JsonString("this is too long")

        assertTrue(valid.validate(schema).isValid)
        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("String length 16 is greater than maximum length 10", result.getErrorMessage())
    }

    @Test
    fun `JsonString validates against regex pattern`() {
        val schema = stringSchema { pattern = "^[A-Z][a-z]+$" }
        val valid = JsonString("Hello")
        val invalidCase = JsonString("hello")
        val invalidFormat = JsonString("HELLO")

        assertTrue(valid.validate(schema).isValid)

        val result1 = invalidCase.validate(schema)
        assertFalse(result1.isValid)
        assertEquals("String 'hello' does not match pattern ^[A-Z][a-z]+$", result1.getErrorMessage())

        val result2 = invalidFormat.validate(schema)
        assertFalse(result2.isValid)
        assertEquals("String 'HELLO' does not match pattern ^[A-Z][a-z]+$", result2.getErrorMessage())
    }

    @Test
    fun `JsonString validates email pattern`() {
        val schema = stringSchema {
            pattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
            format = "email"
        }
        val valid = JsonString("user@example.com")
        val invalid = JsonString("not-an-email")

        assertTrue(valid.validate(schema).isValid)
        assertFalse(invalid.validate(schema).isValid)
    }

    @Test
    fun `JsonString enforces combined constraints`() {
        val schema = stringSchema {
            minLength = 8
            maxLength = 12
            pattern = "[A-Z0-9]+"
        }
        val valid = JsonString("ABC12345")
        val tooShort = JsonString("ABC123")
        val tooLong = JsonString("ABCDEFGHIJKLM")
        val invalidPattern = JsonString("abc12345")

        assertTrue(valid.validate(schema).isValid)
        assertFalse(tooShort.validate(schema).isValid)
        assertFalse(tooLong.validate(schema).isValid)
        assertFalse(invalidPattern.validate(schema).isValid)
    }

    @Test
    fun `JsonString fails validation against non-string schema`() {
        val intSchema = intSchema { }
        val value = JsonString("123")

        val result = value.validate(intSchema)

        assertFalse(result.isValid)
        assertEquals("Expected IntSchema, but got String", result.getErrorMessage())
    }

    // ========== JsonNumber Validation Tests ==========

    @Test
    fun `JsonNumber validates successfully as integer`() {
        val schema = intSchema { }
        val value = JsonNumber(42.0)

        val result = value.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonNumber validates successfully as double`() {
        val schema = doubleSchema { }
        val value = JsonNumber(42.5)

        val result = value.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonNumber enforces integer constraint`() {
        val schema = intSchema { }
        val valid = JsonNumber(42.0)
        val invalid = JsonNumber(42.5)

        assertTrue(valid.validate(schema).isValid)

        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("Expected integer value, but got 42.5", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber enforces int minimum constraint`() {
        val schema = intSchema { minimum = 0 }
        val valid = JsonNumber(5.0)
        val invalid = JsonNumber(-1.0)

        assertTrue(valid.validate(schema).isValid)

        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("Value -1.0 is less than minimum 0", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber enforces int maximum constraint`() {
        val schema = intSchema { maximum = 100 }
        val valid = JsonNumber(50.0)
        val invalid = JsonNumber(101.0)

        assertTrue(valid.validate(schema).isValid)

        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("Value 101.0 is greater than maximum 100", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber enforces double minimum constraint`() {
        val schema = doubleSchema { minimum = 0.0 }
        val valid = JsonNumber(5.5)
        val invalid = JsonNumber(-0.1)

        assertTrue(valid.validate(schema).isValid)

        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("Value -0.1 is less than minimum 0.0", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber enforces double maximum constraint`() {
        val schema = doubleSchema { maximum = 100.0 }
        val valid = JsonNumber(99.9)
        val invalid = JsonNumber(100.1)

        assertTrue(valid.validate(schema).isValid)

        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("Value 100.1 is greater than maximum 100.0", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber validates int enum values`() {
        val schema = intSchema { enum = listOf(1, 2, 3) }
        val valid = JsonNumber(2.0)
        val invalid = JsonNumber(5.0)

        assertTrue(valid.validate(schema).isValid)

        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("Value 5.0 is not in enum [1, 2, 3]", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber validates double enum values`() {
        val schema = doubleSchema { enum = listOf(1.5, 2.5, 3.5) }
        val valid = JsonNumber(2.5)
        val invalid = JsonNumber(5.0)

        assertTrue(valid.validate(schema).isValid)

        val result = invalid.validate(schema)
        assertFalse(result.isValid)
        assertEquals("Value 5.0 is not in enum [1.5, 2.5, 3.5]", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber handles boundary values for Int range`() {
        val schema = intSchema { }
        val min = JsonNumber(Int.MIN_VALUE.toDouble())
        val max = JsonNumber(Int.MAX_VALUE.toDouble())
        val outOfRange = JsonNumber(Int.MAX_VALUE.toDouble() + 1.0)

        assertTrue(min.validate(schema).isValid)
        assertTrue(max.validate(schema).isValid)
        assertFalse(outOfRange.validate(schema).isValid)
    }

    @Test
    fun `JsonNumber fails validation against non-numeric schema`() {
        val stringSchema = stringSchema { }
        val value = JsonNumber(42.0)

        val result = value.validate(stringSchema)

        assertFalse(result.isValid)
        assertEquals("Expected StringSchema, but got JsonNumber", result.getErrorMessage())
    }

    // ========== JsonBoolean Validation Tests ==========

    @Test
    fun `JsonBoolean validates successfully against boolean schema`() {
        val schema = booleanSchema { }
        val trueValue = JsonBoolean(true)
        val falseValue = JsonBoolean(false)

        assertTrue(trueValue.validate(schema).isValid)
        assertTrue(falseValue.validate(schema).isValid)
    }

    @Test
    fun `JsonBoolean fails validation against non-boolean schema`() {
        val stringSchema = stringSchema { }
        val value = JsonBoolean(true)

        val result = value.validate(stringSchema)

        assertFalse(result.isValid)
        assertEquals("Expected ${stringSchema}, but got Boolean", result.getErrorMessage())
    }

    // ========== JsonNull Validation Tests ==========

    @Test
    fun `JsonNull validates successfully against null schema`() {
        val schema = nullSchema { }
        val value = JsonNull

        val result = value.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonNull fails validation against non-null schema`() {
        val stringSchema = stringSchema { }
        val value = JsonNull

        val result = value.validate(stringSchema)

        assertFalse(result.isValid)
        assertEquals("Expected ${stringSchema}, but got Null", result.getErrorMessage())
    }

    // ========== Edge Cases ==========

    @Test
    fun `JsonString validates empty string when no minLength constraint`() {
        val schema = stringSchema { }
        val value = JsonString("")

        val result = value.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonString rejects empty string with minLength constraint`() {
        val schema = stringSchema { minLength = 1 }
        val value = JsonString("")

        val result = value.validate(schema)

        assertFalse(result.isValid)
        assertEquals("String length 0 is less than minimum length 1", result.getErrorMessage())
    }

    @Test
    fun `JsonNumber zero validates against unbounded int schema`() {
        val schema = intSchema { }
        val value = JsonNumber(0.0)

        val result = value.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonNumber handles exact boundary values`() {
        val schema = intSchema {
            minimum = 0
            maximum = 100
        }
        val min = JsonNumber(0.0)
        val max = JsonNumber(100.0)

        assertTrue(min.validate(schema).isValid)
        assertTrue(max.validate(schema).isValid)
    }
}
