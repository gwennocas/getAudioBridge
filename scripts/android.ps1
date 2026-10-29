param([ValidateSet('init','build','install','devices')][string]$Action='build',[string]$Sdk=$env:ANDROID_HOME)
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
Push-Location $bridgeRoot
try {
    switch ($Action) {
        'devices' { & $adb devices -l }
        'init' { npm.cmd run tauri -- android init --ci; if ($LASTEXITCODE) { throw 'Falha no init Android' } }
        'build' {
            New-Item .logs -ItemType Directory -Force | Out-Null
            & cmd.exe /d /c 'npm.cmd run tauri -- android build --debug --target aarch64 --apk 2>&1' | Tee-Object .logs/android-build.log
            if ($LASTEXITCODE) {
                # Tauri CLI uses symlinks; copy the compiled .so on Windows without Developer Mode.
                if (!(Select-String -Path .logs/android-build.log -SimpleMatch 'Creation symbolic link is not allowed')) { throw 'Compilação Android falhou; consulte .logs/android-build.log' }
                $jni = 'src-tauri/gen/android/app/src/main/jniLibs/arm64-v8a'
                New-Item $jni -ItemType Directory -Force | Out-Null
                Copy-Item -LiteralPath 'target/aarch64-linux-android/debug/libget_audio_bridge_lib.so' -Destination "$jni/libget_audio_bridge_lib.so" -Force
                & './src-tauri/gen/android/gradlew.bat' -p src-tauri/gen/android ':app:assembleArm64Debug' -x ':app:rustBuildArm64Debug' --no-configuration-cache --console=plain
                if ($LASTEXITCODE) { throw 'Falha no empacotamento Gradle' }
            }
        }
        'install' {
            & $adb install -r 'src-tauri/gen/android/app/build/outputs/apk/arm64/debug/app-arm64-debug.apk'
            if ($LASTEXITCODE) { throw 'Falha na instalação do APK' }
            & $adb shell am start -n app.getaudiobridge.mobile/.MainActivity
        }
    }
} finally { Pop-Location }
