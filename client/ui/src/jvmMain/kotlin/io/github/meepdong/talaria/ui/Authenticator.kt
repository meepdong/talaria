package io.github.meepdong.talaria.ui

/** The device owner's fingerprint or screen lock (Android); [done] hears whether they passed. */
fun interface Authenticator {
    fun authenticate(reason: String, done: (Boolean) -> Unit)
}

/**
 * When terminal approvals need the owner's fingerprint or screen lock (spec §16.1, owner 2026-10-05): once unlocked it
 * stays so while the app is open, and for [GRACE_MS] after it was left; after that, unlock again.
 */
class TerminalLock(private val nowMs: () -> Long) {
    private var unlockedAt: Long? = null
    private var leftAt: Long? = null

    val unlocked: Boolean
        @Synchronized get() {
            val u = unlockedAt ?: return false
            val left = leftAt ?: return true
            return nowMs() - left < GRACE_MS && u <= left
        }

    @Synchronized fun unlock() {
        unlockedAt = nowMs()
        leftAt = null
    }

    @Synchronized fun foreground(visible: Boolean) {
        if (!visible) {
            leftAt = nowMs()
        } else {
            val left = leftAt
            if (left != null && nowMs() - left >= GRACE_MS) unlockedAt = null
            leftAt = null
        }
    }

    companion object {
        const val GRACE_MS = 5 * 60 * 1000L
    }
}
