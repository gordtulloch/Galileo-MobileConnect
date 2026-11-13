// design - the tokens scripts/gen_tokens.py writes from design/tokens.json, as plain data (docs/android/SDD.md 3.8).
// No UI toolkit, so the contrast and Night-is-red-only checks run under plain `gradlew :design:test`.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
