import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.kotlinserialization)
}

android {
  namespace = "info.bvlion.journalingpost"
  compileSdk = 36

  defaultConfig {
    applicationId = "info.bvlion.journalingpost"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  val localProperties = Properties().apply {
    rootProject.file("local.properties")
      .takeIf { it.exists() }?.inputStream()?.use { load(it) }
  }

  defaultConfig {
    buildConfigField("String", "POST_URL", "\"${localProperties.getProperty("POST_URL")}\"")
    buildConfigField("String", "TEAM_ID", "\"${localProperties.getProperty("TEAM_ID")}\"")
    buildConfigField("String", "TOKEN", "\"${localProperties.getProperty("TOKEN")}\"")
    buildConfigField("String", "CHANNEL", "\"${localProperties.getProperty("CHANNEL")}\"")
    buildConfigField("String", "USER", "\"${localProperties.getProperty("USER")}\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.serialization)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}