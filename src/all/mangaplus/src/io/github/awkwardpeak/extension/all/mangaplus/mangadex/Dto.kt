package io.github.awkwardpeak.extension.all.mangaplus.mangadex

import kotlinx.serialization.Serializable

typealias CoverArtResponse = Data<CoverArt>

@Serializable
class Data<T>(
    val data: List<T>,
    val limit: Int,
    val offset: Int,
    val total: Int,
)

@Serializable
class CoverArt(
    val id: String,
    val attributes: CoverArtAttribute,
    val relationships: List<Relation>,
)

@Serializable
class CoverArtAttribute(
    val volume: String?,
    val fileName: String,
)

@Serializable
class Relation(
    val id: String,
    val type: String,
)
