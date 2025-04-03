package io.github.awkwardpeak.extension.all.mangaplus

import eu.kanade.tachiyomi.source.SourceFactory
import io.github.awkwardpeak.extension.all.mangaplus.models.MPLanguage

class MangaPlusFactory : SourceFactory {
    override fun createSources() = enumValues<MPLanguage>().map {
        MangaPlus(it)
    }
}
