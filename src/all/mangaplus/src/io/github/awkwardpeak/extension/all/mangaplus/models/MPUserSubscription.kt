package io.github.awkwardpeak.extension.all.mangaplus.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class MPUserSubscription(
    @ProtoNumber(1) val planType: String = "basic",
)
