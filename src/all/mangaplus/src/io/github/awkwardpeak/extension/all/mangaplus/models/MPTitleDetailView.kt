package io.github.awkwardpeak.extension.all.mangaplus.models

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

private val COMPLETED_REGEX = "completado|complete|completo".toRegex()
private val HIATUS_REGEX = "on a hiatus".toRegex(RegexOption.IGNORE_CASE)
private val REEDITION_REGEX = "revival|remasterizada".toRegex()
private const val ONE_SECOND = 1000L
private const val CHAPTER_NUMBER_MINIMUM_LENGTH = 3

@Serializable
data class MPTitleDetailView(
    @ProtoNumber(1) val title: MPTitle,
    @ProtoNumber(2) val titleImageUrl: String,
    @ProtoNumber(3) val overview: String = "",
    @ProtoNumber(5) val nextTimeStamp: Int = 0,
    @ProtoNumber(7) val viewingPeriodDescription: String = "",
    @ProtoNumber(8) val nonAppearanceInfo: String = "",
    @ProtoNumber(14) val isSimulReleased: Boolean = false,
    @ProtoNumber(16) val rating: MPContentRating = MPContentRating.ALL_AGES,
    @ProtoNumber(17) val chaptersDescending: Boolean = true,
    @ProtoNumber(32) val titleLabels: MPTitleLabels,
    @ProtoNumber(33) val userSubscription: MPUserSubscription,
    @ProtoNumber(34) val label: MPLabel? = MPLabel(MPLabelCode.WEEKLY_SHOUNEN_JUMP),
    @ProtoNumber(38) val chapterListV2: List<MPChapter> = emptyList(),
) {
    private val isWebtoon: Boolean
        get() = chapterListV2.isNotEmpty() && chapterListV2.all { it.isVerticalOnly }

    private val isOneShot: Boolean
        get() {
            if (chapterListV2.size != 1) {
                return false
            }

            // XXX: Currently all MANGA Plus Creators awards are one-shot. Remove this condition
            // if there happens to be an award that is a series.
            if (label?.label == MPLabelCode.MANGA_PLUS_CREATORS) {
                return true
            }

            if (titleLabels.releaseSchedule == MPReleaseSchedule.ONE_SHOT) {
                return true
            }

            return chapterListV2.first().name.contains("one-shot", false)
        }

    private val isReEdition: Boolean
        get() = viewingPeriodDescription.contains(REEDITION_REGEX)

    private val isCompleted: Boolean
        get() = nonAppearanceInfo.contains(COMPLETED_REGEX) || isOneShot ||
            titleLabels.releaseSchedule == MPReleaseSchedule.COMPLETED ||
            titleLabels.releaseSchedule == MPReleaseSchedule.DISABLED

    private val isSimulpub: Boolean
        get() = isSimulReleased || titleLabels.isSimulpub

    private val isOnHiatus: Boolean
        get() = nonAppearanceInfo.contains(HIATUS_REGEX)

    private fun createGenres(): List<String> = buildList {
        val isReleasingNewChapters = !isReEdition && !isOneShot && !isCompleted

        if (isSimulpub && isReleasingNewChapters) {
            add("Simulrelease")
        }

        if (isOneShot) {
            add("One-shot")
        }

        if (isReEdition) {
            add("Re-edition")
        }

        if (isWebtoon) {
            add("Webtoon")
        }

        if (label?.magazine != null) {
            add("Serialization: ${label.magazine}")
        }

        if (!isCompleted) {
            add("Schedule: ${titleLabels.releaseSchedule.displayName}")
        }

        add("Rating: ${rating.displayName}")

        if (titleLabels.planType == "deluxe") {
            add("MANGA Plus MAX Deluxe")
        }
    }

    private val viewingDescription: String?
        get() = viewingPeriodDescription.takeIf { titleLabels.planType == "deluxe" }

    // MANGA Plus-specific facts not surfaced by MangaBaka, appended to the description.
    val extraInfo: List<String> get() = buildList {
        label?.magazine?.let { add("Serialization: $it") }
        if (isSimulpub && !isCompleted) add("Simulrelease")
        if (isWebtoon) add("Webtoon")
        if (!isCompleted && titleLabels.releaseSchedule.displayName.isNotEmpty()) {
            add("Release: ${titleLabels.releaseSchedule.displayName}")
        }
        if (titleLabels.planType == "deluxe") add("MANGA Plus MAX Deluxe")
    }

    fun toSManga() = title.toSManga().apply {
        description = "${overview}\n\n${viewingDescription.orEmpty()}".trim()
        genre = createGenres().joinToString()
        status = when {
            isCompleted -> SManga.COMPLETED
            isOnHiatus -> SManga.ON_HIATUS
            else -> SManga.ONGOING
        }
    }
}

@Serializable
data class MPTitleLabels(
    @ProtoNumber(1) val releaseSchedule: MPReleaseSchedule = MPReleaseSchedule.DISABLED,
    @ProtoNumber(2) val isSimulpub: Boolean = false,
    @ProtoNumber(3) val planType: String = "basic",
)

@Serializable
enum class MPReleaseSchedule(val displayName: String) {
    DISABLED(""),
    EVERYDAY("Everyday"),
    WEEKLY("Weekly"),
    BIWEEKLY("Biweekly"),
    MONTHLY("Monthly"),
    BIMONTHLY("Bimonthly"),
    TRIMONTHLY("Trimonthly"),
    OTHER("Other"),
    COMPLETED("Completed"),
    ONE_SHOT("One-shot"),
}

@Serializable
enum class MPContentRating(val displayName: String) {
    ALL_AGES("All ages"),
    TEEN("Teen"),
    TEEN_PLUS("Teen Plus"),
    MATURE("Mature"),
}

@Serializable
data class MPChapter(
    @ProtoNumber(1) val titleId: Int,
    @ProtoNumber(2) val chapterId: Int,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val subTitle: String,
    @ProtoNumber(6) val startTimeStamp: Long,
    @ProtoNumber(9) val isVerticalOnly: Boolean = false,
    @ProtoNumber(16) val chapterType: ChapterType = ChapterType.FREE,
) {
    fun toSChapter() = SChapter.create().apply {
        url = "#/viewer/$chapterId"
        date_upload = startTimeStamp * ONE_SECOND
        chapter_number = chapterNumber
        name = if (chapter_number != -1F) {
            subTitle
        } else {
            "${this@MPChapter.name}: $subTitle"
        }
    }

    // M+ chapter titles have 5 types:
    // - #000: Normal chapter number
    // - #000-0: Chapter split into parts
    // - #000-000: Merged chapters (more than 2)
    // - #000,000: Merged chapters (list)
    // - ex: Extras
    //
    // For cases 1 and 2, we just parse them as funny numbers, i.e.
    // - #041 -> Chapter 41
    // - #041-1 -> Chapter 41.1
    //
    // For case 3 and 4, we use the first chapter as the chapter number, i.e.
    // - #001-004 -> Chapter 1
    // - #001,002 -> Chapter 1
    //
    // For extras, there's no concrete number, but ideally they should be numbered based on surrounding
    // chapters.
    private val chapterNumber: Float get() {
        if (!name.startsWith("#")) {
            return -1F
        }

        val numbers = name.removePrefix("#")

        return if (numbers.contains(",")) {
            numbers.split(",")[0].toFloat()
        } else if (numbers.contains("-")) {
            val parts = name.removePrefix("#").split("-", limit = 2)

            if (parts.size == 1 || parts[1].length >= CHAPTER_NUMBER_MINIMUM_LENGTH) {
                parts[0].toFloat()
            } else {
                "${parts[0]}.${parts[1]}".toFloat()
            }
        } else {
            numbers.toFloat()
        }
    }
}

@Serializable
enum class ChapterType {
    FREE,
    FREE_FOR_FIRST_TIME,
    ANOTHER, // idk
    DELUX,
}
