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

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:8.3.0")
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:8.3.0")
}
