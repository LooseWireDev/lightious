package com.loosewire.lightious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CompanionStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher = AndroidTokenCipher(COMPANION_KEY_ALIAS),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun load(instanceUrl: String): CompanionState {
        val normalizedInstance = normalizeInstanceUrl(instanceUrl)
        val preferences = dataStore.data.first()
        if (preferences[INSTANCE_URL] != normalizedInstance) return CompanionState()

        val session = loadSession(preferences, normalizedInstance)
        val profile = preferences[CACHED_PROFILE]?.let { encoded ->
            try {
                json.decodeFromString<CompanionProfile>(encoded)
            } catch (_: SerializationException) {
                null
            }
        }?.takeIf { cached -> session != null && cached.deviceId == session.deviceId }
        return CompanionState(session, profile)
    }

    suspend fun saveSession(session: CompanionSession) {
        val normalizedInstance = normalizeInstanceUrl(session.instanceUrl)
        require(validDeviceBearer(session.deviceBearer)) { "Invalid device credential." }
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(session.deviceBearer))
        dataStore.edit { preferences ->
            preferences[INSTANCE_URL] = normalizedInstance
            preferences[DEVICE_ID] = session.deviceId
            preferences[ACCOUNT] = session.account
            preferences[ENCRYPTED_BEARER] = encrypted
            preferences.remove(CACHED_PROFILE)
        }
    }

    suspend fun saveProfile(instanceUrl: String, profile: CompanionProfile) {
        val normalizedInstance = normalizeInstanceUrl(instanceUrl)
        dataStore.edit { preferences ->
            if (preferences[INSTANCE_URL] == normalizedInstance && preferences[DEVICE_ID] == profile.deviceId) {
                preferences[ACCOUNT] = profile.account
                preferences[CACHED_PROFILE] = json.encodeToString(profile)
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(INSTANCE_URL)
            preferences.remove(DEVICE_ID)
            preferences.remove(ACCOUNT)
            preferences.remove(ENCRYPTED_BEARER)
            preferences.remove(CACHED_PROFILE)
        }
    }

    private fun loadSession(preferences: Preferences, instanceUrl: String): CompanionSession? {
        val deviceId = preferences[DEVICE_ID]?.takeIf(String::isNotBlank) ?: return null
        val account = preferences[ACCOUNT]?.takeIf(String::isNotBlank) ?: return null
        val encrypted = preferences[ENCRYPTED_BEARER] ?: return null
        val bearer = runCatching {
            cipher.decrypt(Base64.getDecoder().decode(encrypted))
        }.getOrNull()?.takeIf(::validDeviceBearer) ?: return null
        return CompanionSession(instanceUrl, deviceId, account, bearer)
    }

    private companion object {
        const val COMPANION_KEY_ALIAS = "lightious_companion_device_v1"
        val INSTANCE_URL = stringPreferencesKey("lightious_companion_instance")
        val DEVICE_ID = stringPreferencesKey("lightious_companion_device_id")
        val ACCOUNT = stringPreferencesKey("lightious_companion_account")
        val ENCRYPTED_BEARER = stringPreferencesKey("lightious_companion_bearer_encrypted")
        val CACHED_PROFILE = stringPreferencesKey("lightious_companion_profile")
    }
}
