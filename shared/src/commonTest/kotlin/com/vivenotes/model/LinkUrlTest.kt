package com.vivenotes.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Android `LinkUrlTest`, plus the `java.net.URI` rules the portable parser has to reproduce. */
class LinkUrlTest {
    @Test fun acceptsWebAddressesWithOrWithoutScheme() {
        assertEquals("https://example.com/a?q=1", normalizedLinkUrl(" example.com/a?q=1 "))
        assertEquals("http://example.com", normalizedLinkUrl("http://example.com"))
    }

    @Test fun rejectsNonWebAndMalformedAddresses() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "https://user@host.test/",
            "https://", "example.com/has space", "").forEach {
            assertNull(normalizedLinkUrl(it), it)
        }
    }

    @Test fun acceptsWhatAServerAuthorityMayHold() {
        listOf(
            "HTTPS://Example.com", "https://example.com:8080/path", "https://192.168.0.1/",
            "https://[2001:db8::1]:443/", "https://sub-domain.example.org./a%20b#top",
            "https://example.com/wiki/Ünïcode",
        ).forEach { assertEquals(it, normalizedLinkUrl(it), it) }
    }

    @Test fun rejectsWhatUriWouldRefuse() {
        listOf(
            "https://exa_mple.com", "https://-example.com", "https://example.com:port",
            "https://example.123", "https://example.com/a<b", "https://example.com/%zz",
            "https://example.com/a#b#c", "https://[::1/", "ftp://example.com",
        ).forEach { assertNull(normalizedLinkUrl(it), it) }
    }
}
