plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

val releaseVersion = rootProject.file("../VERSION").readText().trim().also {
    require(Regex("\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}").matches(it)) {
        "Root VERSION must use XX.YY.ZZ.NN."
    }
}
val releaseVersionCode = 1_000_000 + releaseVersion.split('.').fold(0) { total, block -> total * 100 + block.toInt() }

android {
    namespace = "app.t4l"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.t4l"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "app.t4l"
    }

    buildTypes {
        debug {
            isDebuggable = true
            val debugApiBaseUrl = providers.gradleProperty("T4L_DEBUG_API_BASE_URL")
                .orElse("http://192.168.202.35:5099/")
                .get()
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
            buildConfigField("String", "OIDC_ISSUER", "\"\"")
            buildConfigField("String", "OIDC_CLIENT_ID", "\"\"")
            buildConfigField("String", "OIDC_REDIRECT_URI", "\"app.t4l://oauth2redirect\"")
        }
        release {
            isMinifyEnabled = true
            val releaseApiBaseUrl = providers.gradleProperty("T4L_RELEASE_API_BASE_URL")
                .orElse("https://t4l.invalid/")
                .get()
            require(releaseApiBaseUrl.startsWith("https://")) {
                "T4L_RELEASE_API_BASE_URL must use HTTPS."
            }
            val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
            val oidcIssuer = providers.gradleProperty("T4L_OIDC_ISSUER").orElse("https://oidc.invalid/").get()
            val oidcClientId = providers.gradleProperty("T4L_OIDC_CLIENT_ID").orElse("t4l-unconfigured").get()
            if (releaseRequested) {
                require(providers.gradleProperty("T4L_OIDC_ISSUER").isPresent) { "T4L_OIDC_ISSUER is required for release builds." }
                require(providers.gradleProperty("T4L_OIDC_CLIENT_ID").isPresent) { "T4L_OIDC_CLIENT_ID is required for release builds." }
                require(oidcIssuer.startsWith("https://")) { "T4L_OIDC_ISSUER must use HTTPS." }
            }
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            buildConfigField("String", "OIDC_ISSUER", "\"$oidcIssuer\"")
            buildConfigField("String", "OIDC_CLIENT_ID", "\"$oidcClientId\"")
            buildConfigField("String", "OIDC_REDIRECT_URI", "\"app.t4l://oauth2redirect\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        managedDevices.localDevices {
            maybeCreate("pixel2Api35").apply {
                device = "Pixel 2"
                apiLevel = 35
                systemImageSource = "aosp-atd"
            }
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "KaptUsageInsteadOfKsp")
    }
}

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
