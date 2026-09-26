package com.vivenotes.model

/**
 * A web address suitable for an external link, accepting a host without a typed scheme.
 *
 * Android's `model/LinkUrl.kt` answers this with `java.net.URI`; common code has no URI parser, so
 * the parts of that parse the rule depends on are spelled out here: an http(s) scheme, a server
 * authority with a real host and no user info, and no character `URI` would refuse.
 */
fun normalizedLinkUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
    val url = if ("://" in trimmed) trimmed else "https://$trimmed"
    val schemeEnd = url.indexOf("://")
    if (url.substring(0, schemeEnd).lowercase() !in setOf("http", "https")) return null
    val rest = url.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if ('@' in authority || !authority.isServerAuthority()) return null
    return url.takeIf { rest.substring(authorityEnd).isValidPathQueryFragment() }
}

private fun String.isServerAuthority(): Boolean {
    val (host, port) = if (startsWith('[')) {
        val close = indexOf(']')
        if (close < 0) return false
        substring(0, close + 1) to substring(close + 1)
    } else {
        substringBefore(':') to (if (':' in this) ":" + substringAfter(':') else "")
    }
    if (port.isNotEmpty() && (port[0] != ':' || !port.drop(1).all(Char::isAsciiDigit))) return false
    return host.isIpv6Literal() || host.isHostName()
}

private fun String.isIpv6Literal(): Boolean =
    length > 2 && startsWith('[') && endsWith(']') &&
        substring(1, length - 1).let { body -> ':' in body && body.all { it.isHexDigit() || it == ':' || it == '.' } }

/** A dotted IPv4 address, or domain labels whose last one starts with a letter, as `URI` requires. */
private fun String.isHostName(): Boolean {
    if (isEmpty()) return false
    val labels = removeSuffix(".").split('.')
    if (labels.any { label ->
            label.isEmpty() || label.first() == '-' || label.last() == '-' ||
                !label.all { it.isAsciiLetterOrDigit() || it == '-' }
        }
    ) return false
    if (labels.size == 4 && labels.all { label -> label.all(Char::isAsciiDigit) && label.toInt() <= 255 }) {
        return true
    }
    return labels.last().first().isAsciiLetter()
}

/** Characters `java.net.URI` accepts after the authority, with escapes that are complete. */
private fun String.isValidPathQueryFragment(): Boolean {
    var fragment = false
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char == '%' -> {
                if (index + 2 >= length || !this[index + 1].isHexDigit() || !this[index + 2].isHexDigit()) return false
                index += 2
            }
            char == '#' -> if (fragment) return false else fragment = true
            char.code >= 0x80 -> if (char.isISOControl()) return false
            !(char.isAsciiLetterOrDigit() || char in "-._~!$&'()*+,;=:@/?") -> return false
        }
        index++
    }
    return true
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || isAsciiDigit()

private fun Char.isHexDigit(): Boolean = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'
