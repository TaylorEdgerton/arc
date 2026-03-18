plugins {
    `java-library`
    id("com.diffplug.spotless")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":common"))

    compileOnly("com.inductiveautomation.ignitionsdk:designer-api:8.3.0")
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:8.3.0")
    compileOnly("com.inductiveautomation.ignitionsdk:perspective-designer:8.3.0")
    compileOnly("com.inductiveautomation.ignitionsdk:perspective-common:8.3.0")
    // Gson is provided by the Ignition runtime classpath
    compileOnly("com.google.code.gson:gson:2.10.1")

    // Markdown → HTML rendering for chat display (modlImplementation bundles into .modl)
    modlImplementation("org.commonmark:commonmark:0.24.0")
    modlImplementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    testImplementation(platform("org.junit:junit-bom:5.12.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
