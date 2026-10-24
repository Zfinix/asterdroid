import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key lives next to the experiment, outside git, so the phone
// keeps one signature across reinstalls instead of the per-machine debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.aster.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aster.probe"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    // The agent and the control client ride here because an app may only exec
    // from its native library directory, never from its own data directory.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    buildFeatures { compose = true }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "aster-release.jks"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // run-as is how the socket is driven during development.
            isMinifyEnabled = false
        }
        release {
            // The services are found by manifest name and the agent is a plain
            // exec, so there is nothing for R8 to gain and a wrong keep rule
            // would silently drop a service.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Skills are written once, as `skills/<name>/SKILL.md`, and the device
// instructions at the experiment's root. Copying them by hand is how the
// shipped ones went stale, so the build does it. The app wants them flat, so
// the rename happens here rather than in a second copy of every file.
val syncDocs by tasks.registering(Copy::class) {
    from(rootProject.file("skills")) {
        include("*/SKILL.md")
        eachFile { path = "${sourcePath.substringBeforeLast('/')}.md" }
    }
    from(rootProject.file("device-AGENTS.md")) { rename { "AGENTS.md" } }
    // The same catalog the CLI and desktop pickers read, so the phone offers
    // the same providers rather than a copy that drifts. It lives in the aster
    // repo; the checked-in copy under assets is the fallback when it is absent.
    val catalog = file(System.getenv("ASTER_REPO") ?: "${rootProject.projectDir}/../aster").resolve("providers.json")
    if (catalog.exists()) from(catalog)
    into(layout.projectDirectory.dir("src/main/assets"))
    includeEmptyDirs = false
}

tasks.named("preBuild") { dependsOn(syncDocs) }

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    // QR codes both ways: core draws the pairing code, the embedded view reads
    // setup codes with the camera. Both are Apache-2.0 with no Play services,
    // so the scanner still works on phones without Google and on F-Droid.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws on the JVM; the setup link
    // and relay parsers are tested against the real thing.
    testImplementation("org.json:json:20240303")
}
