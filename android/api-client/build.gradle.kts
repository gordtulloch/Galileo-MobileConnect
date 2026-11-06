// api-client - the request/response client for the Galileo wire API, GENERATED from ../../api/openapi.yaml
// (docs/SDD.md, ADR-APP-001). Nothing generated is committed: it lands in build/ and is compiled with
// the module. Edit the description, not the output.
//
// Runs on Windows, macOS or Linux with JDK 17+. Plugin and library versions below are starting points
// and must be pinned the first time Gradle is run (there is no Gradle wrapper in the repository yet:
// run `gradle wrapper` once from android/).

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.openapi.generator") version "7.10.0"
}

repositories {
    mavenCentral()
}

val generatedDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("../api/openapi.yaml").path)
    outputDir.set(generatedDir.get().asFile.path)
    packageName.set("com.galileo.mobileconnect.api")
    apiPackage.set("com.galileo.mobileconnect.api.client")
    modelPackage.set("com.galileo.mobileconnect.api.model")
    library.set("jvm-okhttp4")              // OkHttp: no Google services, so it is fine on Meta Quest
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            "dateLibrary" to "java8",       // java.time, not threetenbp
            "enumPropertyNaming" to "UPPERCASE",
        )
    )
    // Emit only the client and models; never the generator's sample build files or docs.
    globalProperties.set(mapOf("apis" to "", "models" to "", "supportingFiles" to ""))
}

sourceSets {
    main {
        kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
    }
}

tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    jvmToolchain(17)
}
