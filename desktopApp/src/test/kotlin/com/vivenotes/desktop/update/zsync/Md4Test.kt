package com.vivenotes.desktop.update.zsync

import kotlin.test.Test
import kotlin.test.assertEquals

/** MD4 against RFC 1320's test suite, whole and fed in pieces that cross its 64-byte blocks. */
class Md4Test {
    private val suite = mapOf(
        "" to "31d6cfe0d16ae931b73c59d7e0c089c0",
        "a" to "bde52cb31de33e46245e05fbdbd6fb24",
        "abc" to "a448017aaf21d8525fc10ae87aa6729d",
        "message digest" to "d9130a8164549fe818874806e1c7014b",
        "abcdefghijklmnopqrstuvwxyz" to "d79e1c308aa5bbcdeea8ed63df412da9",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to "043f8582f241db351ce627e153e7f0e4",
        "1234567890".repeat(8) to "e33b4ddc9c38f2199c3e7b164fcc0536",
    )

    @Test
    fun rfc1320TestSuite() {
        val md4 = Md4()
        for ((message, digest) in suite) {
            md4.update(message.toByteArray())
            assertEquals(digest, ZsyncFixtures.hex(md4.digest()), "MD4(\"$message\")")
        }
    }

    @Test
    fun piecesGiveTheSameDigestAsTheWhole() {
        val data = ZsyncFixtures.xorshift(9, 1_000)
        val whole = Md4().apply { update(data) }.digest()
        for (piece in listOf(1, 7, 55, 56, 63, 64, 65, 129)) {
            val md4 = Md4()
            data.toList().chunked(piece).forEach { md4.update(it.toByteArray()) }
            assertEquals(ZsyncFixtures.hex(whole), ZsyncFixtures.hex(md4.digest()), "pieces of $piece")
        }
        // A digest starts the next one afresh.
        val reused = Md4().apply { update("abc".toByteArray()); digest() }
        reused.update("abc".toByteArray())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", ZsyncFixtures.hex(reused.digest()))
    }
}
