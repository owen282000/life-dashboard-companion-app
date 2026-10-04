package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalTime

class PreferencesManager(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Keystore-backed storage for secrets (webhook headers with auth tokens, HMAC secrets,
     * MQTT credentials): AES-256-GCM with a key the Android Keystore holds, one store for the
     * whole process ([SecretVault]), which moved the secrets out of security-crypto's file the
     * first time it opened.
     *
     * When the Keystore cannot be used it is [InMemoryPrefs]: reads return nothing and writes
     * are dropped, so a sync then fails loudly (missing auth) rather than quietly downgrading
     * the user's security by keeping a secret in plain storage.
     */
    private val vault: SecretVault.Opened = SecretVault.open(context)
    private val securePrefs: SharedPreferences = vault.store

    /**
     * True when encrypted storage could not be opened, so secrets cannot be read or saved in
     * this process. Transient: the next start tries again.
     */
    val secretsUnavailable: Boolean get() = vault.state == SecretState.UNAVAILABLE

    /**
     * True when saved secrets were lost (the Keystore key is gone, or the old store stayed
     * unreadable) and have to be entered again. Saving any secret clears it.
     */
    val secretsNeedReentry: Boolean get() = vault.needsReentry

    /** Removes every saved secret; for tests that start from a clean state. */
    fun clearAllSecrets() {
        securePrefs.edit().clear().commit()
    }

    private val logStore = WebhookLogStore(context)

    /**
     * The Receive ledger and the acks still to send (issue #62), in their own file so they are
     * excluded from backup like the watermarks: on a new phone the integration offers what was
     * not acknowledged again, and the upsert makes that harmless.
     */
    private val writeBackPrefs: SharedPreferences = context.getSharedPreferences(WRITEBACK_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        logStore.migrateFromLegacyPrefs(prefs)
    }

    // ==================== MQTT ====================
    // One shared broker connection (the original mqtt_* keys, so existing setups keep working)
    // that both sections use by default; each section has its own switch, base topic and, when
    // it opts out of the shared connection, its own broker (issue #52).

    fun getSharedMqttBroker(): MqttBroker = readBroker(SHARED_MQTT_PREFIX)

    fun setSharedMqttBroker(broker: MqttBroker) = writeBroker(SHARED_MQTT_PREFIX, broker)

    fun getMqttSection(section: MqttSection): MqttSectionSettings = MqttSectionSettings(
        enabled = prefs.getBoolean(section.enabledKey, false),
        useSharedBroker = prefs.getBoolean(section.prefix + "use_shared", true),
        ownBroker = readBroker(section.prefix),
        baseTopic = prefs.getString(section.baseTopicKey, MqttSupport.DEFAULT_BASE_TOPIC)
            ?.takeIf { it.isNotBlank() } ?: MqttSupport.DEFAULT_BASE_TOPIC
    )

    fun setMqttSection(section: MqttSection, settings: MqttSectionSettings) {
        prefs.edit()
            .putBoolean(section.enabledKey, settings.enabled)
            .putBoolean(section.prefix + "use_shared", settings.useSharedBroker)
            .putString(section.baseTopicKey, settings.baseTopic.trim())
            .apply()
        writeBroker(section.prefix, settings.ownBroker)
    }

    /** What the publisher of [section] connects to: its own switch and topic plus the broker it uses. */
    fun resolvedMqttSettings(section: MqttSection): MqttSettings {
        val settings = getMqttSection(section)
        val broker = if (settings.useSharedBroker) getSharedMqttBroker() else settings.ownBroker
        return MqttSettings(
            enabled = settings.enabled,
            host = broker.host,
            port = broker.port,
            useTls = broker.useTls,
            username = broker.username,
            password = broker.password,
            baseTopic = settings.baseTopic
        )
    }

    fun getMqttSensorCache(section: MqttSection): String? = prefs.getString(section.prefix + "sensor_cache", null)

    /** The phone slug this section last published under; null means nameless, which is also what an install from before names did. */
    fun getMqttPublishedSlug(section: MqttSection): String? = prefs.getString(section.prefix + "published_slug", null)?.takeIf { it.isNotEmpty() }

    fun setMqttPublishedSlug(section: MqttSection, slug: String?) = prefs.edit { putString(section.prefix + "published_slug", slug.orEmpty()) }

    fun setMqttSensorCache(section: MqttSection, json: String) {
        prefs.edit().putString(section.prefix + "sensor_cache", json).apply()
    }

    fun getLastMqttStatus(section: MqttSection): String? = prefs.getString(section.statusKey, null)

    fun setLastMqttStatus(section: MqttSection, status: String) {
        prefs.edit().putString(section.statusKey, status).apply()
    }

    private fun readBroker(prefix: String): MqttBroker = MqttBroker(
        host = prefs.getString(prefix + "host", "") ?: "",
        port = prefs.getInt(prefix + "port", 1883),
        useTls = prefs.getBoolean(prefix + "tls", false),
        username = securePrefs.getString(prefix + "username", null)?.takeIf { it.isNotBlank() },
        password = securePrefs.getString(prefix + "password", null)?.takeIf { it.isNotBlank() }
    )

    private fun writeBroker(prefix: String, broker: MqttBroker) {
        prefs.edit()
            .putString(prefix + "host", broker.host.trim())
            .putInt(prefix + "port", broker.port)
            .putBoolean(prefix + "tls", broker.useTls)
            .apply()
        securePrefs.edit()
            .putString(prefix + "username", broker.username ?: "")
            .putString(prefix + "password", broker.password ?: "")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "life_dashboard_prefs"

        /** The plain settings file; [SecretVault] reads the secrets of versions before 1.6.0 from it. */
        const val PREFS_FILE = PREFS_NAME

        /** The secrets versions before 1.6.0 kept in the plain settings, moved into the store on migration. */
        val LEGACY_PLAIN_SECRET_KEYS: List<String>
            get() = listOf(KEY_HEALTH_WEBHOOK_HEADERS, KEY_SCREENTIME_WEBHOOK_HEADERS, KEY_HEALTH_WEBHOOK_SECRET, KEY_SCREENTIME_WEBHOOK_SECRET)

        private const val WRITEBACK_PREFS_NAME = "life_dashboard_writeback"

        // Receive (write-back from Home Assistant, issue #62)
        private const val KEY_RECEIVE_ENABLED = "receive_enabled"
        private const val KEY_RECEIVE_TYPES = "receive_types"
        private const val KEY_RECEIVE_OLDER = "receive_older_measurements"
        private const val KEY_RECEIVE_SOURCE_URL = "receive_source_url"
        private const val KEY_RECEIVE_CONFIGURED = "receive_configured_types"
        private const val KEY_RECEIVE_OUTDATED = "receive_integration_outdated"
        private const val KEY_WRITEBACK_LEDGER = "ledger"
        private const val KEY_WRITEBACK_REPORT = "report"

        private const val KEY_INCLUDE_DAILY_TOTALS = "include_daily_totals"
        private const val KEY_INCLUDE_RECORD_METADATA = "include_record_metadata"
        private const val KEY_ALLOW_HTTP_WEBHOOKS = "allow_http_webhooks"
        private const val KEY_CLIENT_CERT_ALIAS = "client_cert_alias"

        /** The phone's name for MQTT, shared by both sections; empty means the topics stay as they always were. */
        private const val KEY_PHONE_NAME = "phone_name"

        /** Shared MQTT broker keys: mqtt_host, mqtt_port, mqtt_tls, mqtt_username, mqtt_password (securePrefs). */
        private const val SHARED_MQTT_PREFIX = "mqtt_"

        // Health Connect keys
        private const val KEY_HEALTH_LAST_SYNC_TS_PREFIX = "health_last_sync_ts_"
        private const val KEY_HEALTH_LAST_SYNC_TIE_PREFIX = "health_last_sync_tie_"

        /** Per type, the last moment a sync read all of it, see [LookbackWindow]. */
        private const val KEY_HEALTH_COVERED_UNTIL_PREFIX = "health_covered_until_"
        private const val KEY_HEALTH_SYNC_INTERVAL_MINUTES = "health_sync_interval_minutes"

        /**
         * Health Connect changes tokens, per type, with the time each was issued so an expired
         * one can be recognised without spending a call (see [DeletionTracking]).
         */
        private const val KEY_HEALTH_CHANGES_TOKEN_PREFIX = "health_changes_token_"
        private const val KEY_HEALTH_CHANGES_TOKEN_TS_PREFIX = "health_changes_token_ts_"

        /** Monotonic counter stamped on every payload, so a retried older payload is recognisable. */
        private const val KEY_HEALTH_SYNC_SEQUENCE = "health_sync_sequence"

        /** Guards the sequence counter's read-modify-write against overlapping syncs. */
        private val SEQUENCE_LOCK = Any()

        /** Deletions read from the changes feed but not yet carried by a payload. */
        private const val KEY_HEALTH_PENDING_DELETIONS = "health_pending_deletions"

        /**
         * Schedule keys, per source. The interval keeps its own long-standing key above; mode,
         * times, days and the quiet window are suffixes on these prefixes, so both sources
         * share one implementation.
         */
        private const val HEALTH_SCHEDULE_PREFIX = "health_schedule_"
        private const val SCREENTIME_SCHEDULE_PREFIX = "screentime_schedule_"
        private const val KEY_HEALTH_WEBHOOK_URLS = "health_webhook_urls"
        private const val KEY_HEALTH_ENABLED_DATA_TYPES = "health_enabled_data_types"
        private const val KEY_HEALTH_SERIES_RESOLUTIONS = "health_series_resolutions"
        private const val KEY_HEALTH_BUCKET_CARRY = "health_bucket_carry"

        // Screen Time keys
        private const val KEY_SCREENTIME_LAST_SYNC_TS = "screentime_last_sync_ts"
        private const val KEY_SCREENTIME_SYNC_INTERVAL_MINUTES = "screentime_sync_interval_minutes"
        private const val KEY_SCREENTIME_WEBHOOK_URLS = "screentime_webhook_urls"
        private const val KEY_SCREENTIME_DAY_BOUNDARY_HOUR = "screentime_day_boundary_hour"
        private const val KEY_SCREENTIME_USE_DAY_BOUNDARY = "screentime_use_day_boundary"
        private const val KEY_SCREENTIME_APP_FILTER_MODE = "screentime_app_filter_mode"
        private const val KEY_SCREENTIME_APP_FILTER_PACKAGES = "screentime_app_filter_packages"

        // Webhook header keys
        private const val KEY_HEALTH_WEBHOOK_HEADERS = "health_webhook_headers"
        private const val KEY_SCREENTIME_WEBHOOK_HEADERS = "screentime_webhook_headers"
        private const val KEY_HEALTH_WEBHOOK_SECRET = "health_webhook_secret"
        private const val KEY_SCREENTIME_WEBHOOK_SECRET = "screentime_webhook_secret"

        // URLs that get no custom headers (the ones QR pairing added); not secret themselves
        private const val KEY_HEALTH_URLS_WITHOUT_HEADERS = "health_webhook_urls_without_headers"
        private const val KEY_SCREENTIME_URLS_WITHOUT_HEADERS = "screentime_webhook_urls_without_headers"

        // Shared keys
        private const val KEY_KEEP_FULL_PAYLOADS = "keep_full_payloads"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        // Defaults
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 60
        private const val DEFAULT_DAY_BOUNDARY_HOUR = 4
    }

    // ==================== Health Connect Settings ====================

    fun getHealthSyncIntervalMinutes(): Int {
        return prefs.getInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
    }

    fun setHealthSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

    fun getHealthWebhookUrls(): List<String> {
        val urlsString = prefs.getString(KEY_HEALTH_WEBHOOK_URLS, "") ?: ""
        return if (urlsString.isEmpty()) emptyList() else urlsString.split(",")
    }

    fun setHealthWebhookUrls(urls: List<String>) {
        val urlsString = urls.joinToString(",")
        prefs.edit().putString(KEY_HEALTH_WEBHOOK_URLS, urlsString).apply()
        // The source URL of Receive must stay one of the section's URLs; removing it forgets
        // the choice, and with it the ledger that belonged to that receiver.
        val source = getReceiveSourceUrl()
        if (source != null && source !in urls) setReceiveSourceUrl(null)
    }

    fun getHealthEnabledDataTypes(): Set<HealthDataType> {
        val typesString = prefs.getString(KEY_HEALTH_ENABLED_DATA_TYPES, "") ?: ""
        return if (typesString.isEmpty()) {
            emptySet()
        } else {
            typesString.split(",").mapNotNull {
                try { HealthDataType.valueOf(it) } catch (e: Exception) { null }
            }.toSet()
        }
    }

    fun setHealthEnabledDataTypes(types: Set<HealthDataType>) {
        val typesString = types.joinToString(",") { it.name }
        prefs.edit().putString(KEY_HEALTH_ENABLED_DATA_TYPES, typesString).apply()
    }

    /**
     * Resolution per data type, stored as "TYPE=RESOLUTION" pairs. Types that are absent, and
     * anything unparseable, fall back to [DEFAULT_RESOLUTION], so a partly written or older
     * value degrades to raw records rather than to silently averaged ones.
     */
    fun getSeriesResolutions(): Map<HealthDataType, SeriesResolution> {
        val stored = prefs.getString(KEY_HEALTH_SERIES_RESOLUTIONS, "") ?: ""
        if (stored.isEmpty()) return emptyMap()
        return stored.split(",").mapNotNull { pair ->
            val (typeName, resolutionName) = pair.split("=").let {
                if (it.size == 2) it[0] to it[1] else return@mapNotNull null
            }
            val type = runCatching { HealthDataType.valueOf(typeName) }.getOrNull() ?: return@mapNotNull null
            type to SeriesResolution.from(resolutionName)
        }.toMap()
    }

    fun getSeriesResolution(type: HealthDataType): SeriesResolution =
        getSeriesResolutions()[type] ?: DEFAULT_RESOLUTION

    fun setSeriesResolutions(resolutions: Map<HealthDataType, SeriesResolution>) {
        // Only what differs from the default is written, so the stored value stays small and
        // a future change of default reaches everyone who never touched the setting.
        val stored = resolutions.entries
            .filter { it.value != DEFAULT_RESOLUTION }
            .sortedBy { it.key.name }
            .joinToString(",") { "${it.key.name}=${it.value.name}" }
        prefs.edit().putString(KEY_HEALTH_SERIES_RESOLUTIONS, stored).apply()
    }

    /**
     * Samples of bucketed windows that were still open at the end of the last sync, per type.
     * They are bucketed together with the next sync's records so a window is sent once,
     * complete, instead of as two halves. Anything unreadable is treated as nothing held.
     */
    fun getBucketCarry(): Map<HealthDataType, List<CarriedSample>> {
        val stored = prefs.getString(KEY_HEALTH_BUCKET_CARRY, null) ?: return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, List<CarriedSample>>>(stored)
                .mapNotNull { (name, samples) ->
                    runCatching { HealthDataType.valueOf(name) }.getOrNull()?.let { it to samples }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    fun setBucketCarry(carry: Map<HealthDataType, List<CarriedSample>>, durable: Boolean = false) {
        val nonEmpty = carry.filterValues { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            prefs.edit().remove(KEY_HEALTH_BUCKET_CARRY).save(durable)
            return
        }
        prefs.edit().putString(KEY_HEALTH_BUCKET_CARRY, Json.encodeToString(nonEmpty.mapKeys { it.key.name })).save(durable)
    }

    fun getHealthLastSyncTimestamp(type: HealthDataType): Long? {
        val timestamp = prefs.getLong(KEY_HEALTH_LAST_SYNC_TS_PREFIX + type.name, -1)
        return if (timestamp == -1L) null else timestamp
    }

    /** How far [type] was read, see [Watermark]; null before its first sync. */
    fun getHealthWatermark(type: HealthDataType): Watermark? {
        val time = getHealthLastSyncTimestamp(type) ?: return null
        return Watermark(java.time.Instant.ofEpochMilli(time), prefs.getString(KEY_HEALTH_LAST_SYNC_TIE_PREFIX + type.name, null))
    }

    /** Stores the time and the id together, so a watermark is never half old and half new. */
    fun setHealthWatermark(type: HealthDataType, watermark: Watermark, durable: Boolean = false) {
        prefs.edit().apply {
            putLong(KEY_HEALTH_LAST_SYNC_TS_PREFIX + type.name, watermark.time.toEpochMilli())
            if (watermark.tieId == null) remove(KEY_HEALTH_LAST_SYNC_TIE_PREFIX + type.name)
            else putString(KEY_HEALTH_LAST_SYNC_TIE_PREFIX + type.name, watermark.tieId)
        }.save(durable)
    }

    /** The last moment a sync read all of [type], or null when none has yet, as after an update from an older version. */
    fun getHealthCoveredUntil(type: HealthDataType): java.time.Instant? {
        val ms = prefs.getLong(KEY_HEALTH_COVERED_UNTIL_PREFIX + type.name, -1)
        return if (ms == -1L) null else java.time.Instant.ofEpochMilli(ms)
    }

    fun setHealthCoveredUntil(type: HealthDataType, until: java.time.Instant, durable: Boolean = false) {
        prefs.edit().putLong(KEY_HEALTH_COVERED_UNTIL_PREFIX + type.name, until.toEpochMilli()).save(durable)
    }

    /** The stored changes token for [type], or null when there is none yet. */
    fun getHealthChangesToken(type: HealthDataType): String? =
        prefs.getString(KEY_HEALTH_CHANGES_TOKEN_PREFIX + type.name, null)

    /** When the stored token was issued, in epoch milliseconds, or null when there is none. */
    fun getHealthChangesTokenIssuedAt(type: HealthDataType): Long? {
        val ts = prefs.getLong(KEY_HEALTH_CHANGES_TOKEN_TS_PREFIX + type.name, -1)
        return if (ts == -1L) null else ts
    }

    /**
     * Stores a changes token and the moment it was issued. Passing null for [token] forgets the
     * type's token, which is what happens when Health Connect refuses an expired one.
     */
    fun setHealthChangesToken(type: HealthDataType, token: String?, issuedAtMs: Long) {
        prefs.edit().apply {
            if (token == null) {
                remove(KEY_HEALTH_CHANGES_TOKEN_PREFIX + type.name)
                remove(KEY_HEALTH_CHANGES_TOKEN_TS_PREFIX + type.name)
            } else {
                putString(KEY_HEALTH_CHANGES_TOKEN_PREFIX + type.name, token)
                putLong(KEY_HEALTH_CHANGES_TOKEN_TS_PREFIX + type.name, issuedAtMs)
            }
        }.apply()
    }

    /**
     * The next payload sequence number, incremented on every call. A receiver that keeps the
     * highest sequence it has seen can ignore a retry that arrives after a newer payload.
     *
     * Screen Time payloads take their number here too, so it goes up across everything the
     * install sends. Per source they still rise in the order the payloads were made, with gaps,
     * so a receiver keeps its highest number per source: one per install would take a Screen
     * Time week that the outbox held back behind a newer Health Connect payload for a stale one.
     *
     * A manual sync from the UI can run while a scheduled one is in flight, and each holds its
     * own PreferencesManager, so the read-modify-write is guarded by a lock on the class and
     * committed synchronously: two payloads sharing a number would be exactly the ambiguity the
     * number exists to remove, and a number handed out but lost to a process kill would repeat.
     */
    fun nextHealthSyncSequence(): Long = synchronized(SEQUENCE_LOCK) {
        val next = prefs.getLong(KEY_HEALTH_SYNC_SEQUENCE, 0L) + 1
        prefs.edit().putLong(KEY_HEALTH_SYNC_SEQUENCE, next).commit()
        next
    }

    /**
     * Deletions read from Health Connect that have not been handed to a payload yet.
     *
     * Reading the changes feed consumes it, so these cannot be read again: they are kept here
     * until a payload carries them, and only then cleared (issue #61).
     */
    fun getPendingDeletions(): DeletionSummary {
        val stored = prefs.getString(KEY_HEALTH_PENDING_DELETIONS, null) ?: return DeletionSummary.EMPTY
        return runCatching { Json.decodeFromString<DeletionSummary>(stored) }
            .getOrDefault(DeletionSummary.EMPTY)
    }

    fun setPendingDeletions(summary: DeletionSummary, durable: Boolean = false) {
        if (summary.isEmpty) {
            prefs.edit().remove(KEY_HEALTH_PENDING_DELETIONS).save(durable)
            return
        }
        prefs.edit().putString(KEY_HEALTH_PENDING_DELETIONS, Json.encodeToString(summary)).save(durable)
    }

    /**
     * [durable] writes to disk before it returns. A sync's write-ahead commit needs that: with
     * apply() a process killed just after a delivery can lose the moved watermarks and bucket
     * carry, and the next sync would send bucketed windows, which carry no uuid, a second time.
     * Never on the main thread; the syncs run on Dispatchers.IO.
     */
    private fun android.content.SharedPreferences.Editor.save(durable: Boolean) {
        if (durable) commit() else apply()
    }

    fun getHealthWebhookHeaders(): Map<String, String> {
        val headersJson = securePrefs.getString(KEY_HEALTH_WEBHOOK_HEADERS, null) ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(headersJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setHealthWebhookHeaders(headers: Map<String, String>) {
        val headersJson = Json.encodeToString(headers)
        securePrefs.edit().putString(KEY_HEALTH_WEBHOOK_HEADERS, headersJson).apply()
    }

    /** Health URLs that get none of the custom headers, see [WebhookSupport.headersFor]. */
    fun getHealthUrlsWithoutHeaders(): Set<String> = urlSet(KEY_HEALTH_URLS_WITHOUT_HEADERS)

    fun setHealthUrlsWithoutHeaders(urls: Set<String>) = putUrlSet(KEY_HEALTH_URLS_WITHOUT_HEADERS, urls)

    private fun urlSet(key: String): Set<String> {
        val stored = prefs.getString(key, null) ?: return emptySet()
        return runCatching { Json.decodeFromString<List<String>>(stored).toSet() }.getOrDefault(emptySet())
    }

    private fun putUrlSet(key: String, urls: Set<String>) {
        if (urls.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putString(key, Json.encodeToString(urls.toList())).apply()
    }

    /** Daily deduplicated totals in the payload (aggregate API merges phone + watch). */
    fun includeDailyTotals(): Boolean = prefs.getBoolean(KEY_INCLUDE_DAILY_TOTALS, true)

    /** Record metadata on every record of a Health Connect payload (P2-7); off by default. */
    fun includeRecordMetadata(): Boolean = prefs.getBoolean(KEY_INCLUDE_RECORD_METADATA, false)

    fun setIncludeRecordMetadata(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_RECORD_METADATA, enabled).apply()
    }

    /**
     * Plain http:// webhook URLs are refused unless the user opts in, for endpoints only
     * reachable over a private LAN or VPN (issue #51). Applies to both webhook sections.
     */
    fun allowHttpWebhooks(): Boolean = prefs.getBoolean(KEY_ALLOW_HTTP_WEBHOOKS, false)

    fun setAllowHttpWebhooks(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_HTTP_WEBHOOKS, enabled).apply()
    }

    /**
     * KeyChain alias of the client certificate presented to webhooks (mTLS), or null for none.
     * Device specific, so it is not part of the config backup.
     */
    fun clientCertAlias(): String? = prefs.getString(KEY_CLIENT_CERT_ALIAS, null)

    fun setClientCertAlias(alias: String?) {
        prefs.edit().putString(KEY_CLIENT_CERT_ALIAS, alias).apply()
    }

    fun setIncludeDailyTotals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_DAILY_TOTALS, enabled).apply()
    }

    /**
     * The phone's name, which becomes part of the MQTT device id and topics so two phones can
     * share one broker. Null when the user never set one: everything then publishes exactly as
     * it did before the setting existed.
     */
    fun getPhoneName(): String? = prefs.getString(KEY_PHONE_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setPhoneName(name: String?) {
        val trimmed = name?.trim().orEmpty()
        prefs.edit { if (trimmed.isEmpty()) remove(KEY_PHONE_NAME) else putString(KEY_PHONE_NAME, trimmed) }
    }

    fun getHealthWebhookSecret(): String? {
        return securePrefs.getString(KEY_HEALTH_WEBHOOK_SECRET, null)?.takeIf { it.isNotBlank() }
    }

    fun setHealthWebhookSecret(secret: String?) {
        val previous = getHealthWebhookSecret()
        if (secret.isNullOrBlank()) {
            securePrefs.edit().remove(KEY_HEALTH_WEBHOOK_SECRET).apply()
        } else {
            securePrefs.edit().putString(KEY_HEALTH_WEBHOOK_SECRET, secret).apply()
        }
        // A new secret, whether typed or paired, means a new counterpart for Receive: the
        // ledger and the acks belonged to the old one (protocol section 6).
        if (previous != null && previous != secret?.takeIf { it.isNotBlank() }) clearWriteBackState()
    }

    // ==================== Receive (write-back from Home Assistant, issue #62) ====================

    fun getReceiveSettings(): ReceiveSettings = ReceiveSettings(
        enabled = prefs.getBoolean(KEY_RECEIVE_ENABLED, false),
        types = (prefs.getString(KEY_RECEIVE_TYPES, "") ?: "")
            .split(",")
            .mapNotNull { ConfigBackupManager.writeBackTypeFrom(it) }
            .toSet(),
        olderMeasurements = prefs.getBoolean(KEY_RECEIVE_OLDER, false),
        sourceUrl = getReceiveSourceUrl()
    )

    fun setReceiveEnabled(enabled: Boolean) = prefs.edit {
        putBoolean(KEY_RECEIVE_ENABLED, enabled)
        // The notice about an outdated integration was about the last answer read; switching
        // off means no answer is read, so it must not sit there waiting for the next switch-on.
        if (!enabled) putBoolean(KEY_RECEIVE_OUTDATED, false)
    }

    fun setReceiveTypes(types: Set<WriteBackType>) =
        prefs.edit { putString(KEY_RECEIVE_TYPES, WriteBackType.entries.filter { it in types }.joinToString(",") { it.key }) }

    fun setReceiveOlderMeasurements(enabled: Boolean) = prefs.edit { putBoolean(KEY_RECEIVE_OLDER, enabled) }

    fun getReceiveSourceUrl(): String? = prefs.getString(KEY_RECEIVE_SOURCE_URL, null)?.takeIf { it.isNotBlank() }

    /** Choosing another source URL, or none, starts Receive's bookkeeping afresh (protocol section 6). */
    fun setReceiveSourceUrl(url: String?) {
        val previous = getReceiveSourceUrl()
        val changed = previous != url?.takeIf { it.isNotBlank() }
        prefs.edit {
            if (url.isNullOrBlank()) remove(KEY_RECEIVE_SOURCE_URL) else putString(KEY_RECEIVE_SOURCE_URL, url)
            // What the old source answered says nothing about a new one; the same source
            // switched off and on again still offers the types it named.
            if (changed) {
                putBoolean(KEY_RECEIVE_OUTDATED, false)
                remove(KEY_RECEIVE_CONFIGURED)
            }
        }
        if (changed) clearWriteBackState()
    }

    fun getReceiveStatus(): ReceiveStatus = ReceiveStatus(
        configured = (prefs.getString(KEY_RECEIVE_CONFIGURED, "") ?: "").split(",").filter { it.isNotBlank() },
        // The key is written by every answer the integration gives, even an empty one, and
        // removed when the source changes; its presence is "the integration has answered".
        answered = prefs.contains(KEY_RECEIVE_CONFIGURED),
        integrationOutdated = prefs.getBoolean(KEY_RECEIVE_OUTDATED, false),
        writtenToday = SyncStatusStore.writtenToday(appContext)
    )

    fun setReceiveConfiguredTypes(types: List<String>) = prefs.edit { putString(KEY_RECEIVE_CONFIGURED, types.joinToString(",")) }

    fun setReceiveIntegrationOutdated(outdated: Boolean) = prefs.edit { putBoolean(KEY_RECEIVE_OUTDATED, outdated) }

    fun getWriteBackLedger(): WriteBackLedger = WriteBackLedger.decode(writeBackPrefs.getString(KEY_WRITEBACK_LEDGER, null))

    fun setWriteBackLedger(ledger: WriteBackLedger) = writeBackPrefs.edit { putString(KEY_WRITEBACK_LEDGER, ledger.encode()) }

    /** The acks and failures still to ride on the next request to the source URL. */
    fun getWriteBackReport(): WriteBackReport =
        writeBackPrefs.getString(KEY_WRITEBACK_REPORT, null)
            ?.let { runCatching { Json.decodeFromString<WriteBackReport>(it) }.getOrNull() }
            ?: WriteBackReport.EMPTY

    fun setWriteBackReport(report: WriteBackReport) = writeBackPrefs.edit {
        if (report.isEmpty) remove(KEY_WRITEBACK_REPORT) else putString(KEY_WRITEBACK_REPORT, Json.encodeToString(report))
    }

    /** Forgets the ledger and the pending acks; what was not acknowledged is offered again. */
    fun clearWriteBackState() = writeBackPrefs.edit { remove(KEY_WRITEBACK_LEDGER); remove(KEY_WRITEBACK_REPORT) }

    // ==================== Sync schedules ====================

    /**
     * The schedule of one source. Reads the existing interval key so an app that has never
     * seen this setting keeps syncing exactly as before: mode defaults to INTERVAL and the
     * interval to whatever the user already had.
     */
    fun getSyncSchedule(source: LogType): SyncSchedule {
        val prefix = schedulePrefix(source)
        val interval = when (source) {
            LogType.HEALTH_CONNECT -> getHealthSyncIntervalMinutes()
            LogType.SCREEN_TIME -> getScreenTimeSyncIntervalMinutes()
        }
        val mode = runCatching { SyncMode.valueOf(prefs.getString(prefix + "mode", null) ?: SyncMode.INTERVAL.name) }
            .getOrDefault(SyncMode.INTERVAL)
        val quietFrom = prefs.getString(prefix + "quiet_from", null)
        val quietTo = prefs.getString(prefix + "quiet_to", null)
        val quiet = if (quietFrom != null && quietTo != null) {
            runCatching { QuietWindow(LocalTime.parse(quietFrom), LocalTime.parse(quietTo)) }.getOrNull()
        } else null
        return SyncSchedule(
            mode = mode,
            intervalMinutes = interval,
            times = SyncSchedule.parseTimes(prefs.getString(prefix + "times", "") ?: ""),
            days = SyncSchedule.parseDays(prefs.getString(prefix + "days", null)),
            quietWindow = quiet
        )
    }

    fun setSyncSchedule(source: LogType, schedule: SyncSchedule) {
        val prefix = schedulePrefix(source)
        when (source) {
            LogType.HEALTH_CONNECT -> setHealthSyncIntervalMinutes(schedule.intervalMinutes)
            LogType.SCREEN_TIME -> setScreenTimeSyncIntervalMinutes(schedule.intervalMinutes)
        }
        prefs.edit()
            .putString(prefix + "mode", schedule.mode.name)
            .putString(prefix + "times", SyncSchedule.formatTimes(schedule.times))
            .putString(prefix + "days", SyncSchedule.formatDays(schedule.days))
            .putString(prefix + "quiet_from", schedule.quietWindow?.from?.toString())
            .putString(prefix + "quiet_to", schedule.quietWindow?.to?.toString())
            .apply()
    }

    /** When the last scheduled run of [source] finished, in epoch millis; null before the first. */
    fun getScheduleLastRun(source: LogType): Long? =
        prefs.getLong(schedulePrefix(source) + "last_run", -1L).takeIf { it > 0 }

    fun setScheduleLastRun(source: LogType, epochMillis: Long) {
        prefs.edit().putLong(schedulePrefix(source) + "last_run", epochMillis).apply()
    }

    private fun schedulePrefix(source: LogType) = when (source) {
        LogType.HEALTH_CONNECT -> HEALTH_SCHEDULE_PREFIX
        LogType.SCREEN_TIME -> SCREENTIME_SCHEDULE_PREFIX
    }

    // ==================== Screen Time Settings ====================

    fun getScreenTimeSyncIntervalMinutes(): Int {
        return prefs.getInt(KEY_SCREENTIME_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
    }

    fun setScreenTimeSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SCREENTIME_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

    fun getScreenTimeWebhookUrls(): List<String> {
        val urlsString = prefs.getString(KEY_SCREENTIME_WEBHOOK_URLS, "") ?: ""
        return if (urlsString.isEmpty()) emptyList() else urlsString.split(",")
    }

    fun setScreenTimeWebhookUrls(urls: List<String>) {
        val urlsString = urls.joinToString(",")
        prefs.edit().putString(KEY_SCREENTIME_WEBHOOK_URLS, urlsString).apply()
    }

    fun getScreenTimeWebhookHeaders(): Map<String, String> {
        val headersJson = securePrefs.getString(KEY_SCREENTIME_WEBHOOK_HEADERS, null) ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(headersJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setScreenTimeWebhookHeaders(headers: Map<String, String>) {
        val headersJson = Json.encodeToString(headers)
        securePrefs.edit().putString(KEY_SCREENTIME_WEBHOOK_HEADERS, headersJson).apply()
    }

    /** Screen Time URLs that get none of the custom headers, see [WebhookSupport.headersFor]. */
    fun getScreenTimeUrlsWithoutHeaders(): Set<String> = urlSet(KEY_SCREENTIME_URLS_WITHOUT_HEADERS)

    fun setScreenTimeUrlsWithoutHeaders(urls: Set<String>) = putUrlSet(KEY_SCREENTIME_URLS_WITHOUT_HEADERS, urls)

    fun getScreenTimeWebhookSecret(): String? {
        return securePrefs.getString(KEY_SCREENTIME_WEBHOOK_SECRET, null)?.takeIf { it.isNotBlank() }
    }

    fun setScreenTimeWebhookSecret(secret: String?) {
        if (secret.isNullOrBlank()) {
            securePrefs.edit().remove(KEY_SCREENTIME_WEBHOOK_SECRET).apply()
        } else {
            securePrefs.edit().putString(KEY_SCREENTIME_WEBHOOK_SECRET, secret).apply()
        }
    }

    fun getScreenTimeLastSyncTimestamp(): Long? {
        val timestamp = prefs.getLong(KEY_SCREENTIME_LAST_SYNC_TS, -1)
        return if (timestamp == -1L) null else timestamp
    }

    fun setScreenTimeLastSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_SCREENTIME_LAST_SYNC_TS, timestamp).apply()
    }

    fun getScreenTimeDayBoundaryHour(): Int {
        return prefs.getInt(KEY_SCREENTIME_DAY_BOUNDARY_HOUR, DEFAULT_DAY_BOUNDARY_HOUR)
    }

    fun setScreenTimeDayBoundaryHour(hour: Int) {
        prefs.edit().putInt(KEY_SCREENTIME_DAY_BOUNDARY_HOUR, hour.coerceIn(0, 23)).apply()
    }

    fun useScreenTimeDayBoundary(): Boolean {
        return prefs.getBoolean(KEY_SCREENTIME_USE_DAY_BOUNDARY, true)
    }

    fun setUseScreenTimeDayBoundary(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREENTIME_USE_DAY_BOUNDARY, enabled).apply()
    }

    /** Which apps Screen Time sends (issue #63); every app when nothing was chosen. */
    fun getScreenTimeAppFilter(): ScreenTimeAppFilter = ScreenTimeAppFilter(
        mode = AppFilterMode.from(prefs.getString(KEY_SCREENTIME_APP_FILTER_MODE, null)),
        packages = prefs.getStringSet(KEY_SCREENTIME_APP_FILTER_PACKAGES, null)?.toSet().orEmpty()
    )

    fun setScreenTimeAppFilter(filter: ScreenTimeAppFilter) {
        prefs.edit()
            .putString(KEY_SCREENTIME_APP_FILTER_MODE, filter.mode.name)
            .putStringSet(KEY_SCREENTIME_APP_FILTER_PACKAGES, filter.packages.toSet())
            .apply()
    }

    // ==================== Webhook Logs (Shared) ====================
    // Backed by WebhookLogStore: metadata in its own prefs file, payloads as separate files
    // capped by total bytes. Both are excluded from backup (res/xml/backup_rules.xml).

    fun getWebhookLogs(filterType: LogType? = null): List<WebhookLog> =
        logStore.getAll(filterType)

    fun addWebhookLog(log: WebhookLog) {
        // A Receive row's payload is the per-reading lines, already without values unless the
        // user keeps full payloads, so it is stored whole rather than cut at the size limit.
        logStore.add(log, keepFullPayloads = keepFullPayloads() || log.direction == LogDirection.IN.name)
    }

    fun clearWebhookLogs(filterType: LogType? = null) {
        logStore.clear(filterType)
    }

    /** Whether the first-run wizard has been completed or skipped. */
    fun onboardingCompleted(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }

    /** Whether raw payloads are kept in full; off by default, they are raw health data. */
    fun keepFullPayloads(): Boolean = prefs.getBoolean(KEY_KEEP_FULL_PAYLOADS, false)

    fun setKeepFullPayloads(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_FULL_PAYLOADS, enabled).apply()
    }

    // QR pairing writes across both webhook sections at once, which no single ViewModel
    // owns, so it goes through here. PairingStore is narrower than this class on purpose,
    // so the rules in PairingApply stay unit-testable without SharedPreferences.

    fun healthSectionWebhook(): SectionWebhook =
        SectionWebhook(getHealthWebhookUrls(), getHealthWebhookSecret(), getHealthUrlsWithoutHeaders())

    fun screenTimeSectionWebhook(): SectionWebhook =
        SectionWebhook(getScreenTimeWebhookUrls(), getScreenTimeWebhookSecret(), getScreenTimeUrlsWithoutHeaders())

    fun asPairingStore(): PairingStore = object : PairingStore {
        override fun health() = healthSectionWebhook()
        override fun screenTime() = screenTimeSectionWebhook()

        override fun setHealth(urls: List<String>, secret: String, urlsWithoutHeaders: Set<String>) {
            setHealthWebhookUrls(urls)
            setHealthWebhookSecret(secret)
            setHealthUrlsWithoutHeaders(urlsWithoutHeaders)
        }

        override fun setScreenTime(urls: List<String>, secret: String, urlsWithoutHeaders: Set<String>) {
            setScreenTimeWebhookUrls(urls)
            setScreenTimeWebhookSecret(secret)
            setScreenTimeUrlsWithoutHeaders(urlsWithoutHeaders)
        }

        override fun setAllowPlainHttp(enabled: Boolean) = setAllowHttpWebhooks(enabled)
    }
}
