package io.github.awkwardpeak.extension.en.omegascans

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.concurrent.thread

class OmegaScans : ConfigurableSource, HttpSource() {

    override val name = "Omega Scans"

    override val baseUrl = "https://omegascans.org"

    private val apiUrl: String = baseUrl.replace("://", "://api.")

    override val lang = "en"

    // Site changed from MangaThemesia to HeanCms.
    override val versionId = 2

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val cdnUrl = apiUrl

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/query".toHttpUrl().newBuilder()
            .addQueryParameter("query_string", "")
            .addQueryParameter("status", "All")
            .addQueryParameter("order", "desc")
            .addQueryParameter("orderBy", "total_views")
            .addQueryParameter("series_type", "Comic")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", "12")
            .addQueryParameter("tags_ids", "[]")
            .addQueryParameter("adult", "true")

        return GET(url.build(), headers)
    }

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/query".toHttpUrl().newBuilder()
            .addQueryParameter("query_string", "")
            .addQueryParameter("status", "All")
            .addQueryParameter("order", "desc")
            .addQueryParameter("orderBy", "latest")
            .addQueryParameter("series_type", "Comic")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", "12")
            .addQueryParameter("tags_ids", "[]")
            .addQueryParameter("adult", "true")

        return GET(url.build(), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (!query.startsWith(SEARCH_PREFIX)) {
            return super.fetchSearchManga(page, query, filters)
        }

        val slug = query.substringAfter(SEARCH_PREFIX)
        val manga = SManga.create().apply {
            url = "/series/$slug"
        }

        return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sortByFilter = filters.firstInstanceOrNull<SortByFilter>()
        val statusFilter = filters.firstInstanceOrNull<StatusFilter>()

        val tagIds = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()
            .filter(Genre::state)
            .map(Genre::id)
            .joinToString(",", prefix = "[", postfix = "]")

        val url = "$apiUrl/query".toHttpUrl().newBuilder()
            .addQueryParameter("query_string", query)
            .addQueryParameter("status", statusFilter?.selected?.value ?: "All")
            .addQueryParameter("order", if (sortByFilter?.state?.ascending == true) "asc" else "desc")
            .addQueryParameter("orderBy", sortByFilter?.selected ?: "total_views")
            .addQueryParameter("series_type", "Comic")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", "12")
            .addQueryParameter("tags_ids", tagIds)
            .addQueryParameter("adult", "true")

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val json = response.body.string()

        val result = json.parseAs<HeanCmsQuerySearchDto>()
        val mangaList = result.data.map {
            it.toSManga(cdnUrl)
        }

        return MangasPage(mangaList, result.meta?.hasNextPage() ?: false)
    }

    override fun getMangaUrl(manga: SManga): String {
        val seriesSlug = manga.url
            .substringAfterLast("/")
            .substringBefore("#")

        return "$baseUrl/series/$seriesSlug"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val seriesSlug = manga.url.substringAfterLast("/").substringBefore("#")

        val apiHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .build()

        return GET("$apiUrl/series/$seriesSlug", apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<HeanCmsSeriesDto>()

        return result.toSManga(cdnUrl)
    }

    override fun chapterListRequest(manga: SManga): Request {
        if (!manga.url.contains("#")) {
            throw Exception("The URL of the series has changed. Migrate from $name to $name to update the URL")
        }

        val seriesId = manga.url.substringAfterLast("#")
        val seriesSlug = manga.url.substringAfterLast("/").substringBefore("#")

        val url = "$apiUrl/chapter/query".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("perPage", PER_PAGE_CHAPTERS.toString())
            .addQueryParameter("series_id", seriesId)
            .fragment(seriesSlug)

        return GET(url.build(), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val showPaidChapters = preferences.showPaidChapters

        val apiHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .build()

        val seriesId = response.request.url.queryParameter("series_id")

        val seriesSlug = response.request.url.fragment!!

        var result = response.parseAs<HeanCmsChapterPayloadDto>()

        val currentTimestamp = System.currentTimeMillis()

        val chapterList = mutableListOf<HeanCmsChapterDto>()

        chapterList.addAll(result.data)

        var page = 2
        while (result.meta.hasNextPage()) {
            val url = "$apiUrl/chapter/query".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("perPage", PER_PAGE_CHAPTERS.toString())
                .addQueryParameter("series_id", seriesId)
                .build()

            val nextResponse = client.newCall(GET(url, apiHeaders)).execute()
            result = nextResponse.parseAs<HeanCmsChapterPayloadDto>()
            chapterList.addAll(result.data)
            page++
        }

        return chapterList
            .filter { it.price == 0 || showPaidChapters }
            .map { it.toSChapter(seriesSlug) }
            .filter { it.date_upload <= currentTimestamp }
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substringBeforeLast("#")

    override fun pageListRequest(chapter: SChapter) =
        GET(apiUrl + chapter.url.replace("/series/", "/chapter/"), headers)

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<HeanCmsPagePayloadDto>()

        if (result.isPaywalled() && result.chapter.chapterData == null) {
            throw Exception("Paid chapter unavailable")
        }

        return result.chapter.chapterData?.images.orEmpty().mapIndexed { i, img ->
            Page(i, imageUrl = img.toAbsoluteUrl(cdnUrl))
        }
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl!!)

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .add("Accept", ACCEPT_IMAGE)
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    private fun getStatusList(): List<Status> = listOf(
        Status("All", "All"),
        Status("Ongoing", "Ongoing"),
        Status("On hiatus", "Hiatus"),
        Status("Dropped", "Dropped"),
        Status("Completed", "Completed"),
        Status("Canceled", "Canceled"),
    )

    private fun getSortProperties(): List<SortProperty> = listOf(
        SortProperty("Title", "title"),
        SortProperty("Views", "total_views"),
        SortProperty("Latest", "latest"),
        SortProperty("Created at", "created_at"),
    )

    private var genresList: List<Genre> = emptyList()
    private var fetchFiltersAttempts = 0
    private var filtersState = FiltersState.NOT_FETCHED

    private fun fetchFilters() {
        if (filtersState != FiltersState.NOT_FETCHED || fetchFiltersAttempts >= 3) return
        filtersState = FiltersState.FETCHING
        fetchFiltersAttempts++
        thread {
            try {
                val response = client.newCall(GET("$apiUrl/tags", headers)).execute()
                val genres = response.parseAs<List<HeanCmsGenreDto>>()

                genresList = genres.map { Genre(it.name, it.id) }

                filtersState = FiltersState.FETCHED
            } catch (e: Throwable) {
                filtersState = FiltersState.NOT_FETCHED
            }
        }
    }

    override fun getFilterList(): FilterList {
        fetchFilters()

        val filters = mutableListOf<Filter<*>>(
            StatusFilter("Status", getStatusList()),
            SortByFilter("Sort By", getSortProperties()),
        )

        filters += if (filtersState == FiltersState.FETCHED) {
            listOfNotNull(
                GenreFilter("Genres", genresList),
            )
        } else {
            listOf(
                Filter.Separator(),
                Filter.Header("Press 'Reset' to attempt to show the genres"),
            )
        }

        return FilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_PAID_CHAPTERS_PREF
            title = "Display paid chapters"
            summaryOn = "Paid chapters will appear."
            summaryOff = "Only free chapters will be displayed."
            setDefaultValue(SHOW_PAID_CHAPTERS_DEFAULT)
        }.also(screen::addPreference)
    }

    private val SharedPreferences.showPaidChapters: Boolean
        get() = getBoolean(SHOW_PAID_CHAPTERS_PREF, SHOW_PAID_CHAPTERS_DEFAULT)

    private enum class FiltersState { NOT_FETCHED, FETCHING, FETCHED }

    companion object {
        private const val ACCEPT_IMAGE = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        private const val ACCEPT_JSON = "application/json, text/plain, */*"

        private const val PER_PAGE_CHAPTERS = 1000

        const val SEARCH_PREFIX = "slug:"

        private const val SHOW_PAID_CHAPTERS_PREF = "pref_show_paid_chap"
        private const val SHOW_PAID_CHAPTERS_DEFAULT = false
    }
}
