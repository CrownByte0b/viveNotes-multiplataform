package com.vivenotes.desktop

import com.vivenotes.ui.account.AccountProvider
import com.vivenotes.ui.account.AccountSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountCredentialStoreTest {
    private val credential = StoredAccountCredential(
        AccountSession("https://notes.example.com", "owner@example.com", "account-id", "device-id",
            membership = "active", provider = AccountProvider.Google, managed = true),
        "vive_secret",
    )

    @Test
    fun secretServiceUsesStdinAndNeverPlacesBearerInArguments() {
        val calls = mutableListOf<Pair<List<String>, String?>>()
        var secret: String? = null
        val store = SecretServiceAccountStore("vivenotes-dev") { args, input ->
            calls += args to input
            when (args[1]) {
                "store" -> { secret = input; "" }
                "lookup" -> secret
                else -> { secret = null; "" }
            }
        }
        assertTrue(store.save(credential))
        assertEquals(credential.token, store.load()?.token)
        assertEquals(AccountProvider.Google, store.load()?.session?.provider)
        assertTrue(calls.all { (args, _) -> args.none { "vive_secret" in it } })
        assertTrue(calls.first().second!!.contains("vive_secret"))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun unavailableSecretServiceKeepsSessionOnly() {
        val store = SecretServiceAccountStore("vivenotes") { _, _ -> null }
        assertFalse(store.save(credential))
        assertNull(store.load())
    }

    @Test
    fun dpapiStoreWritesOnlyEncryptedPayload() {
        val root = Files.createTempDirectory("account-dpapi").toFile()
        val file = root.resolve("account.dpapi")
        val store = DpapiAccountStore(file) { args, input ->
            if (args.last().contains("Protect(")) "encrypted-data" else if (input == "encrypted-data")
                encodeCredential(credential) else null
        }
        try {
            assertTrue(store.save(credential))
            assertEquals("encrypted-data", file.readText())
            assertEquals(credential.token, store.load()?.token)
            store.clear()
            assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }
}
