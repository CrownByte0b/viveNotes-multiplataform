package com.vivenotes.desktop

import com.vivenotes.ui.account.AccountProvider
import com.vivenotes.ui.account.AccountSession
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class StoredAccountCredential(val session: AccountSession, val token: String)

internal interface AccountCredentialStore {
    fun load(): StoredAccountCredential?
    fun save(credential: StoredAccountCredential): Boolean
    fun clear()
}

/** Linux Secret Service through secret-tool; the bearer is passed on stdin, never in argv. */
internal class SecretServiceAccountStore(
    private val profile: String,
    private val run: (List<String>, String?) -> String? = ::runSecretCommand,
) : AccountCredentialStore {
    private val attributes get() = listOf("service", "vivenotes", "profile", profile)

    override fun load(): StoredAccountCredential? = run(listOf("secret-tool", "lookup") + attributes, null)
        ?.takeIf(String::isNotBlank)?.let(::decodeCredential)

    override fun save(credential: StoredAccountCredential): Boolean =
        run(listOf("secret-tool", "store", "--label=Vive Notes account") + attributes,
            encodeCredential(credential)) != null

    override fun clear() { run(listOf("secret-tool", "clear") + attributes, null) }
}

/** Windows DPAPI encrypts the credential for the current OS user before any file write. */
internal class DpapiAccountStore(
    private val file: File,
    private val run: (List<String>, String?) -> String? = ::runSecretCommand,
) : AccountCredentialStore {
    override fun load(): StoredAccountCredential? {
        val cipher = runCatching { file.readText() }.getOrNull() ?: return null
        return run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", DECRYPT), cipher)
            ?.let(::decodeCredential)
    }

    override fun save(credential: StoredAccountCredential): Boolean {
        val cipher = run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ENCRYPT),
            encodeCredential(credential))?.trim()?.takeIf(String::isNotBlank) ?: return false
        return runCatching {
            file.parentFile.mkdirs()
            val staged = File.createTempFile("credential-", ".tmp", file.parentFile)
            try {
                staged.writeText(cipher)
                Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { staged.delete() }
            true
        }.getOrDefault(false)
    }

    override fun clear() { file.delete() }

    private companion object {
        const val ENCRYPT = "Add-Type -AssemblyName System.Security; " +
            "\$b=[Text.Encoding]::UTF8.GetBytes([Console]::In.ReadToEnd()); " +
            "\$c=[Security.Cryptography.ProtectedData]::Protect(\$b,\$null," +
            "[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
            "[Console]::Out.Write([Convert]::ToBase64String(\$c))"
        const val DECRYPT = "Add-Type -AssemblyName System.Security; " +
            "\$b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); " +
            "\$p=[Security.Cryptography.ProtectedData]::Unprotect(\$b,\$null," +
            "[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
            "[Console]::Out.Write([Text.Encoding]::UTF8.GetString(\$p))"
    }
}

internal fun desktopCredentialStore(profile: DesktopProfile, config: File): AccountCredentialStore? = when {
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ->
        SecretServiceAccountStore(profile.windowClass)
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
        DpapiAccountStore(File(config, "account-credential.dpapi"))
    else -> null
}

private fun runSecretCommand(command: List<String>, input: String?): String? = runCatching {
    val process = ProcessBuilder(command).start()
    process.outputStream.bufferedWriter().use { writer -> if (input != null) writer.write(input) }
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    if (process.exitValue() != 0) return null
    process.inputStream.bufferedReader().use { it.readText() }
}.getOrNull()

internal fun encodeCredential(value: StoredAccountCredential): String = buildJsonObject {
    put("serverUrl", value.session.serverUrl)
    put("email", value.session.email)
    put("accountId", value.session.accountId)
    put("deviceId", value.session.deviceId)
    put("provider", value.session.provider.name)
    put("managed", value.session.managed)
    put("token", value.token)
}.toString()

internal fun decodeCredential(value: String): StoredAccountCredential? = runCatching {
    val json = Json.parseToJsonElement(value) as JsonObject
    fun string(key: String) = json[key]!!.jsonPrimitive.content
    StoredAccountCredential(
        AccountSession(string("serverUrl"), string("email"), string("accountId"), string("deviceId"),
            membership = "unknown", provider = AccountProvider.valueOf(string("provider")),
            managed = json["managed"]!!.jsonPrimitive.boolean, persisted = true),
        string("token"),
    )
}.getOrNull()
