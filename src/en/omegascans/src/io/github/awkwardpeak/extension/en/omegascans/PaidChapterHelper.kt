package io.github.awkwardpeak.extension.en.omegascans

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.charset.Charset

object PaidChapterHelper {
    private val client = Injekt.get<NetworkHelper>().cloudflareClient

    private val repo = Base64.decode(
        "TGFpY2h0L2ltYWdlcw==".toByteArray(),
        Base64.DEFAULT,
    ).toString(
        Charset.forName("UTF-8"),
    )

    /**
     * contains all those which have jc < 0.6 but should match
     */
    val hardcodedList = javaClass.getResourceAsStream("/assets/cubari.json")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?.parseAs<Map<String, String>>()
        ?: mapOf()

    private val cubariList by lazy {
        val url = "https://api.github.com/repos/$repo/git/trees/master?recursive=1"

        client.newCall(GET(url)).execute()
            .parseAs<GitHubResponse>()
            .tree.map { it.path }
            .filter { it.endsWith(".json") && it !in listOf("gaylorddd!.json", "test.json") }
            .associateBy { cubari ->
                cubari
                    .substringBeforeLast(".json")
                    .filter { it.isLetterOrDigit() || it == ' ' }
                    .lowercase()
            }
    }

    private fun getCubariClosest(title: String): String? {
        hardcodedList[title]?.also { return it }

        val cleanedTitle = title.filter { it.isLetterOrDigit() || it == ' ' }.lowercase()

        var jc = 0.0
        var res: String? = null

        cubariList.forEach { (cleanedCubari, cubari) ->
            val jaccard = jaccardSimilarity(cleanedCubari, cleanedTitle)
            if (jaccard >= 0.6 && jaccard > jc) {
                jc = jaccard
                res = cubari
            }
        }

        return res
    }

    private fun jaccardSimilarity(s1: String, s2: String): Double {
        val set1 = s1.split(" ").toSet()
        val set2 = s2.split(" ").toSet()
        val intersection = set1.intersect(set2).size.toDouble()
        val union = set1.union(set2).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    suspend fun getCubariChapters(title: String): CubariChaptersResponse? {
        val jsonFile = getCubariClosest(title)
            ?: return null

        val url = "https://raw.githubusercontent.com/$repo/refs/heads/master/$jsonFile"

        return client.newCall(GET(url)).await()
            .parseAs<CubariChaptersResponse>()
    }
}

@Serializable
class GitHubResponse(
    val tree: List<Tree>,
)

@Serializable
class Tree(
    val path: String,
)

@Serializable
class CubariChaptersResponse(
    val chapters: Map<String, CubariChapterResponse>,
)

@Serializable
class CubariChapterResponse(
    private val groups: Map<String, String>,
    @SerialName("last_updated") private val lastUpdatedString: String,
) {
    val url = groups.values.first()
    val lastUpdated get() = lastUpdatedString.toLong().times(1000)
}
