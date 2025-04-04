package io.github.awkwardpeak.extension.all.mangaplus.mangadex

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.commonEmptyHeaders
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object MangaDexMetadataFetcher {
    private val apiUrl = "https://api.mangadex.org"

    private val client = Injekt.get<NetworkHelper>().cloudflareClient
        .newBuilder()
        .rateLimit(3)
        .build()

    private val mapping: Map<String, String> by lazy {
        client.newCall(
            GET(
                url = "https://raw.githubusercontent.com/AwkwardPeak7/mplus-mdex-map/refs/heads/main/map.json",
                cache = CacheControl.FORCE_NETWORK,
            ),
        ).execute().parseAs()
    }

    fun getCovers(ids: List<String>, small: Boolean = false): Map<String, String?> {
        var offset = 0
        var total: Int
        val data = mutableListOf<Manga>()

        val mdexUuids = ids.mapNotNull { id ->
            mapping[id]
        }

        if (mdexUuids.isEmpty()) {
            return ids.associateWith { null }
        }

        do {
            val url = apiUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("manga")
                addQueryParameter("limit", "100")
                addQueryParameter("offset", offset.toString())
                addQueryParameter("includes[]", "cover_art")
                mdexUuids.forEach { uuid ->
                    addQueryParameter("ids[]", uuid)
                }
            }.build()

            val response = client.newCall(GET(url, commonEmptyHeaders)).execute()
                .parseAs<MangasResponse>()

            data += response.data
            offset += response.limit
            total = response.total
        } while (offset < total)

        val coverMap = data.associate { manga ->
            manga.id to
                manga.relationships.firstOrNull()?.attributes?.fileName?.takeIf { it.isNotEmpty() }
        }

        return ids.associateWith { mpId ->
            val uuid = mapping[mpId]

            uuid?.let { mdexUUid ->
                coverMap[mdexUUid]?.let { file ->
                    "https://uploads.mangadex.org/covers/$mdexUUid/$file" +
                        if (small) {
                            ".512.jpg"
                        } else {
                            ""
                        }
                }
            }
        }
    }

    fun getCover(id: String, small: Boolean = false): String? {
        return getCovers(listOf(id), small)[id]
    }
}
