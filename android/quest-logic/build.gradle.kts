// quest-logic - the Meta Quest edition's own rules that need no headset: where the dock goes, how a pointer lands on a
// pane, which object a ray picks, how a thumbstick moves time, when a held move must stop (docs/quest/SDD.md 3.3, 3.4).
// Pure Kotlin/JVM so it is tested under plain `gradlew :quest-logic:test`; the renderer and OpenXR loop use it.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
