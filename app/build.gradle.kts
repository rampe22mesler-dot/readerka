plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "org.readerka"
    compileSdk = 35
    ndkVersion = "26.3.11579264"
    defaultConfig {
        val preview = providers.gradleProperty("readerkaPreview").orNull == "true"
        applicationId = if (preview) "org.readerka.preview" else "org.readerka"
        manifestPlaceholders["readerkaLabel"] = if (preview) "Readerka Preview" else "Readerka"
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 35
        versionCode = 10
        versionName = if (preview) "1.0.0-preview" else "1.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.version") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/interop-assets"))
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/dropbox-protocol"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/djvu"))
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":shared"))
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.github.bumptech.glide:gifdecoder:4.16.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.commonmark:commonmark:0.24.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.00"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val buildDjvuDecoder by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp"))
    inputs.file(rootProject.file("scripts/build-android-djvu.sh"))
    inputs.files(rootProject.fileTree("windows/third-party") { include("*.tar.gz", "SHA256SUMS.txt") })
    outputs.dir(layout.buildDirectory.dir("generated/djvu"))
    commandLine("bash", rootProject.file("scripts/build-android-djvu.sh").absolutePath,
        android.sdkDirectory.absolutePath, layout.buildDirectory.dir("generated/djvu").get().asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(buildDjvuDecoder) }

val generateDropboxProtocol by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("scripts/generate-android-dropbox.py"))
    inputs.file(rootProject.file("windows/desktop/src/main/kotlin/org/readerka/desktop/DropboxClient.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/dropbox-protocol"))
    commandLine("python3", rootProject.file("scripts/generate-android-dropbox.py").absolutePath)
}
tasks.named("preBuild") { dependsOn(generateDropboxProtocol) }
