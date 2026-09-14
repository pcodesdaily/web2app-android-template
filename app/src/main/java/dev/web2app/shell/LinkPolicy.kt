package dev.web2app.shell

/**
 * Decides what happens to a URL the WebView is about to load.
 *
 * Pure functions over strings, deliberately free of android.net.Uri, so the rules
 * are unit-testable on the JVM. This is a trust boundary — an allowlist mistake
 * here means the app renders someone else's page inside your branding — so it is
 * one of the places ponytail's "never simplify away input validation" applies.
 */
object LinkPolicy {

    enum class Decision { OPEN_INTERNAL, OPEN_EXTERNAL, BLOCK }

    /**
     * @param host          host of the URL being loaded, lowercased, no port
     * @param scheme        scheme of the URL being loaded, lowercased
     * @param startHost     host of the configured start URL — the implicit allowlist
     *                      when internalHosts is empty
     * @param internalHosts extra hosts kept inside the WebView; a leading "." or "*."
     *                      matches subdomains
     * @param blocked       substring patterns checked against the full URL; blocking
     *                      is evaluated first and always wins
     */
    fun decide(
        url: String,
        host: String?,
        scheme: String?,
        startHost: String?,
        internalHosts: List<String>,
        blocked: List<String>,
        customSchemes: List<String>,
        externalPolicy: String,
    ): Decision {
        if (blocked.any { it.isNotEmpty() && url.contains(it, ignoreCase = true) }) return Decision.BLOCK

        // A custom scheme is an explicit handoff to another app, never a page load.
        if (scheme != null && scheme in customSchemes) return Decision.OPEN_EXTERNAL

        // Non-web schemes (mailto:, tel:, intent:) always leave the WebView.
        if (scheme != null && scheme != "http" && scheme != "https") return Decision.OPEN_EXTERNAL

        if (host != null && isInternal(host, startHost, internalHosts)) return Decision.OPEN_INTERNAL

        return when (externalPolicy) {
            "block" -> Decision.BLOCK
            "webview" -> Decision.OPEN_INTERNAL
            else -> Decision.OPEN_EXTERNAL
        }
    }

    internal fun isInternal(host: String, startHost: String?, internalHosts: List<String>): Boolean {
        val allow = buildList {
            startHost?.takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(internalHosts)
        }
        return allow.any { matches(host, it) }
    }

    /** "example.com" matches only that host; ".example.com" / "*.example.com" match subdomains. */
    private fun matches(host: String, pattern: String): Boolean {
        val p = pattern.lowercase().trim()
        if (p.isEmpty()) return false
        val wildcard = p.startsWith("*.") || p.startsWith(".")
        if (!wildcard) return host == p
        val bare = p.removePrefix("*").removePrefix(".")
        // endsWith(".$bare") prevents "notexample.com" matching "*.example.com".
        return host == bare || host.endsWith(".$bare")
    }
}
