import gradle.kotlin.dsl.accessors._0e5f5f81437fd59f7c2133e1ac765c7d.kotlinOptions
import gradle.kotlin.dsl.accessors._0e5f5f81437fd59f7c2133e1ac765c7d.kotlinter

plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlinx-serialization")
    id("org.jmailen.kotlinter")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "io.github.awkwardpeak.lib.${project.name}"

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            assets.setSrcDirs(listOf("assets"))
        }
    }

    buildFeatures {
        androidResources = false
    }

    kotlinOptions {
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

kotlinter {
    experimentalRules = true
    disabledRules = arrayOf(
        "experimental:argument-list-wrapping", // Doesn't play well with Android Studio
        "experimental:comment-wrapping",
    )
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}

tasks.register("printDependentExtensions") {
    doLast {
        project.printDependentExtensions()
    }
}
