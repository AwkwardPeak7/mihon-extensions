package io.github.awkwardpeak.extension.all.mangaplus.mangabaka

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

// memo key holding the resolved MangaBaka series id for a title.
const val BAKA_MEMO_KEY = "mangabaka.id"

@Serializable
class BakaSearchResponse(
    val data: List<BakaSeries> = emptyList(),
    val pagination: BakaPagination = BakaPagination(),
)

@Serializable
class BakaPagination(
    val next: String? = null,
)

@Serializable
class BakaSingleResponse(
    val data: BakaSeries? = null,
)

@Serializable
class BakaSimilarResponse(
    val data: List<BakaSimilarItem> = emptyList(),
)

@Serializable
class BakaSimilarItem(
    val series: BakaSeries,
)

@Serializable
class BakaImagesResponse(
    val data: List<BakaImage> = emptyList(),
)

@Serializable
class BakaImage(
    val type: String = "",
    @SerialName("index_numeric") val indexNumeric: Double = 0.0,
    val image: BakaCover? = null,
)

@Serializable
class BakaSeries(
    val id: Int,
    private val title: String,
    @SerialName("native_title") private val nativeTitle: String? = null,
    @SerialName("romanized_title") private val romanizedTitle: String? = null,
    private val titles: List<BakaTitle>? = null,
    private val authors: List<String>? = null,
    private val artists: List<String>? = null,
    private val description: String? = null,
    private val status: String = "unknown",
    private val genres: List<String>? = null,
    private val type: String = "manga",
    private val year: Int? = null,
    private val rating: Double? = null,
    @SerialName("total_chapters") private val totalChapters: String? = null,
    @SerialName("final_volume") private val finalVolume: String? = null,
    @SerialName("content_rating") private val contentRating: String = "safe",
    private val cover: BakaCover? = null,
    @SerialName("links_v2") private val links: List<BakaLink> = emptyList(),
) {
    fun mangaPlusIds(): List<Int> = links.mapNotNull { it.mangaPlusId() }

    fun toSManga(
        mangaPlusId: Int,
        lang: String,
        coverOverride: String? = null,
        extraFacts: List<String> = emptyList(),
    ): SManga = SManga.create().apply {
        val displayTitle = localizedTitle(lang)
        url = "#/titles/$mangaPlusId"
        title = displayTitle
        author = authors?.joinToString()?.ifEmpty { null }
        artist = artists?.joinToString()?.ifEmpty { null }
        description = buildDescription(displayTitle, extraFacts).ifEmpty { null }
        genre = genres.orEmpty().joinToString { it.toDisplayGenre() }
        thumbnail_url = coverOverride ?: cover?.raw?.url
        status = this@BakaSeries.status.toStatus()
        memo = JsonObject(mapOf(BAKA_MEMO_KEY to JsonPrimitive(id)))
    }

    private fun localizedTitle(lang: String): String = titles?.firstOrNull { it.language.equals(lang, ignoreCase = true) }?.title
        ?: titles?.firstOrNull { it.isPrimary }?.title
        ?: title

    private fun buildDescription(displayTitle: String, extraFacts: List<String>): String {
        val altTitles = buildList {
            add(nativeTitle)
            add(romanizedTitle)
            titles?.forEach { add(it.title) }
        }.filterNotNull().distinct().filterNot { it == displayTitle }

        val facts = buildList {
            if (!type.equals("manga", ignoreCase = true)) add("Type: ${type.toDisplayGenre()}")
            year?.let { add("Year: $it") }
            rating?.let { add("Rating: ${it.roundToInt()}%") }
            if (!status.equals("releasing", ignoreCase = true)) {
                totalChapters?.let { add("Chapters: $it") }
                finalVolume?.let { add("Volumes: $it") }
            }
            if (!contentRating.equals("safe", ignoreCase = true)) {
                add("Content rating: ${contentRating.toDisplayGenre()}")
            }
            addAll(extraFacts)
        }

        val linkLines = links
            .filter { it.isDisplayable() }
            .distinctBy { it.url }
            .map { "[${it.nameDisplay}](${it.url})" }

        return buildString {
            description?.let { append(it) }
            appendSection(null, facts)
            appendSection("Alternative titles", altTitles)
            appendSection("Links", linkLines)
        }
    }
}

private fun StringBuilder.appendSection(header: String?, lines: List<String>) {
    if (lines.isEmpty()) return
    if (isNotEmpty()) append("\n\n")
    header?.let { append(it).append('\n') }
    append(lines.joinToString("\n") { "- $it" })
}

@Serializable
class BakaTitle(
    val language: String = "",
    val title: String,
    @SerialName("is_primary") val isPrimary: Boolean = false,
)

@Serializable
class BakaCover(
    val raw: BakaCoverImage? = null,
)

@Serializable
class BakaCoverImage(
    val url: String? = null,
)

@Serializable
class BakaLink(
    val url: String,
    @SerialName("name_display") val nameDisplay: String = "",
) {
    fun mangaPlusId(): Int? = MP_TITLE_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull()

    fun isDisplayable(): Boolean = nameDisplay.isNotEmpty() &&
        !url.contains("mangaplus.shueisha", ignoreCase = true)
}

private val MP_TITLE_REGEX = Regex("""mangaplus\.shueisha\.co\.jp/titles/(\d+)""")

@Serializable
class BakaGenre(
    val label: String,
    val value: String,
)

private fun String.toStatus(): Int = when (this) {
    "releasing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private fun String.toDisplayGenre(): String = replace('_', ' ').replace('-', ' ').split(' ')
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
