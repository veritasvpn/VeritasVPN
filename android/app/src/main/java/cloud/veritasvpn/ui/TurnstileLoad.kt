package cloud.veritasvpn.ui

/**
 * Hosted Turnstile page. The WebView loads this URL itself; nothing in the
 * APK is the challenge document.
 */
object TurnstileLoad {
    const val PAGE = "https://veritasvpn.cloud/turnstile-mobile-v2"
    const val ATTEMPTS = 4
    const val RETRY_DELAY_MS = 500L
    const val UNAVAILABLE = "Security check couldn't be reached. Please try again."
    const val BLANK_HTML =
        "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
            "<body style=\"margin:0;background:#06101c\"></body></html>"

    /** [failures] is how many main-frame loads have already failed. */
    fun retry(failures: Int): Boolean = failures in 1 until ATTEMPTS
}
