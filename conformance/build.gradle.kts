dependencies {
    testImplementation(project(":pile-format"))
}

tasks.test {
    systemProperty("pile.lobby", System.getenv("PILE_LOBBY") ?: "")
}
