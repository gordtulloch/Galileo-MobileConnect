// api-client - the request/response client for the Galileo wire API, GENERATED from ../../api/openapi.yaml
// (docs/SDD.md, ADR-APP-001). Nothing generated is committed: it lands in build/ and is compiled with
// the module. Edit the description, not the output.
//
// Runs on Windows, macOS or Linux with JDK 17+. Versions live in gradle/libs.versions.toml; the Gradle 8.7
// wrapper is committed in android/ (build with `gradlew` and JDK 17).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
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
    // Free-form JSON (Arg.default, OperationOk.result, args maps): kotlin.Any has no serializer, so use JsonElement.
    typeMappings.set(mapOf("AnyType" to "JsonElement"))
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement"))
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(17)
}
