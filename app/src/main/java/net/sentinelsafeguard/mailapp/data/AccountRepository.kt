package net.sentinelsafeguard.mailapp.data

import net.sentinelsafeguard.mailapp.config.MailConfig
import net.sentinelsafeguard.mailapp.data.mail.MailService

/** One configured mailbox: its address, password, and connection. */
data class MailAccount(
    val address: String,
    val password: String,
    val config: MailConfig,
)

/**
 * The account seam. V1 ships ONE active account, done excellently —
 * but every screen and the checker ask THIS object for "the active
 * account" and a ready [MailService], never the stores directly.
 * Multi-account later means: a list behind this same interface, a
 * per-account SecureStore namespace, and an account switcher that
 * changes what [activeAccount] returns. Nothing in the engine or
 * the UI keys on "there is only one", so that work is additive.
 */
class AccountRepository(
    private val secureStore: SecureStore,
) {
    fun activeAccount(): MailAccount? {
        val address = secureStore.accountAddress?.takeIf { it.isNotBlank() } ?: return null
        val password = secureStore.mailPassword?.takeIf { it.isNotBlank() } ?: return null
        val host = secureStore.serverHost?.takeIf { it.isNotBlank() }
            ?: MailConfig.production().host
        val base = MailConfig.production(host)
        val config = base.copy(
            imapPort = secureStore.imapPort.takeIf { it > 0 } ?: base.imapPort,
            smtpPort = secureStore.smtpPort.takeIf { it > 0 } ?: base.smtpPort,
        )
        return MailAccount(address = address, password = password, config = config)
    }

    fun serviceFor(account: MailAccount): MailService = MailService(account.config)

    fun saveAccount(
        address: String,
        password: String,
        host: String?,
        imapPort: Int?,
        smtpPort: Int?,
    ) {
        secureStore.accountAddress = address
        secureStore.mailPassword = password
        secureStore.serverHost = host?.takeIf { it.isNotBlank() }
        secureStore.imapPort = imapPort ?: 0
        secureStore.smtpPort = smtpPort ?: 0
    }

    fun signOut() {
        secureStore.clearAll()
    }
}
