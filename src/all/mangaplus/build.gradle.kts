import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MANGA Plus by SHUEISHA"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    // English only: metadata comes from MangaBaka, which is primarily English.
    source {
        lang = "en"
        baseUrl = "https://mangaplus.shueisha.co.jp"
    }

    deeplink {
        host("mangaplus.shueisha.co.jp")
        host("www.mangaplus.shueisha.co.jp")
        host("jumpg-webapi.tokyo-cdn.com")
        host("www.jumpg-webapi.tokyo-cdn.com")
        path("/titles/..*")
        path("/viewer/..*")
        path("/www/sns_share")
    }
}

// Injects the security key salt (from the MANGAPLUS_SALT env var, falling back to salt.txt) into a
// generated SecurityKeySalt.kt so it never has to be committed.
extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val kotlinSources = variant.sources.kotlin ?: return@onVariants
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val saltTask = tasks.register<UpdateSecurityKeySaltTask>("update${variantName}SecurityKeySalt") {
            salt.set(
                providers.environmentVariable("MANGAPLUS_SALT")
                    .orElse(
                        providers.fileContents(layout.projectDirectory.file("salt.txt"))
                            .asText
                            .map { it.trim() },
                    ),
            )
            outputs.upToDateWhen { false }
        }
        kotlinSources.addGeneratedSourceDirectory(saltTask, UpdateSecurityKeySaltTask::outputDir)
    }
}

abstract class UpdateSecurityKeySaltTask : DefaultTask() {

    @get:Input
    abstract val salt: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun action() {
        val saltFile = outputDir.get().file("SecurityKeySalt.kt").asFile
        saltFile.parentFile.mkdirs()
        saltFile.writeText(
            """
            |// THIS FILE IS AUTO-GENERATED, DO NOT COMMIT
            |package io.github.awkwardpeak.extension.all.mangaplus
            |
            |const val SECURITY_KEY_SALT = "${salt.get()}"
            |
            """.trimMargin(),
        )
    }
}
