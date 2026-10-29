param([string]$Directory = (Join-Path $env:USERPROFILE '.android\getAudioBridge-release'))
$ErrorActionPreference = 'Stop'
$keyPath = Join-Path $Directory 'getaudiobridge-release.p12'
$propertiesPath = Join-Path $Directory 'keystore.properties'
if (Test-Path -LiteralPath $propertiesPath) {
    if (!(Test-Path -LiteralPath $keyPath)) { throw 'Signing properties exist but key is missing. Restore the original key; do not replace it.' }
    Write-Output "Existing signing key preserved at $Directory"
    exit 0
}
if (Test-Path -LiteralPath $keyPath) { throw 'Key already exists. Restore its signing properties instead of generating a replacement.' }
New-Item -ItemType Directory -Path $Directory -Force | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $Directory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE) { throw 'Cannot restrict signing directory permissions' }
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
$env:GAB_NEW_KEY_PASSWORD = $password
try {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $keyPath -alias getaudiobridge -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=getAudioBridge' -storetype PKCS12 -storepass:env GAB_NEW_KEY_PASSWORD -keypass:env GAB_NEW_KEY_PASSWORD
    if ($LASTEXITCODE) { throw 'Signing key generation failed' }
    $storePath = [IO.Path]::GetFullPath($keyPath).Replace('\','/')
    $properties = "storeFile=$storePath`nstorePassword=$password`nkeyAlias=getaudiobridge`nkeyPassword=$password`n"
    [IO.File]::WriteAllText($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))
} finally { Remove-Item Env:GAB_NEW_KEY_PASSWORD; $password = $null; $bytes = $null }
Write-Output "Signing key created at $Directory. Back up this private directory securely; never upload it to GitHub."
