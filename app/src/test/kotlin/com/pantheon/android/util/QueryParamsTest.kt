package com.pantheon.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryParamsTest {

    @Test
    fun `integer-valued double serializes without a decimal`() {
        assertEquals(mapOf("limit" to "16"), toQueryParams(mapOf("limit" to 16.0)))
    }

    @Test
    fun `non-integer double keeps its decimal`() {
        assertEquals(mapOf("seed" to "0.5"), toQueryParams(mapOf("seed" to 0.5)))
    }

    @Test
    fun `non-double values are stringified as-is`() {
        assertEquals(mapOf("sort" to "recently_added"), toQueryParams(mapOf("sort" to "recently_added")))
    }

    @Test
    fun `boolean values are stringified too`() {
        assertEquals(mapOf("home" to "true"), toQueryParams(mapOf("home" to true)))
    }

    @Test
    fun `null map returns an empty map`() {
        assertEquals(emptyMap<String, String>(), toQueryParams(null))
    }

    @Test
    fun `empty map returns an empty map`() {
        assertEquals(emptyMap<String, String>(), toQueryParams(emptyMap()))
    }

    @Test
    fun `quoteFilterValue wraps a plain word in quotes`() {
        assertEquals("\"Action\"", quoteFilterValue("Action"))
    }

    @Test
    fun `quoteFilterValue escapes embedded quotes`() {
        assertEquals("\"Sci-Fi \\\"Weird\\\"\"", quoteFilterValue("Sci-Fi \"Weird\""))
    }
}
