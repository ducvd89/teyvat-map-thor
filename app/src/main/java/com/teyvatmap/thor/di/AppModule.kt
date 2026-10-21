package com.teyvatmap.thor.di

import android.content.Context
import androidx.room.Room
import com.teyvatmap.thor.data.api.HeaderInterceptor
import com.teyvatmap.thor.data.db.AppDatabase
import com.teyvatmap.thor.data.store.PackParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = PackParser.json

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HeaderInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

    @Provides fun mapDao(db: AppDatabase) = db.maps()
    @Provides fun labelDao(db: AppDatabase) = db.labels()
    @Provides fun pointDao(db: AppDatabase) = db.points()
    @Provides fun floorEntranceDao(db: AppDatabase) = db.floorEntrances()
    @Provides fun userMarkDao(db: AppDatabase) = db.userMarks()
    @Provides fun customPinDao(db: AppDatabase) = db.customPins()
    @Provides fun respawnRuleDao(db: AppDatabase) = db.respawnRules()
}
