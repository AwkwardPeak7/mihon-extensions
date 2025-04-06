package io.github.awkwardpeak.extension.en.omegascans

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
class HeanCmsQuerySearchDto(
    val data: List<HeanCmsSeriesDto> = emptyList(),
    val meta: HeanCmsQuerySearchMetaDto? = null,
)

@Serializable
class HeanCmsQuerySearchMetaDto(
    @SerialName("current_page") private val currentPage: Int,
    @SerialName("last_page") private val lastPage: Int,
) {
    fun hasNextPage() = currentPage < lastPage
}

@Serializable
class HeanCmsSeriesDto(
    private val id: Int,
    @SerialName("series_slug") val slug: String,
    private val author: String? = null,
    private val description: String? = null,
    private val studio: String? = null,
    private val status: String? = null,
    private val thumbnail: String,
    private val title: String,
    private val tags: List<HeanCmsTagDto>? = emptyList(),
) {

    fun toSManga(
        cdnUrl: String,
    ): SManga = SManga.create().apply {
        val descriptionBody = this@HeanCmsSeriesDto.description?.let(Jsoup::parseBodyFragment)

        title = this@HeanCmsSeriesDto.title
        author = this@HeanCmsSeriesDto.author?.trim()
        artist = this@HeanCmsSeriesDto.studio?.trim()
        description = descriptionBody?.select("p")
            ?.joinToString("\n\n") { it.text() }
            ?.ifEmpty { descriptionBody.text().replace("\n", "\n\n") }
        genre = tags.orEmpty()
            .sortedBy(HeanCmsTagDto::name)
            .joinToString { it.name }
        thumbnail_url = thumbnail.ifEmpty { null }
            ?.toAbsoluteUrl(cdnUrl)
        status = this@HeanCmsSeriesDto.status?.toStatus() ?: SManga.UNKNOWN
        url = "/series/$slug#$id"
    }
}

@Serializable
class HeanCmsTagDto(val name: String)

@Serializable
class HeanCmsChapterPayloadDto(
    val data: List<HeanCmsChapterDto>,
    val meta: HeanCmsChapterMetaDto,
)

@Serializable
class HeanCmsChapterDto(
    val id: Int,
    @SerialName("chapter_name") val name: String,
    @SerialName("chapter_title") val title: String? = null,
    @SerialName("chapter_slug") val slug: String,
    @SerialName("created_at") val createdAt: String? = null,
    val price: Int? = null,
) {
    val number get() = CHAP_NUM_REGEX.find(name)?.value ?: "-1"
}

private val CHAP_NUM_REGEX = Regex("""\d+(\.\d+)?""")

val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.US)

@Serializable
class HeanCmsChapterMetaDto(
    @SerialName("current_page") private val currentPage: Int,
    @SerialName("last_page") private val lastPage: Int,
) {
    fun hasNextPage() = currentPage < lastPage
}

@Serializable
class HeanCmsPagePayloadDto(
    val chapter: HeanCmsPageDto,
    private val paywall: Boolean = false,
) {
    fun isPaywalled() = paywall
}

@Serializable
class HeanCmsPageDto(
    @SerialName("chapter_data") val chapterData: HeanCmsPageDataDto?,
)

@Serializable
class HeanCmsPageDataDto(
    val images: List<String>? = emptyList(),
)

@Serializable
class HeanCmsGenreDto(
    val id: Int,
    val name: String,
)

fun String.toAbsoluteUrl(cdnUrl: String): String {
    return if (startsWith("https://") || startsWith("http://")) this else "$cdnUrl/$this"
}

fun String.toStatus(): Int = when (this) {
    "Ongoing" -> SManga.ONGOING
    "Hiatus" -> SManga.ON_HIATUS
    "Dropped" -> SManga.CANCELLED
    "Completed", "Finished" -> SManga.COMPLETED
    else -> SManga.UNKNOWN
}
