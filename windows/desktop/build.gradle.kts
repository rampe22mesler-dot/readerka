plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    implementation("com.formdev:flatlaf:3.6")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.apache.pdfbox:pdfbox:2.0.33")
    implementation("org.apache.pdfbox:jbig2-imageio:3.0.4")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("junit:junit:4.13.2")
}
application { mainClass = "org.readerka.desktop.MainKt" }

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version, "Main-Class" to application.mainClass.get())
    }
}
val desktopLibraries by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(layout.buildDirectory.dir("desktop/lib"))
}
tasks.register<Sync>("windowsLibraries") {
    dependsOn(desktopLibraries)
    from(desktopLibraries)
    into(layout.buildDirectory.dir("windows/lib"))
}
tasks.register<JavaExec>("interopFixture") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.readerka.desktop.InteropFixture"
    args(layout.buildDirectory.dir("interop").get().asFile.absolutePath)
}
tasks.register<JavaExec>("verifyInterop") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.readerka.desktop.InteropFixture"
    args(providers.gradleProperty("interopArchive").orElse("/tmp/readerka-android-roundtrip.zip").get(), "verify")
}
tasks.test {
    systemProperty("java.awt.headless", providers.systemProperty("java.awt.headless").orElse("true").get())
    inputs.property("desktopDisplay", providers.environmentVariable("DISPLAY").orElse(""))
    inputs.property("djvuDirectory", providers.environmentVariable("READERKA_DJVU_DIR").orElse(""))
}
