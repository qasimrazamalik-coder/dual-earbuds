plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Copies the one shared browser player page (repo-root shared/web-player.html, also served by the
 * Windows host) into this module's generated assets. Never fork the page: edit the shared file.
 */
abstract class CopyWebPlayer : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val page: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        page.get().asFile.copyTo(out.resolve("web-player.html"), overwrite = true)
    }
}

val copyWebPlayer = tasks.register<CopyWebPlayer>("copyWebPlayer") {
    page.set(layout.projectDirectory.file("../../shared/web-player.html"))
    outputDir.set(layout.buildDirectory.dir("generated/webPlayer/assets"))
}

android {
    namespace = "com.dualearbuds.host"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyWebPlayer, CopyWebPlayer::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR encoding only (QRCodeWriter). Same zxing core that zxing-android-embedded 4.3.0 already brings into the app.
    implementation("com.google.zxing:core:3.4.1")

    testImplementation("junit:junit:4.13.2")
}
