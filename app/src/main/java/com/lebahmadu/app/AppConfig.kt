package com.lebahmadu.app

/** Edit these values before building. */
object AppConfig {
    const val CONFIG_URL = "https://raw.githubusercontent.com/username/repo/main/config.json"
    const val WEB_URL = "https://username.github.io/repo/index.html"

    const val DNS_HOST = "all.dns.mullvad.net"
    const val DOH_URL = "https://$DNS_HOST/dns-query"

    /**
     * Bootstrap addresses for DNS_HOST, so the app never needs DNS to find its DNS server.
     * Verify against https://mullvad.net/en/help/dns-over-https-and-dns-over-tls
     */
    val DNS_BOOTSTRAP_IPS = listOf("194.242.2.9", "2a07:e340::9")
}
