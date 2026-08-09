package com.dylandos.iptv.ultimate.di

import android.content.Context
import com.dylandos.iptv.ultimate.data.network.TmdbApiService
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Dispatcher
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * DYLANDOS IPTV - Network Module
 * 
 * Provides network dependencies: Retrofit, OkHttp, Gson
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .create()

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (com.dylandos.iptv.ultimate.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        // 50 MB disk cache — Xtream API responses (streams list, categories, EPG)
        // are cached on-device so the Home stats screen and browse screens load
        // from disk on repeat visits instead of hitting the network every time.
        val cache = Cache(
            directory = File(context.cacheDir, "okhttp_cache"),
            maxSize = 50L * 1024L * 1024L  // 50 MB
        )

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addNetworkInterceptor { chain ->
                val trexUserAgentRequest = chain.request().newBuilder()
                    .header("User-Agent", "VLC/3.0.0")
                    .build()
                chain.proceed(trexUserAgentRequest)
            }
            .cache(cache)
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 12
            })
            // Larger connection pool for parallel EPG batch fetching (50 channels)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            // Prefer HTTP/2 (multiplexed) — reduces connection setup overhead
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)   // Reduced from 60s — prevents 1-min freeze on stalled Xtream calls
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)   // Hard cap per call — prevents indefinite hangs
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideXtreamRepository(
        okHttpClient: OkHttpClient,
        gson: Gson,
        hiddenCategoryDao: com.dylandos.iptv.ultimate.data.db.dao.HiddenCategoryDao,
        channelDao: com.dylandos.iptv.ultimate.data.db.dao.ChannelDao,
        epgProgramDao: com.dylandos.iptv.ultimate.data.db.dao.EpgProgramDao
    ): XtreamRepository {
        // Repository uses OkHttp directly so the server URL can be fully dynamic.
        return XtreamRepository(okHttpClient, gson, hiddenCategoryDao, channelDao, epgProgramDao)
    }

    // ── TMDB (The Movie Database) ──────────────────────────────────────────
    // Provides high-resolution posters and backdrops for VOD and Series details.
    // The API key should be stored in BuildConfig or a secrets manager for production.
    // The same OkHttpClient is shared with XtreamRepository (connection pool re-use).

    @Provides
    @Singleton
    fun provideTmdbRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideTmdbApiService(retrofit: Retrofit): TmdbApiService =
        retrofit.create(TmdbApiService::class.java)
}
