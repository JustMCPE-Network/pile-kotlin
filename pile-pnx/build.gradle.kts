plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":pile-format"))
    compileOnly(libs.pnx.server)
    testImplementation(libs.pnx.server)
    testImplementation(libs.mockito.core)
}

tasks.test {
    systemProperty("pile.lobby", System.getenv("PILE_LOBBY") ?: "")
}

tasks.shadowJar {
    archiveClassifier.set("plugin")
    archiveBaseName.set("pile")
    mergeServiceFiles()
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}