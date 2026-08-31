package com.loosewire.lightious

import com.loosewire.lightious.data.AccountStore
import com.loosewire.lightious.data.CompanionRepository
import com.loosewire.lightious.data.CompanionStore
import com.loosewire.lightious.data.HistoryDatabase
import com.loosewire.lightious.data.HistoryRepository
import com.loosewire.lightious.data.HistorySyncer
import com.loosewire.lightious.data.SettingsStore
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

class LightiousServices private constructor(
    val settings: SettingsStore,
    val accounts: AccountStore,
    val companion: CompanionRepository,
    val history: HistoryRepository,
    val historySyncer: HistorySyncer,
) {
    companion object {
        @Volatile
        private var instance: LightiousServices? = null

        fun from(context: SealedLightContext): LightiousServices =
            instance ?: synchronized(this) {
                instance ?: run {
                    val history = HistoryRepository(
                        context.buildDatabase(HistoryDatabase::class.java, HistoryDatabase.NAME)
                            .historyDao(),
                    )
                    LightiousServices(
                        settings = SettingsStore(context.dataStore),
                        accounts = AccountStore(context.dataStore),
                        companion = CompanionRepository(CompanionStore(context.dataStore)),
                        history = history,
                        historySyncer = HistorySyncer(history),
                    ).also { instance = it }
                }
            }
    }
}
