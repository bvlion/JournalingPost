import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.kotlinserialization)
  alias(libs.plugins.ksp)
}

// Hosted解析APIのBase URL。実ドメインは公開リポジトリへcommitしないため、`local.properties` の
// `hostedAnalysisBaseUrl` か `-PhostedAnalysisBaseUrl=...` から注入する。未設定時は解決しない
// プレースホルダを使い、release build では `verifyHostedAnalysisBaseUrl` が未設定を弾く。
val placeholderHostedAnalysisBaseUrl = "https://hosted.invalid"
val hostedAnalysisBaseUrl: String = run {
  (project.findProperty("hostedAnalysisBaseUrl") as? String)?.trim()?.let { if (it.isNotEmpty()) return@run it }
  val localProperties = rootProject.file("local.properties")
  if (!localProperties.exists()) return@run ""
  Properties().apply { localProperties.inputStream().use { load(it) } }
    .getProperty("hostedAnalysisBaseUrl")?.trim().orEmpty()
}

// release署名用 keystore。内部テスト配布ワークフローが RELEASE_JKS secret を Base64 decode して
// リポジトリ直下へ配置し、alias / password は環境変数で渡す。keystore が無い環境(ローカルでの
// release build 確認や main push 時の CI)では署名せずに build する。
val releaseKeystoreFile = rootProject.file("release.jks")

android {
  namespace = "info.bvlion.journalingpost"
  compileSdk = 36

  defaultConfig {
    applicationId = "info.bvlion.journalingpost"
    minSdk = 31
    targetSdk = 36
    versionCode = 3
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField(
      "String",
      "HOSTED_ANALYSIS_BASE_URL",
      "\"${hostedAnalysisBaseUrl.ifEmpty { placeholderHostedAnalysisBaseUrl }}\"",
    )
  }

  val releaseSigningConfig = if (releaseKeystoreFile.exists()) {
    signingConfigs.create("release") {
      storeFile = releaseKeystoreFile
      storePassword = System.getenv("KEYSTORE_PASSWORD")
      keyAlias = System.getenv("KEYSTORE_ALIAS")
      keyPassword = System.getenv("KEYSTORE_PASSWORD")
    }
  } else {
    null
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      signingConfig = releaseSigningConfig
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

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

// release build では Hosted の Base URL 未設定のまま APK/AAB を作らせない。
// debug / local 開発ではプレースホルダのままで構わない(Hosted解析は失敗するだけ)。
val verifyHostedAnalysisBaseUrl = tasks.register("verifyHostedAnalysisBaseUrl") {
  doLast {
    if (
      hostedAnalysisBaseUrl.isEmpty() ||
      hostedAnalysisBaseUrl == placeholderHostedAnalysisBaseUrl ||
      !hostedAnalysisBaseUrl.startsWith("https://")
    ) {
      throw GradleException(
        "hostedAnalysisBaseUrl が設定されていません。release build では local.properties に " +
          "hostedAnalysisBaseUrl=https://... を設定するか、-PhostedAnalysisBaseUrl=https://... を渡してください。",
      )
    }
  }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyHostedAnalysisBaseUrl) }

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.work.runtime)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.serialization)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}
