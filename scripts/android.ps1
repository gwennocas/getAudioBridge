param([ValidateSet('init','build','install','devices')][string]$Action='build',[string]$Sdk=$env:ANDROID_HOME,[switch]$Release)
$ErrorActionPreference='Stop'
$bridgeRoot = Split-Path $PSScriptRoot -Parent
if (!$Sdk) {
    $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (!(Test-Path $Sdk)) {
        $existingSdk = Join-Path (Split-Path $bridgeRoot -Parent) 'getYoink\.tools\android-sdk'
        if (Test-Path $existingSdk) { $Sdk = $existingSdk }
    }
}
if (!(Test-Path $Sdk)) { throw 'Defina ANDROID_HOME ou passe -Sdk com o caminho do Android SDK.' }
$env:ANDROID_HOME = (Resolve-Path $Sdk).Path
$env:NDK_HOME = (Get-ChildItem "$Sdk\ndk" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$androidBuildMutex = $null
$ownsAndroidBuildMutex = $false
Push-Location $bridgeRoot
try {
    switch ($Action) {
        'devices' { & $adb devices -l }
        'init' { npm.cmd run tauri -- android init --ci; if ($LASTEXITCODE) { throw 'Falha no init Android' } }
        'build' {
            $androidBuildMutex = [Threading.Mutex]::new($false, 'Local\getAudioBridgeAndroidBuild')
            try { $ownsAndroidBuildMutex = $androidBuildMutex.WaitOne(0) }
            catch [Threading.AbandonedMutexException] { $ownsAndroidBuildMutex = $true }
            if (!$ownsAndroidBuildMutex) { throw 'Another getAudioBridge Android build is already running. Wait before starting another build.' }
            if ($Release) {
                $signingFile = $env:GAB_SIGNING_PROPERTIES
                if (!$signingFile) { $signingFile = Join-Path $env:USERPROFILE '.android\getAudioBridge-release\keystore.properties' }
                if (!(Test-Path -LiteralPath $signingFile)) { throw 'Release signing is missing. Run scripts/new-signing-key.ps1 first and preserve that private key.' }
            }
            New-Item .logs -ItemType Directory -Force | Out-Null
            $buildProfile = if ($Release) { 'release' } else { 'debug' }
            $variant = if ($Release) { 'Release' } else { 'Debug' }
            $buildLog = ".logs/android-$buildProfile-$PID.log"
            $buildCommand = if ($Release) { 'npm.cmd run tauri -- android build --target aarch64 --apk 2>&1' } else { 'npm.cmd run tauri -- android build --debug --target aarch64 --apk 2>&1' }
            & cmd.exe /d /c $buildCommand | Tee-Object $buildLog
            if ($LASTEXITCODE) {
                # Tauri CLI uses symlinks; copy the compiled .so on Windows without Developer Mode.
                if (!(Select-String -Path $buildLog -SimpleMatch 'Creation symbolic link is not allowed')) { throw "Android build failed; see $buildLog" }
                $jni = 'src-tauri/gen/android/app/src/main/jniLibs/arm64-v8a'
                New-Item $jni -ItemType Directory -Force | Out-Null
                Copy-Item -LiteralPath "target/aarch64-linux-android/$buildProfile/libget_audio_bridge_lib.so" -Destination "$jni/libget_audio_bridge_lib.so" -Force
                & './src-tauri/gen/android/gradlew.bat' -p src-tauri/gen/android ":app:assembleArm64$variant" -x ":app:rustBuildArm64$variant" --no-configuration-cache --console=plain
                if ($LASTEXITCODE) { throw 'Falha no empacotamento Gradle' }
            }
        }
        'install' {
            $buildProfile = if ($Release) { 'release' } else { 'debug' }
            & $adb install -r "src-tauri/gen/android/app/build/outputs/apk/arm64/$buildProfile/app-arm64-$buildProfile.apk"
            if ($LASTEXITCODE) { throw 'Falha na instalação do APK' }
            & $adb shell am start -n app.getaudiobridge.mobile/.MainActivity
        }
    }
} finally {
    if ($ownsAndroidBuildMutex) { $androidBuildMutex.ReleaseMutex() }
    if ($androidBuildMutex) { $androidBuildMutex.Dispose() }
    Pop-Location
}
