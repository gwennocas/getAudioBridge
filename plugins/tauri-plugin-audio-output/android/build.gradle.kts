plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.getaudiobridge.output"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("proguard-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":tauri-android"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    testImplementation("junit:junit:4.13.2")
}
