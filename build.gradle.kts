plugins {
    java
}

group = "dev.wsprotobuf"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("net.portswigger.burp.extensions:montoya-api:2026.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed", "skipped") }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Implementation-Title" to "WebSocket Protobuf",
            "Implementation-Version" to project.version,
            "Main-Class" to "dev.wsprotobuf.ui.Workbench"
        )
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    }
    from(listOf("LICENSE", "THIRD-PARTY-NOTICES.md")) { into("META-INF") }
    from("docs/third-party-licenses") { into("META-INF/licenses") }
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Open the local, offline Protobuf workbench."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.wsprotobuf.ui.Workbench")
}
