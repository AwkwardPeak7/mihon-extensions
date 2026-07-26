package io.github.awkwardpeak.extension.all.mangaplus.mangabaka

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement

object MangaBakaFilters {

    fun getFilterList(genresData: JsonElement?): FilterList {
        val genres = genresData
            ?.let { runCatching { it.parseAs<List<BakaGenre>>() }.getOrNull() }
            .orEmpty()

        return FilterList(
            buildList {
                add(SortFilter())
                add(StatusFilter())
                add(ContentRatingFilter())
                if (genres.isNotEmpty()) {
                    add(GenreFilter(genres))
                }
            },
        )
    }

    fun toQueryParams(filters: FilterList): List<Pair<String, String>> = buildList {
        add("sort_by" to (filters.firstInstanceOrNull<SortFilter>()?.selected ?: SORTS[0].second))

        filters.firstInstanceOrNull<StatusFilter>()?.selected?.let { add("status" to it) }

        filters.firstInstanceOrNull<ContentRatingFilter>()?.state
            ?.filter { it.state }
            ?.forEach { add("content_rating" to it.value) }

        filters.firstInstanceOrNull<GenreFilter>()?.state?.forEach {
            when (it.state) {
                Filter.TriState.STATE_INCLUDE -> add("genre" to it.value)
                Filter.TriState.STATE_EXCLUDE -> add("genre_not" to it.value)
            }
        }
    }

    private val SORTS = arrayOf(
        "Relevance" to "relevance_desc",
        "Popularity" to "popularity_asc",
        "Latest" to "latest",
        "Score" to "score_desc",
        "Trending (7d)" to "trending_7d",
        "Title" to "name_asc",
        "Year" to "published_year_desc",
    )

    private class SortFilter : Filter.Select<String>("Sort", SORTS.map { it.first }.toTypedArray()) {
        val selected: String get() = SORTS[state].second
    }

    private val STATUSES = arrayOf(
        "Any" to null,
        "Releasing" to "releasing",
        "Completed" to "completed",
        "Hiatus" to "hiatus",
        "Cancelled" to "cancelled",
        "Upcoming" to "upcoming",
    )

    private class StatusFilter : Filter.Select<String>("Status", STATUSES.map { it.first }.toTypedArray()) {
        val selected: String? get() = STATUSES[state].second
    }

    private class ContentRating(name: String, val value: String) : Filter.CheckBox(name)

    private class ContentRatingFilter :
        Filter.Group<ContentRating>(
            "Content rating",
            listOf(
                ContentRating("Safe", "safe"),
                ContentRating("Suggestive", "suggestive"),
                ContentRating("Erotica", "erotica"),
                ContentRating("Pornographic", "pornographic"),
            ),
        )

    private class Genre(name: String, val value: String) : Filter.TriState(name)

    private class GenreFilter(genres: List<BakaGenre>) :
        Filter.Group<Genre>(
            "Genres",
            genres.map { Genre(it.label, it.value) },
        )
}
