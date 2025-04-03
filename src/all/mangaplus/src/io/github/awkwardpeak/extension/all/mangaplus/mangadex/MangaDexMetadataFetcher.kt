package io.github.awkwardpeak.extension.all.mangaplus.mangadex

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import keiyoushi.utils.parseAs
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.commonEmptyHeaders
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object MangaDexMetadataFetcher {
    private val apiUrl = "https://api.mangadex.org"

    private val client = Injekt.get<NetworkHelper>().cloudflareClient

    private val mapping: Map<String, String> by lazy {
        client.newCall(
            GET(
                url = "https://raw.githubusercontent.com/AwkwardPeak7/mplus-mdex-map/refs/heads/main/map.json",
                cache = CacheControl.FORCE_NETWORK,
            ),
        ).execute().parseAs()
    }

    fun getCovers(ids: List<String>, small: Boolean = false): Map<String, String?> {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("cover")
            addQueryParameter("order[volume]", "desc")
            addQueryParameter("locales[]", "ja")
            addQueryParameter("limit", "100")
            ids.forEach { mpID ->
                mapping[mpID]?.also { uuid ->
                    addQueryParameter("manga[]", uuid)
                }
            }
        }.build()

        val data = client.newCall(GET(url, commonEmptyHeaders)).execute()
            .parseAs<CoverArtResponse>()

        val coverMap = data.data.groupBy { coverArt ->
            coverArt.relationships.firstOrNull { it.type == "manga" }!!.id
        }.mapValues { (_, values) ->
            values.maxByOrNull { coverArt ->
                coverArt.attributes.volume?.toFloatOrNull() ?: Float.MIN_VALUE
            }?.attributes?.fileName?.takeIf { it.isNotEmpty() }
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
