package io.github.awkwardpeak.extension.all.mangaplus

import android.os.Build
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.github.awkwardpeak.extension.all.mangaplus.mangabaka.BAKA_MEMO_KEY
import io.github.awkwardpeak.extension.all.mangaplus.mangabaka.BakaSearchResponse
import io.github.awkwardpeak.extension.all.mangaplus.mangabaka.BakaSeries
import io.github.awkwardpeak.extension.all.mangaplus.mangabaka.MangaBakaFilters
import io.github.awkwardpeak.extension.all.mangaplus.mangabaka.MangaBakaMetadataFetcher
import io.github.awkwardpeak.extension.all.mangaplus.models.ChapterType
import io.github.awkwardpeak.extension.all.mangaplus.models.MPErrorAction
import io.github.awkwardpeak.extension.all.mangaplus.models.MPLanguage
import io.github.awkwardpeak.extension.all.mangaplus.models.MPMangaViewer
import io.github.awkwardpeak.extension.all.mangaplus.models.MPResponse
import io.github.awkwardpeak.extension.all.mangaplus.models.MPSuccessResult
import io.github.awkwardpeak.extension.all.mangaplus.models.MPTitleDetailView
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAsProto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.enums.enumEntries
import kotlin.random.Random

private val API_URL = "https://jumpg-api.tokyo-cdn.com/api".toHttpUrl()

@Source
abstract class MangaPlus :
    KeiSource(),
    ConfigurableSource {

    private val mpLang: MPLanguage = enumEntries<MPLanguage>().first { it.lang == lang }

    private val internalLang get() = mpLang.internalLang

    private val preferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::authIntercept)
        rateLimit(1) { it.host == API_URL.host }
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", "okhttp/4.9.0")
        removeAll("Referer")
        removeAll("Origin")
    }

    private suspend fun BakaSearchResponse.toMangasPage(): MangasPage {
        val langIds = langIds()
        val entries = data.mapNotNull { it.toSMangaOrNull(langIds) }

        return MangasPage(entries, hasNextPage = pagination.next != null)
    }

    private fun BakaSeries.toSMangaOrNull(langIds: Set<Int>): SManga? {
        val mangaPlusId = mangaPlusIds().firstOrNull { it in langIds } ?: return null

        return toSManga(mangaPlusId, lang)
    }

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage = MangaBakaMetadataFetcher.search(query = null, page = page, params = listOf("sort_by" to "popularity_asc"))
        .toMangasPage()

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage = coroutineScope {
        if (page > 1) return@coroutineScope MangasPage(emptyList(), false)

        val orderedDeferred = async { latestMangaPlusIds() }
        val catalogDeferred = async { MangaBakaMetadataFetcher.allByMangaPlusId() }

        val ordered = orderedDeferred.await()
        val catalog = catalogDeferred.await()

        MangasPage(
            ordered.mapNotNull { catalog[it]?.toSManga(it, lang) },
            hasNextPage = false,
        )
    }

    private suspend fun latestMangaPlusIds(): List<Int> {
        val url = API_URL.newBuilder()
            .addPathSegment("home_v4")
            .addQueryParameter("lang", internalLang)
            .addQueryParameter("clang", internalLang)
            .addCommonQueryParameters()
            .build()

        val data = client.get(url, headers).parseAsMpResponse()

        setSubscriptionReading(data.homeViewV3!!.userSubscription.planType != "basic")

        return data.homeViewV3.groups
            .flatMap { it.titleGroups.flatMap { g -> g.titles.map { t -> t.title } } }
            .filter { it.language == mpLang }
            .map { it.titleId }
            .distinct()
    }

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            return MangasPage(listOf(getMangaByMangaPlusId(query.removePrefix(PREFIX_ID_SEARCH))), false)
        }
        if (query.startsWith(PREFIX_CHAPTER_ID_SEARCH)) {
            val titleId = mangaViewer(query.removePrefix(PREFIX_CHAPTER_ID_SEARCH)).titleId
            return MangasPage(listOf(getMangaByMangaPlusId(titleId.toString())), false)
        }

        return MangaBakaMetadataFetcher.search(query = query, page = page, params = MangaBakaFilters.toQueryParams(filters))
            .toMangasPage()
    }

    // Filters

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = MangaBakaMetadataFetcher.fetchGenres()

    override fun getFilterList(data: JsonElement?): FilterList = MangaBakaFilters.getFilterList(data)

    // Related

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val mangaPlusId = manga.url.substringAfterLast("/").toIntOrNull() ?: return emptyList()
        val bakaId = manga.bakaId
            ?: MangaBakaMetadataFetcher.seriesByMangaPlusId(mangaPlusId, manga.title)?.id
            ?: return emptyList()

        val langIds = langIds()

        return MangaBakaMetadataFetcher.similar(bakaId).mapNotNull { it.toSMangaOrNull(langIds) }
    }

    private var languageIds: Set<Int>? = null
    private val languageIdsMutex = Mutex()

    private suspend fun langIds(): Set<Int> {
        languageIds?.let { return it }

        return languageIdsMutex.withLock {
            languageIds ?: fetchLangIds().also { languageIds = it }
        }
    }

    private suspend fun fetchLangIds(): Set<Int> {
        val url = API_URL.newBuilder()
            .addPathSegments("title_list/search")
            .addQueryParameter("lang", internalLang)
            .addQueryParameter("clang", internalLang)
            .addCommonQueryParameters()
            .build()

        val data = client.get(url, headers).parseAsMpResponse()

        return data.searchView!!.allTitlesGroup
            .flatMap { it.titles }
            .filter { it.language == mpLang }
            .map { it.titleId }
            .toSet()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val mangaPlusId = when {
            segments.getOrNull(0) == "viewer" -> segments.getOrNull(1)?.let { mangaViewer(it).titleId.toString() }
            segments.getOrNull(1) == "sns_share" -> url.queryParameter("title_id")
            segments.size >= 2 -> segments[1]
            else -> null
        } ?: return null

        return getMangaByMangaPlusId(mangaPlusId)
    }

    // Details & Chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaPlusId = manga.url.substringAfterLast("/")

        val viewDeferred = async { getTitleDetail(mangaPlusId) }
        val bakaByIdDeferred = manga.bakaId?.let { id -> async { MangaBakaMetadataFetcher.getSeries(id) } }

        val view = viewDeferred.await()
        val baka = bakaByIdDeferred?.await()
            ?: MangaBakaMetadataFetcher.seriesByMangaPlusId(mangaPlusId.toInt(), view.title.name)

        SMangaUpdate(
            manga = baka?.toDetailedSManga(mangaPlusId.toInt(), view) ?: view.toSManga(),
            chapters = view.toChapters(),
        )
    }

    private suspend fun getMangaByMangaPlusId(mangaPlusId: String): SManga {
        val view = getTitleDetail(mangaPlusId)
        val id = mangaPlusId.toIntOrNull()

        return id?.let { MangaBakaMetadataFetcher.seriesByMangaPlusId(it, view.title.name)?.toDetailedSManga(it, view) }
            ?: view.toSManga()
    }

    private suspend fun BakaSeries.toDetailedSManga(mangaPlusId: Int, view: MPTitleDetailView): SManga = toSManga(mangaPlusId, lang, MangaBakaMetadataFetcher.latestVolumeCover(id), view.extraInfo)

    private val SManga.bakaId: Int?
        get() = (memo[BAKA_MEMO_KEY] as? JsonPrimitive)?.content?.toIntOrNull()

    private suspend fun getTitleDetail(titleId: String): MPTitleDetailView {
        val url = API_URL.newBuilder()
            .addPathSegment("title_detailV3")
            .addQueryParameter("title_id", titleId)
            .addQueryParameter("lang", internalLang)
            .addCommonQueryParameters()
            .build()

        val view = client.get(url, headers).parseAsMpResponse().titleDetailView!!

        if (view.title.language != mpLang) {
            throw Exception("Title not available in this language.")
        }

        setSubscriptionReading(view.userSubscription.planType != "basic")

        return view
    }

    private fun MPTitleDetailView.toChapters(): List<SChapter> {
        val hidePaidChapters = preferences.getBoolean(PREF_HIDE_PAID_CHAPTERS, false)
        val chapters = if (
            hidePaidChapters &&
            titleLabels.planType == "deluxe" &&
            userSubscription.planType != "deluxe"
        ) {
            chapterListV2.filter { it.chapterType != ChapterType.DELUX }
        } else {
            chapterListV2
        }
            .map { it.toSChapter() }

        // HACK: All our issues start with Kaiju no.8...
        //
        // This whole thing started because friends and I wanted extra chapters to be numbered
        // properly, instead of having a bunch of "ex: ILLUSTRATION" which made downloads super
        // messy. In order to make it look nice I decided that I could use the same prefix
        // as the other chapters.
        //
        // Because Kaiju no.8 numbers their chapters with words i.e. "Episode One Hundred",
        // I can't be lazy and find the chapter word (Chapter, Case, Story, etc.) by just
        // walking the chapter name from the beginning until I hit a number. A previous
        // version used regex that walked the chapter name until it hits a digit or a
        // number word (one, two, three, etc.), but I felt that was silly and it was [super long](https://github.com/beerpiss/tachiyomi-unofficial-extensions/blob/4c60bd478b8a5fda18ec17325e891086d8f5936f/src/all/mangaplus/src/io/github/beerpsi/tachiyomi/extension/all/mangaplus/MangaPlus.kt#L503C1-L507C2),
        // so I switched to a [trie](https://en.wikipedia.org/wiki/Trie), which mostly solved my
        // issues...
        //
        // Cue Kaiju no.8 coming in to ruin my day again. There is ***one*** specific chapter
        // that doesn't start with the common chapter prefix (#28 - Twenty Eight: An Enlarging Threat!!)
        // which broke the trie and made the longest prefix empty.
        val chapterPrefix = Trie().let { t ->
            chapters
                .filterNot { it.name.startsWith("ex:") || it.name == "Twenty Eight: An Enlarging Threat!!" }
                .forEach { t.insert(it.name) }
            t.longestPrefix()
        }
            .ifEmpty { "Chapter" }

        for (i in chapters.indices) {
            val chapter = chapters[i]

            // Since we're going from the first chapter to the last, any extra chapters
            // is guaranteed to be preceded by a previous numbered chapter.
            // Hopefully there are no manga with the first chapter being an extra.
            if (chapter.name.startsWith("ex:") && i > 0) {
                val previousChapterNumber = chapters[i - 1].chapter_number

                chapter.apply {
                    chapter_number = previousChapterNumber + EXTRA_CHAPTER_INCREMENT
                    name = chapter.name.replace("ex:", "$chapterPrefix${DECIMAL_FORMAT.format(chapter_number)}:")
                }
            }
        }

        return chapters.reversed()
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url.substring(1)

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substring(1)

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/")

        return mangaViewer(chapterId).pages
            .mapNotNull { it.mangaPage }
            .mapIndexed { i, page ->
                Page(i, imageUrl = page.imageUrl)
            }
    }

    private suspend fun mangaViewer(chapterId: String): MPMangaViewer {
        val subscriptionReading = isSubscriptionReading()
        val url = API_URL.newBuilder()
            .addPathSegment("manga_viewer")
            .addQueryParameter("chapter_id", chapterId)
            .addQueryParameter(
                "split",
                if (preferences.getBoolean("${PREF_SPLIT_DOUBLE_PAGES}_$lang", false)) {
                    "yes"
                } else {
                    "no"
                },
            )
            .addQueryParameter(
                "img_quality",
                preferences.getString("${PREF_IMAGE_QUALITY}_$lang", "high")!!,
            )
            .addQueryParameter("ticket_reading", "no")
            .addQueryParameter("free_reading", if (subscriptionReading) "no" else "yes")
            .addQueryParameter("subscription_reading", if (subscriptionReading) "yes" else "no")
            .addQueryParameter("viewer_mode", "horizontal")
            .addCommonQueryParameters()
            .build()

        return client.get(url, headers, CacheControl.FORCE_NETWORK).parseAsMpResponse().mangaViewer!!
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "${PREF_IMAGE_QUALITY}_$lang"
            title = "Image quality"
            summary = "%s"
            entries = arrayOf("Low", "Medium", "High")
            entryValues = arrayOf("low", "high", "super_high")

            setDefaultValue("high")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "${PREF_SPLIT_DOUBLE_PAGES}_$lang"
            title = "Split double pages"
            summary = "Only a few titles supports disabling this setting."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_PAID_CHAPTERS
            title = "Hide paid chapters"
            summary = "Don't show chapters that require a MANGA Plus MAX Deluxe subscription."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SECRET
            title = "Access token"
            summary = "A valid access token is required to access the service. " +
                "Leave empty to let the extension generate one."

            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }.also(screen::addPreference)
    }

    // Subscription state

    private var subscriptionReading: Boolean? = null

    private fun setSubscriptionReading(value: Boolean) {
        if (subscriptionReading == null) {
            subscriptionReading = value
        }
    }

    private suspend fun isSubscriptionReading(): Boolean {
        subscriptionReading?.let { return it }

        val url = API_URL.newBuilder()
            .addPathSegment("settings_v2")
            .addQueryParameter("lang", internalLang)
            .addQueryParameter("viewer_mode", "horizontal")
            .addQueryParameter("clang", internalLang)
            .addCommonQueryParameters()
            .build()

        val data = client.get(url, headers).parseAsMpResponse()

        return (data.settingsViewV2!!.userSubscription.planType != "basic")
            .also { subscriptionReading = it }
    }

    // Auth

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.host != API_URL.host ||
            request.url.queryParameter("secret") != null ||
            request.url.pathSegments.last() == "register"
        ) {
            return chain.proceed(request)
        }

        val deviceToken = generateDeviceToken()
        val securityKey = calculateSecurityKey(deviceToken)
        val registerUrl = "$API_URL/register".toHttpUrl().newBuilder().apply {
            addQueryParameter("device_token", deviceToken)
            addQueryParameter("security_key", securityKey)
            addCommonQueryParameters()
        }.build()
        val registerRequest = Request.Builder()
            .method("PUT", ByteArray(0).toRequestBody())
            .url(registerUrl)
            .headers(headers)
            .build()
        val data = client
            .newCall(registerRequest)
            .execute()
            .parseAsMpResponse()
        val secret = data.registrationData!!.deviceSecret

        preferences
            .edit()
            .putString(PREF_SECRET, secret)
            .apply()

        val url = request.url.newBuilder()
            .addQueryParameter("secret", secret)
            .build()
        val newRequest = request.newBuilder().url(url).build()

        return chain.proceed(newRequest)
    }

    // Utilities

    private fun Response.parseAsMpResponse(): MPSuccessResult {
        val data = parseAsProto<MPResponse>()

        if (data.error != null) {
            if (data.error.action == MPErrorAction.UNAUTHORIZED && request.url.pathSegments.last() == "manga_viewer") {
                throw Exception("This chapter can only be accessed by subscribing for MANGA Plus MAX Deluxe.")
            }

            val popup = data.error.popups.find { it.language == mpLang }
                ?: data.error.englishPopup

            if (popup.subject == "Not Found" && request.url.pathSegments.last() == "title_detailV3") {
                throw IOException("This title was removed from the MANGA Plus catalogue.")
            }

            throw IOException("${popup.subject}: ${popup.body.ifEmpty { "An unknown error happened." }}")
        }

        check(data.success != null) { "An unknown error happened." }

        return data.success
    }

    private fun HttpUrl.Builder.addCommonQueryParameters() = apply {
        addQueryParameter("os", "android")
        addQueryParameter("os_ver", Build.VERSION.SDK_INT.toString())
        addQueryParameter("app_ver", APP_VER)

        preferences.getString(PREF_SECRET, null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                addQueryParameter("secret", it)
            }
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        const val PREFIX_CHAPTER_ID_SEARCH = "chapter-id:"
    }
}

private const val EXTRA_CHAPTER_INCREMENT = 0.01F
private const val DEVICE_TOKEN_BYTES = 16

private val DECIMAL_FORMAT = DecimalFormat(
    "#.###",
    DecimalFormatSymbols().apply { decimalSeparator = '.' },
)

private const val PREF_SECRET = "secret"
private const val PREF_IMAGE_QUALITY = "imageResolution"
private const val PREF_SPLIT_DOUBLE_PAGES = "splitImage"
private const val PREF_HIDE_PAID_CHAPTERS = "hidePaidChapters"

// MANGA Plus app's versionCode
private const val APP_VER = "261"

fun generateDeviceToken() = Random.nextBytes(DEVICE_TOKEN_BYTES).toHexString()

fun calculateSecurityKey(deviceToken: String): String {
    val md5 = MessageDigest.getInstance("MD5")

    return md5.digest("${deviceToken}$SECURITY_KEY_SALT".encodeToByteArray()).toHexString()
}
