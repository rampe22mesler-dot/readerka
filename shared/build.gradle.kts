plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
}
kotlin { jvmToolchain(17) }
dependencies {
    api("androidx.room:room-common:2.7.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    api("org.commonmark:commonmark:0.24.0")
    testImplementation("junit:junit:4.13.2")
}
