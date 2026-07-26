package io.github.awkwardpeak.extension.all.mangaplus.mangabaka

import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.awkwardpeak.extension.BuildConfig
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.applicationContext
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.brotli.Brotli
import okhttp3.zstd.Zstd
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object MangaBakaMetadataFetcher {
    private const val PUBLISHER = "MANGA Plus"
    private const val SEARCH_URL = "https://api.mangabaka.org/v1/series/search"
    private const val SERIES_URL = "https://api.mangabaka.org/v1/series"
    private const val GENRES_URL = "https://api.mangabaka.org/v1/genres"

    private val client = Injekt.get<NetworkHelper>().client
        .newBuilder()
        .addInterceptor(CompressionInterceptor(Brotli, Gzip, Zstd))
        .rateLimit(3)
        .build()

    private val appName: String get() {
        val pkg = runCatching { applicationContext.packageName }.getOrNull()

        return when {
            pkg == null -> "Suwayomi"
            pkg.startsWith("app.mihon") -> "Mihon"
            pkg.contains("app.komikku") -> "Komikku"
            pkg == "eu.kanade.tachiyomi.sy" -> "TachiyomiSY"
            else -> pkg
        }
    }
    private val headers = Headers.headersOf("User-Agent", "MangaPlus/${BuildConfig.VERSION_NAME} $appName/${AppInfo.getVersionName()} (Android ${android.os.Build.VERSION.RELEASE}) (Discord: @AwkwardPeak; Github: AwkwardPeak7/mihon-extensions)")

    suspend fun search(query: String?, page: Int, params: List<Pair<String, String>>): BakaSearchResponse {
        val url = SEARCH_URL.toHttpUrl().newBuilder().apply {
            addQueryParameter("publisher", PUBLISHER)
            addQueryParameter("limit", "100")
            addQueryParameter("page", page.toString())
            if (!query.isNullOrBlank()) addQueryParameter("q", query)
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

        return client.get(url, headers).parseAs()
    }

    suspend fun getSeries(id: Int): BakaSeries? = client.get("$SERIES_URL/$id", headers, ensureSuccess = false).let { response ->
        if (!response.isSuccessful) {
            response.close()
            null
        } else {
            response.parseAs<BakaSingleResponse>().data
        }
    }

    suspend fun seriesByMangaPlusId(mangaPlusId: Int, name: String): BakaSeries? = search(query = name, page = 1, params = emptyList())
        .data.firstOrNull { mangaPlusId in it.mangaPlusIds() }

    suspend fun similar(id: Int): List<BakaSeries> = client.get("$SERIES_URL/$id/similar", headers, ensureSuccess = false).let { response ->
        if (!response.isSuccessful) {
            response.close()
            emptyList()
        } else {
            response.parseAs<BakaSimilarResponse>().data.map { it.series }
        }
    }

    suspend fun latestVolumeCover(id: Int): String? = client.get("$SERIES_URL/$id/images?limit=50&language=ja&type=volume", headers, ensureSuccess = false).let { response ->
        if (!response.isSuccessful) {
            response.close()
            null
        } else {
            response.parseAs<BakaImagesResponse>().data
                .filter { it.type == "volume" }
                .maxByOrNull { it.indexNumeric }
                ?.image?.raw?.url
        }
    }

    suspend fun allByMangaPlusId(): Map<Int, BakaSeries> {
        val result = mutableMapOf<Int, BakaSeries>()
        var page = 1

        while (true) {
            val response = search(query = null, page = page, params = emptyList())

            for (series in response.data) {
                series.mangaPlusIds().forEach { result.putIfAbsent(it, series) }
            }

            if (response.pagination.next == null) break
            page++
        }

        return result
    }

    suspend fun fetchGenres(): JsonElement = client.get(GENRES_URL, headers).parseAs<JsonElement>()["data"] ?: JsonArray(emptyList())
}
