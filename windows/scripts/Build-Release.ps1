param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$WixHome = $env:WIX,
    [switch]$Offline,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localRoot = Split-Path $projectRoot -Parent
if (!$JdkHome) {
    $localJdk = Get-ChildItem (Join-Path $localRoot '.tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $JdkHome = $localJdk.FullName }
}
if (!$WixHome) { $WixHome = Join-Path $localRoot '.tools\release-tools\wix314' }
foreach ($tool in @('java.exe', 'jlink.exe', 'jpackage.exe')) {
    if (!(Test-Path (Join-Path $JdkHome "bin\$tool"))) { throw "JDK 17 tool missing: $tool. Set -JdkHome." }
}
foreach ($tool in @('candle.exe', 'light.exe')) {
    if (!(Test-Path (Join-Path $WixHome $tool))) { throw "WiX 3 tool missing: $tool. Set -WixHome." }
}
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$WixHome;$env:PATH"
$versionLine = Get-Content (Join-Path $projectRoot 'gradle.properties') | Where-Object { $_ -match '^version=' }
$version = ($versionLine -split '=', 2)[1].Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid release version in gradle.properties.' }
if (!$SkipBuild) {
    Push-Location $projectRoot
    try {
        $gradleArgs = @('--no-daemon', '--console=plain', ':shared:test', ':desktop:test', ':desktop:windowsLibraries', '-Djava.awt.headless=true', "-Dorg.gradle.java.installations.paths=$JdkHome")
        if ($Offline) { $gradleArgs += '--offline' }
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Release build/tests failed.' }
    } finally { Pop-Location }
}
$libraries = Join-Path $projectRoot 'desktop\build\windows\lib'
$mainJar = "desktop-$version.jar"
if (!(Test-Path (Join-Path $libraries $mainJar))) { throw 'Run the build first.' }
$work = Join-Path $projectRoot ('desktop\build\release-work\' + [guid]::NewGuid().ToString())
$output = Join-Path $projectRoot 'desktop\build\release'
$inputPath = Join-Path $work 'input'
New-Item -ItemType Directory -Path $inputPath,$output -Force | Out-Null
Copy-Item (Join-Path $libraries '*.jar') $inputPath
Copy-Item (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') $inputPath
Copy-Item (Join-Path $projectRoot 'README.md') $inputPath
New-Item -ItemType Directory -Path (Join-Path $inputPath 'third-party') -Force | Out-Null
Copy-Item (Join-Path $projectRoot 'third-party\licenses') (Join-Path $inputPath 'third-party') -Recurse
if (Test-Path (Join-Path $projectRoot 'docs')) { Copy-Item (Join-Path $projectRoot 'docs') $inputPath -Recurse }
Copy-Item (Join-Path $projectRoot 'desktop\src\main\resources\native\djvu\COPYING.txt') (Join-Path $inputPath 'DJVULIBRE-COPYING.txt')
$runtime = Join-Path $work 'runtime'
& "$JdkHome\bin\jlink.exe" --add-modules 'java.base,java.desktop,java.sql,java.logging,java.naming,java.net.http,java.management,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.localedata' --strip-debug --no-header-files --no-man-pages --compress=2 --output $runtime
if ($LASTEXITCODE -ne 0) { throw 'jlink failed.' }
$diagnostics = Join-Path $work 'diagnostics.properties'
Set-Content $diagnostics 'win-console=true' -Encoding ascii
$imageRoot = Join-Path $work 'images'
& "$JdkHome\bin\jpackage.exe" --type app-image --name Readerka --app-version $version --vendor Readerka --description 'Readerka: books, quotations and notes' --input $inputPath --main-jar $mainJar --main-class org.readerka.desktop.MainKt --runtime-image $runtime --java-options '-Dfile.encoding=UTF-8' --add-launcher "Readerka-Diagnostics=$diagnostics" --dest $imageRoot
if ($LASTEXITCODE -ne 0) { throw 'Application image packaging failed.' }
$image = Join-Path $imageRoot 'Readerka'
$actualVersion = & (Join-Path $image 'Readerka-Diagnostics.exe') --version
if ($LASTEXITCODE -ne 0 -or "$actualVersion".Trim() -ne "Readerka $version") { throw "Packaged launcher version mismatch: $actualVersion" }
# Keep this upgrade UUID in future releases. User data is never an MSI component.
$msiOutput = Join-Path $work 'msi'
& "$JdkHome\bin\jpackage.exe" --type msi --name Readerka --app-version $version --vendor Readerka --app-image $image --dest $msiOutput --resource-dir (Join-Path $PSScriptRoot 'installer-resources') --install-dir ReaderkaApp --win-per-user-install --win-menu --win-menu-group Readerka --win-shortcut --win-dir-chooser --win-upgrade-uuid 'dc263179-9f64-4dbf-ad73-e8814cb95a77' --about-url 'https://github.com/rampe22mesler-dot/readerka' --win-help-url 'https://github.com/rampe22mesler-dot/readerka/issues' --verbose
if ($LASTEXITCODE -ne 0) { throw 'MSI packaging failed.' }
$msi = @(Get-ChildItem $msiOutput -Filter '*.msi')
if ($msi.Count -ne 1) { throw 'Expected one MSI installer.' }
Copy-Item $msi[0].FullName (Join-Path $output "readerka-$version-windows-x64.msi") -Force
$portable = Join-Path $output "readerka-$version-windows-x64-portable.zip"
Compress-Archive -Path $image -DestinationPath $portable -CompressionLevel Optimal -Force
$artifacts = @(Get-ChildItem $output -File | Where-Object { $_.Name -in @("readerka-$version-windows-x64.msi", "readerka-$version-windows-x64-portable.zip") })
$sums = $artifacts | Sort-Object Name | ForEach-Object { (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name }
Set-Content (Join-Path $output 'SHA256SUMS.txt') $sums -Encoding ascii
Write-Host "Release image: $image"
Write-Host "Release assets: $output"
