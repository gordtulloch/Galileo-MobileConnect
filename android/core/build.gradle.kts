// core - the hand-written S-* components of docs/SDD.md, as pure Kotlin/JVM: no Android imports, so it runs
// under plain `gradlew :core:test` on any machine and is reused unchanged by the Quest edition
// (docs/android/SDD.md ADR-AND-002, 3.0). Anything that needs the platform (Keystore, NsdManager) sits behind
// an interface defined here and is implemented in :app.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":api-client"))
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.okhttp.mockwebserver)
}

kotlin {
    jvmToolchain(17)
}

// The mock Master plays the scenarios in api/scenarios (S-MOCK); they travel with the module, on the JVM and in the app.
tasks.processResources {
    from("../../api/scenarios") { into("scenarios") }
}

tasks.test {
    useJUnitPlatform()
}
