package dev.web2app.shell

import dev.web2app.shell.LinkPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {

    private fun decide(
        url: String,
        internalHosts: List<String> = emptyList(),
        blocked: List<String> = emptyList(),
        customSchemes: List<String> = emptyList(),
        externalPolicy: String = "browser",
        startHost: String? = "example.com",
    ): Decision {
        val scheme = url.substringBefore(":", "").lowercase().ifEmpty { null }
        val host = url.substringAfter("://", "").substringBefore("/").substringBefore(":")
            .lowercase().ifEmpty { null }
        return LinkPolicy.decide(url, host, scheme, startHost, internalHosts, blocked, customSchemes, externalPolicy)
    }

    @Test
    fun `start host loads in the webview`() {
        assertEquals(Decision.OPEN_INTERNAL, decide("https://example.com/page"))
    }

    @Test
    fun `unlisted host opens externally by default`() {
        assertEquals(Decision.OPEN_EXTERNAL, decide("https://other.com/page"))
    }

    @Test
    fun `blocking wins over an internal host`() {
        assertEquals(
            Decision.BLOCK,
            decide("https://example.com/admin", blocked = listOf("/admin")),
        )
    }

    @Test
    fun `wildcard matches subdomains but not a lookalike host`() {
        assertTrue(LinkPolicy.isInternal("shop.example.com", null, listOf("*.example.com")))
        assertTrue(LinkPolicy.isInternal("example.com", null, listOf("*.example.com")))
        // The bug this guards: endsWith("example.com") alone would accept this.
        assertFalse(LinkPolicy.isInternal("notexample.com", null, listOf("*.example.com")))
        assertFalse(LinkPolicy.isInternal("example.com.evil.test", null, listOf("*.example.com")))
    }

    @Test
    fun `bare host does not match its subdomains`() {
        assertFalse(LinkPolicy.isInternal("shop.example.com", null, listOf("example.com")))
    }

    @Test
    fun `non-web schemes leave the webview`() {
        assertEquals(Decision.OPEN_EXTERNAL, decide("mailto:hi@example.com"))
        assertEquals(Decision.OPEN_EXTERNAL, decide("tel:+1234567890"))
        assertEquals(Decision.OPEN_EXTERNAL, decide("intent://scan#Intent;scheme=zxing;end"))
    }

    @Test
    fun `blocked pattern still wins over a custom scheme`() {
        assertEquals(
            Decision.BLOCK,
            decide("myapp://pay", customSchemes = listOf("myapp"), blocked = listOf("myapp://pay")),
        )
    }

    @Test
    fun `external policy block refuses offsite navigation`() {
        assertEquals(Decision.BLOCK, decide("https://other.com", externalPolicy = "block"))
    }

    @Test
    fun `external policy webview keeps offsite navigation inside`() {
        assertEquals(Decision.OPEN_INTERNAL, decide("https://other.com", externalPolicy = "webview"))
    }

    @Test
    fun `host matching ignores case`() {
        assertTrue(LinkPolicy.isInternal("shop.example.com", null, listOf("*.EXAMPLE.com")))
    }

    @Test
    fun `empty pattern matches nothing`() {
        assertFalse(LinkPolicy.isInternal("example.com", null, listOf("")))
        assertFalse(LinkPolicy.isInternal("example.com", null, emptyList()))
    }
}
