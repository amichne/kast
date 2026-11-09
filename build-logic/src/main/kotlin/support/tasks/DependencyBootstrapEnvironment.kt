package support.tasks

/** Dependency provisioning keeps approved transport settings without inheriting unrelated credentials. */
object DependencyBootstrapEnvironment {
    private val names = listOf(
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
        "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "NODE_EXTRA_CA_CERTS",
    )

    fun network(read: (String) -> String?): Map<String, String> = buildMap {
        for (name in names) {
            val value = read(name)
            if (!value.isNullOrEmpty()) put(name, value)
        }
    }
}
