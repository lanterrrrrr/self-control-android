param([string]$Sdk, [switch]$Test)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }

$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'android/app/src/main'
$build = Join-Path $root '.work/build'
$signing = Join-Path $root '.work/signing'
$artifacts = Join-Path $root 'artifacts'
$dependencies = Join-Path $root '.work/dependencies'
$androidJar = Join-Path $Sdk 'platforms/android-36/android.jar'
$buildTools = Join-Path $Sdk 'build-tools/36.0.0'
$temp = Join-Path $root '.work/tmp'
$take = @($build, $signing, $artifacts, $temp, $dependencies)
$take | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }
$env:TEMP = $temp
$env:TMP = $temp
if (-not (Test-Path -LiteralPath $androidJar)) { throw "Missing Android SDK platform 36 under $Sdk" }
if (-not (Test-Path -LiteralPath (Join-Path $buildTools 'aapt2.exe'))) { throw "Missing build-tools 36.0.0 under $Sdk" }
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
    $keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
} else {
    $java = (Get-Command java.exe).Source
    $javac = (Get-Command javac.exe).Source
    $keytool = (Get-Command keytool.exe).Source
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
}

# Each build gets an isolated directory so removed Java classes cannot enter the APK.
$run = Join-Path $build ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
$testClasses = Join-Path $run 'test-classes'
$generated = Join-Path $run 'generated'
$dex = Join-Path $run 'dex'
$stagedAssets = Join-Path $run 'assets'
@($classes, $testClasses, $generated, $dex, $stagedAssets) | ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
$main = Join-Path $source 'java/com/focusguard/selfcontrol'
if ($Test) {
    $testPath = Join-Path $root 'android/tests'
    & $javac -encoding UTF-8 --release 17 -cp $testClasses -d $testClasses (Join-Path $main 'RuleEngine.java') (Join-Path $main 'ChallengeText.java') (Join-Path $testPath 'RuleEngineTest.java') (Join-Path $testPath 'ChallengeTextTest.java')
    Push-Location -LiteralPath $root
    try {
        & $java -cp $testClasses com.focusguard.selfcontrol.RuleEngineTest
        & $java -cp $testClasses com.focusguard.selfcontrol.ChallengeTextTest (Join-Path $source 'assets/quotes.tsv')
    } finally {
        Pop-Location
    }
}

# Speech assets and native libraries stay out of the source tree and are packaged reproducibly.
function Get-SpeechDependency([string]$Name, [string]$Url, [string]$Hash, [long]$Size) {
    $path = Join-Path $dependencies $Name
    if (-not (Test-Path -LiteralPath $path) -or (Get-Item -LiteralPath $path).Length -ne $Size) {
        $partial = $path + '.partial'
        & curl.exe --noproxy '*' --fail --silent --show-error --location --connect-timeout 10 --retry 2 --max-time 600 -o $partial $Url
        if ((Get-Item -LiteralPath $partial).Length -ne $Size) { throw "Incomplete speech dependency: $Name" }
        Move-Item -LiteralPath $partial -Destination $path -Force
    }
    if ($Hash -and (Get-FileHash -LiteralPath $path).Hash -ne $Hash) {
        throw "Speech dependency checksum mismatch: $path"
    }
    return $path
}

$voskArchive = Get-SpeechDependency 'vosk-0.3.75.aar' 'https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar' 'AB2F8B91AC8051561AA325546B35FED9A68B36B8121BAC5C6FB927525C4ADFAD' 13472638
$jnaArchive = Get-SpeechDependency 'jna-5.18.1.aar' 'https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar' '7F053E3EC99E14DD71259C82C1C8A02738D64A13C31226B2ACC170F3060951E0' 522677
$modelArchive = Get-SpeechDependency 'chinese-model.zip' 'https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip' '3AF8B0E7E0F835AE9D414CE5DF580237A3CFB08D586C9FBBB0F7FF29AD5B14BA' 43898754
$vosk = Join-Path $run 'vosk'
$jna = Join-Path $run 'jna'
[IO.Compression.ZipFile]::ExtractToDirectory($voskArchive, $vosk)
[IO.Compression.ZipFile]::ExtractToDirectory($jnaArchive, $jna)
Copy-Item -LiteralPath $modelArchive -Destination (Join-Path $stagedAssets 'mandarin_model.zip')
Copy-Item -LiteralPath (Join-Path $source 'assets/quotes.tsv') -Destination $stagedAssets
Copy-Item -LiteralPath (Join-Path $root 'android/licenses') -Destination (Join-Path $stagedAssets 'licenses') -Recurse
$speechJars = @((Join-Path $vosk 'classes.jar'), (Join-Path $jna 'classes.jar'))

$resources = Join-Path $run 'resources.zip'
$unsigned = Join-Path $run 'unsigned.apk'
$aligned = Join-Path $run 'aligned.apk'
$aapt = Join-Path $buildTools 'aapt2.exe'
# The native SDK tools on Windows require relative paths under non-ASCII project directories.
Push-Location -LiteralPath $root
try {
    & $aapt compile --dir 'android/app/src/main/res' -o ([IO.Path]::GetRelativePath($root, $resources))
    & $aapt link -I $androidJar --manifest 'android/app/src/main/AndroidManifest.xml' --java ([IO.Path]::GetRelativePath($root, $generated)) -A ([IO.Path]::GetRelativePath($root, $stagedAssets)) -o ([IO.Path]::GetRelativePath($root, $unsigned)) ([IO.Path]::GetRelativePath($root, $resources))
} finally {
    Pop-Location
}
$javaFiles = @(Get-ChildItem -LiteralPath $main,$generated -Filter '*.java' -Recurse -File | ForEach-Object FullName)
& $javac -encoding UTF-8 --release 17 -cp ((@($androidJar) + $speechJars) -join [IO.Path]::PathSeparator) -d $classes @javaFiles
$classFiles = @(Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse -File | ForEach-Object FullName)
& (Join-Path $buildTools 'd8.bat') --min-api 31 --lib $androidJar --output $dex @classFiles @speechJars
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    Get-ChildItem -LiteralPath $dex -Filter '*.dex' -File | ForEach-Object {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $_.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
        $libs = @((Join-Path $vosk "jni/$abi/libvosk.so"), (Join-Path $jna "jni/$abi/libjnidispatch.so"))
        foreach ($lib in $libs) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $lib, "lib/$abi/$(Split-Path -Leaf $lib)", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
} finally {
    $archive.Dispose()
}
Push-Location -LiteralPath $root
try {
    & (Join-Path $buildTools 'zipalign.exe') -P 16 -f 4 ([IO.Path]::GetRelativePath($root, $unsigned)) ([IO.Path]::GetRelativePath($root, $aligned))
} finally {
    Pop-Location
}

$keystore = Join-Path $signing 'local-test.jks'
if (-not (Test-Path -LiteralPath $keystore)) {
    & $keytool -genkeypair -keystore $keystore -storetype JKS -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Self Control Local Test,O=Android,C=CN'
}
$manifest = [xml][IO.File]::ReadAllText((Join-Path $source 'AndroidManifest.xml'))
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Missing or invalid APK version in AndroidManifest.xml' }
$output = Join-Path $artifacts "SelfControl-$version.apk"
$signed = Join-Path $run 'signed.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --v4-signing-enabled false --out $signed $aligned
& (Join-Path $buildTools 'apksigner.bat') verify --verbose $signed
Copy-Item -LiteralPath $signed -Destination $output -Force
Write-Output $output
