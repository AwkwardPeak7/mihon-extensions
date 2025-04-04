package io.github.awkwardpeak.extension.all.mangaplus.mangadex

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

typealias MangasResponse = Data<Manga>

@Serializable
class Data<T>(
    val data: List<T>,
    val limit: Int,
    val offset: Int,
    val total: Int,
)

@Serializable
class Manga(
    val id: String,
    @Serializable(with = CoverRelations::class)
    val relationships: List<Relation>,
)

object CoverRelations : JsonTransformingSerializer<List<Relation>>(ListSerializer(Relation.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        return JsonArray(
            element.jsonArray.filter { jsonElement ->
                val jsonObject = jsonElement.jsonObject
                jsonObject["type"]?.jsonPrimitive?.content == "cover_art"
            },
        )
    }
}

@Serializable
class Relation(
    val attributes: CoverArtAttribute,
)

@Serializable
class CoverArtAttribute(
    val fileName: String,
)
