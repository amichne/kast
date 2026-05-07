package io.amichne.kontracts

import io.amichne.kontracts.dsl.arraySchema
import io.amichne.kontracts.dsl.booleanSchema
import io.amichne.kontracts.dsl.elementSchema
import io.amichne.kontracts.dsl.fieldSchema
import io.amichne.kontracts.dsl.intSchema
import io.amichne.kontracts.dsl.objectSchema
import io.amichne.kontracts.dsl.stringSchema
import io.amichne.kontracts.value.JsonArray
import io.amichne.kontracts.value.JsonBoolean
import io.amichne.kontracts.value.JsonNumber
import io.amichne.kontracts.value.JsonObject
import io.amichne.kontracts.value.JsonString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for JsonArray validation against schemas.
 * Tests element validation, array constraints, and complex array structures.
 */
class JsonArrayValidationTest {

    // ========== Basic Array Validation ==========

    @Test
    fun `JsonArray validates successfully with homogeneous elements`() {
        val schema = arraySchema {
            elementSchema(stringSchema { })
        }
        val arr = JsonArray(
            elements = listOf(
                JsonString("hello"),
                JsonString("world")
            )
        )

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonArray validates empty array`() {
        val schema = arraySchema {
            elementSchema(stringSchema { })
        }
        val arr = JsonArray(elements = emptyList())

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonArray fails validation with heterogeneous elements`() {
        val schema = arraySchema {
            elementSchema(stringSchema { })
        }
        val arr = JsonArray(
            elements = listOf(
                JsonString("hello"),
                JsonNumber(42.0)  // Wrong type
            )
        )

        val result = arr.validate(schema)

        assertFalse(result.isValid)
        assertTrue(result.getErrorMessage()?.contains("Element at index 1:") == true)
    }

    @Test
    fun `JsonArray validates array of integers`() {
        val schema = arraySchema {
            elementSchema(intSchema { })
        }
        val arr = JsonArray(
            elements = listOf(
                JsonNumber(1.0),
                JsonNumber(2.0),
                JsonNumber(3.0)
            )
        )

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonArray validates array of booleans`() {
        val schema = arraySchema {
            elementSchema(booleanSchema { })
        }
        val arr = JsonArray(
            elements = listOf(
                JsonBoolean(true),
                JsonBoolean(false),
                JsonBoolean(true)
            )
        )

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonArray fails validation when element violates schema constraints`() {
        val schema = arraySchema {
            elementSchema(
                stringSchema {
                    minLength = 5
                }
            )
        }
        val arr = JsonArray(
            elements = listOf(
                JsonString("hello"),
                JsonString("hi")  // Too short
            )
        )

        val result = arr.validate(schema)

        assertFalse(result.isValid)
        assertTrue(result.getErrorMessage()?.contains("Element at index 1:") == true)
        assertTrue(result.getErrorMessage()?.contains("less than minimum length 5") == true)
    }

    // ========== Array of Objects ==========

    @Test
    fun `JsonArray validates array of objects successfully`() {
        val objectSchema = objectSchema {
            fields = mapOf(
                "id" to fieldSchema { schema = intSchema {  }; required = true },
                "name" to fieldSchema { schema = stringSchema {  }; required = true }
            )
        }
        val schema = arraySchema {
            elementSchema(objectSchema)
        }
        val arr = JsonArray(
            elements = listOf(
                JsonObject(
                    fields = mapOf(
                        "id" to JsonNumber(1.0),
                        "name" to JsonString("Alice")
                    )
                ),
                JsonObject(
                    fields = mapOf(
                        "id" to JsonNumber(2.0),
                        "name" to JsonString("Bob")
                    )
                )
            )
        )

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonArray fails validation when object element is invalid`() {
        val objectSchema = objectSchema {
            fields = mapOf(
                "id" to fieldSchema { schema = intSchema {  }; required = true },
                "name" to fieldSchema { schema = stringSchema {  }; required = true }
            )
        }
        val schema = arraySchema {
            elementSchema(objectSchema)
        }
        val arr = JsonArray(
            elements = listOf(
                JsonObject(
                    fields = mapOf(
                        "id" to JsonNumber(1.0),
                        "name" to JsonString("Alice")
                    )
                ),
                JsonObject(
                    fields = mapOf(
                        "id" to JsonNumber(2.0)
                        // Missing "name"
                    )
                )
            )
        )

        val result = arr.validate(schema)

        assertFalse(result.isValid)
        assertTrue(result.getErrorMessage()?.contains("Element at index 1:") == true)
        assertTrue(result.getErrorMessage()?.contains("Missing required fields: [name]") == true)
    }

    // ========== Nested Arrays ==========

    @Test
    fun `JsonArray validates nested arrays successfully`() {
        val innerSchema = arraySchema {
            elementSchema(intSchema { })
        }
        val schema = arraySchema {
            elementSchema(innerSchema)
        }
        val arr = JsonArray(
            elements = listOf(
                JsonArray(elements = listOf(JsonNumber(1.0), JsonNumber(2.0))),
                JsonArray(elements = listOf(JsonNumber(3.0), JsonNumber(4.0)))
            )
        )

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonArray fails validation with invalid nested array element`() {
        val innerSchema = arraySchema {
            elementSchema(intSchema { })
        }
        val schema = arraySchema {
            elementSchema(innerSchema)
        }
        val arr = JsonArray(
            elements = listOf(
                JsonArray(elements = listOf(JsonNumber(1.0), JsonNumber(2.0))),
                JsonArray(elements = listOf(JsonString("invalid"), JsonNumber(4.0)))
            )
        )

        val result = arr.validate(schema)

        assertFalse(result.isValid)
        assertTrue(result.getErrorMessage()?.contains("Element at index 1:") == true)
    }

    // ========== Array Element Access ==========

    @Test
    fun `JsonArray get operator retrieves element by index`() {
        val arr = JsonArray(
            elements = listOf(
                JsonString("hello"),
                JsonString("world")
            )
        )

        assertEquals(JsonString("hello"), arr[0])
        assertEquals(JsonString("world"), arr[1])
        assertNull(arr[2])
        assertNull(arr[-1])
    }

    @Test
    fun `JsonArray size returns correct count`() {
        val arr = JsonArray(
            elements = listOf(
                JsonString("a"),
                JsonString("b"),
                JsonString("c")
            )
        )

        assertEquals(3, arr.size)
    }

    @Test
    fun `JsonArray isEmpty returns true for empty array`() {
        val arr = JsonArray(elements = emptyList())

        assertTrue(arr.isEmpty())
        assertFalse(arr.isNotEmpty())
    }

    @Test
    fun `JsonArray isEmpty returns false for non-empty array`() {
        val arr = JsonArray(elements = listOf(JsonString("test")))

        assertFalse(arr.isEmpty())
        assertTrue(arr.isNotEmpty())
    }

    // ========== Type Mismatch ==========

    @Test
    fun `JsonArray fails validation against non-array schema`() {
        val stringSchema = stringSchema { }
        val arr = JsonArray(elements = listOf(JsonString("test")))

        val result = arr.validate(stringSchema)

        assertFalse(result.isValid)
        assertEquals("Expected ${stringSchema}, but got JsonArray", result.getErrorMessage())
    }

    // ========== Construction Validation ==========

    @Test
    fun `JsonArray construction validates elements against provided schema`() {
        val elementSchema = stringSchema { minLength = 5 }

        val exception = assertThrows<IllegalArgumentException> {
            JsonArray(
                elements = listOf(
                    JsonString("hello"),
                    JsonString("hi")  // Too short
                ),
                elementSchema = elementSchema
            )
        }

        assertTrue(exception.message?.contains("JsonArray does not match schema") == true)
    }

    @Test
    fun `JsonArray construction succeeds with valid elements`() {
        val elementSchema = stringSchema { minLength = 5 }
        val arr = JsonArray(
            elements = listOf(
                JsonString("hello"),
                JsonString("world")
            ),
            elementSchema = elementSchema
        )

        assertEquals(2, arr.size)
    }

    // ========== Complex Element Schemas ==========

    @Test
    fun `JsonArray validates with complex element constraints`() {
        val elementSchema = intSchema {
            minimum = 0
            maximum = 100
        }
        val schema = arraySchema {
            elementSchema(elementSchema)
        }
        val arr = JsonArray(
            elements = listOf(
                JsonNumber(0.0),
                JsonNumber(50.0),
                JsonNumber(100.0)
            )
        )

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    @Test
    fun `JsonArray fails validation when element exceeds constraints`() {
        val elementSchema = intSchema {
            minimum = 0
            maximum = 100
        }
        val schema = arraySchema {
            elementSchema(elementSchema)
        }
        val arr = JsonArray(
            elements = listOf(
                JsonNumber(50.0),
                JsonNumber(101.0)  // Exceeds maximum
            )
        )

        val result = arr.validate(schema)

        assertFalse(result.isValid)
        assertTrue(result.getErrorMessage()?.contains("Element at index 1:") == true)
        assertTrue(result.getErrorMessage()?.contains("greater than maximum 100") == true)
    }

    // ========== Array of Mixed Complex Types ==========

    @Test
    fun `JsonArray validates array with objects containing nested arrays`() {
        val tagsSchema = arraySchema {
            elementSchema(stringSchema { })
        }
        val objectSchema = objectSchema {
            fields = mapOf(
                "id" to fieldSchema { schema = intSchema {  }; required = true },
                "tags" to fieldSchema { schema = tagsSchema; required = true }
            )
        }
        val schema = arraySchema {
            elementSchema(objectSchema)
        }
        val arr = JsonArray(
            elements = listOf(
                JsonObject(
                    fields = mapOf(
                        "id" to JsonNumber(1.0),
                        "tags" to JsonArray(elements = listOf(JsonString("tag1"), JsonString("tag2")))
                    )
                ),
                JsonObject(
                    fields = mapOf(
                        "id" to JsonNumber(2.0),
                        "tags" to JsonArray(elements = listOf(JsonString("tag3")))
                    )
                )
            )
        )

        val result = arr.validate(schema)

        assertTrue(result.isValid)
    }

    // ========== toString ==========

    @Test
    fun `JsonArray toString formats correctly`() {
        val arr = JsonArray(
            elements = listOf(
                JsonString("hello"),
                JsonNumber(42.0),
                JsonBoolean(true)
            )
        )

        val str = arr.toString()

        assertTrue(str.contains("hello"))
        assertTrue(str.contains("42"))
        assertTrue(str.contains("true"))
    }

    @Test
    fun `JsonArray empty array toString`() {
        val arr = JsonArray(elements = emptyList())

        assertEquals("[]", arr.toString())
    }
}
