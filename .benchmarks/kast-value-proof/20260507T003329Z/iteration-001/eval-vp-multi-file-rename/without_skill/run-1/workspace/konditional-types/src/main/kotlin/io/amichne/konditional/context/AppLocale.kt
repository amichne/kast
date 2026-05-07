package io.amichne.konditional.context

/**
 * Locale and Market enums (examples only)
 *
 * This abstracts the concept of a locale and market for the application, and removes the need for ISO language-country codes.
 */
enum class AppLocale(
    private val language: String,
    private val country: String,
) : LocaleTag {
    AUSTRALIA("en", "AU"),
    AUSTRIA("de", "AT"),
    BELGIUM_DUTCH("nl", "BE"),
    BELGIUM_FRENCH("fr", "BE"),
    CANADA("en", "CA"),
    CANADA_FRENCH("fr", "CA"),
    FINLAND("fi", "FI"),
    FRANCE("fr", "FR"),
    GERMANY("de", "DE"),
    HONG_KONG("ch", "HK"),
    HONG_KONG_ENGLISH("en", "HK"),
    INDIA("en", "IN"),
    ITALY("it", "IT"),
    JAPAN("ja", "JP"),
    MEXICO("es", "MX"),
    NETHERLANDS("nl", "NL"),
    NEW_ZEALAND("en", "NZ"),
    NORWAY("no", "NO"),
    SINGAPORE("en", "SG"),
    SPAIN("es", "ES"),
    SWEDEN("sv", "SE"),
    TAIWAN("zh", "TW"),
    UNITED_KINGDOM("en", "GB"),
    UNITED_STATES("en", "US"),
    ICC_EN_EU("en", "EU"),
    ICC_EN_EI("en", "EI");

    override val id: String = name

    companion object {
        fun from(
            language: String,
            country: String,
        ): AppLocale =
            entries.single { it.language == language && it.country == country }
    }
}
