# Dual Earbuds Pro - build script. Uses only the C# compiler that ships with Windows (.NET Framework 4.8).
#   powershell -ExecutionPolicy Bypass -File build.ps1            build app + installer into .\dist
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Docs      also render screenshots + demo GIF into .\..\docs\images
param([switch]$Docs)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$fw   = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
$csc  = Join-Path $fw 'csc.exe'
$wpf  = Join-Path $fw 'WPF'
if (-not (Test-Path $csc)) { throw ".NET Framework 4.x compiler not found at $csc" }

$refs = @(
  "/r:$wpf\PresentationFramework.dll", "/r:$wpf\PresentationCore.dll", "/r:$wpf\WindowsBase.dll",
  "/r:$fw\System.Xaml.dll", '/r:System.Windows.Forms.dll', '/r:System.Drawing.dll', '/r:System.Core.dll'
)
$common = @('/nologo', '/optimize+', '/platform:anycpu', '/codepage:65001', '/warn:4', '/nowarn:1591', '/langversion:5',
            '/win32manifest:build\app.manifest')

$engine = Get-ChildItem src\Engine\*.cs | ForEach-Object FullName
$appSrc = Get-ChildItem src\App\*.cs    | ForEach-Object FullName
$setup  = Get-ChildItem src\Setup\*.cs  | ForEach-Object FullName
# The phone player page is shared with the Android app; it is embedded as the resource "web-player.html".
$player = (Resolve-Path ..\shared\web-player.html).Path
$appRes = @("/resource:$player,web-player.html")

New-Item -ItemType Directory -Force bin, dist | Out-Null

# One version for everything: AppInfo.Version in src\Engine\Support.cs -> the exe's file/product version.
$m = Select-String -Path src\Engine\Support.cs -Pattern 'public const string Version = "([0-9]+\.[0-9]+\.[0-9]+)"'
if (-not $m) { throw 'AppInfo.Version not found in src\Engine\Support.cs' }
$version = $m.Matches[0].Groups[1].Value
$verFile = Join-Path $PSScriptRoot 'bin\AssemblyVersion.cs'
Set-Content -Encoding UTF8 $verFile @"
[assembly: System.Reflection.AssemblyVersion("$version.0")]
[assembly: System.Reflection.AssemblyFileVersion("$version.0")]
[assembly: System.Reflection.AssemblyInformationalVersion("$version")]
[assembly: System.Reflection.AssemblyProduct("Dual Earbuds")]
[assembly: System.Reflection.AssemblyTitle("Dual Earbuds")]
[assembly: System.Reflection.AssemblyCompany("Qasim Raza Malik")]
[assembly: System.Reflection.AssemblyCopyright("Copyright (c) 2026 Qasim Raza Malik (MIT)")]
"@
Write-Host "Version $version"

function Compile($out, $sources, $extra) {
  $args = $common + $refs + @("/target:winexe", "/out:$out") + $extra + $sources
  & $csc @args
  if ($LASTEXITCODE -ne 0) { throw "Compile failed: $out" }
}

Write-Host '1/4  App (without icon, to draw the icon)'
Compile 'bin\DualEarbuds-noicon.exe' ($engine + $appSrc + @($verFile)) $appRes
Write-Host '2/4  Icon'
$p = Start-Process -FilePath 'bin\DualEarbuds-noicon.exe' -ArgumentList '--make-icon', "`"$PSScriptRoot\build\app.ico`"" -Wait -PassThru -WindowStyle Hidden
if ($p.ExitCode -ne 0 -or -not (Test-Path build\app.ico)) { throw 'Icon generation failed' }
Write-Host '3/4  App'
Compile 'dist\DualEarbuds.exe' ($engine + $appSrc + @($verFile)) ($appRes + @('/win32icon:build\app.ico'))
Write-Host '4/4  Installer'
$setupSources = $setup + @((Resolve-Path src\App\Theme.cs).Path, (Resolve-Path src\App\Ui.cs).Path, (Resolve-Path src\App\IconFactory.cs).Path,
                           (Resolve-Path src\Engine\QrCode.cs).Path, (Resolve-Path src\Engine\Support.cs).Path,
                           (Resolve-Path src\Engine\Firewall.cs).Path, (Resolve-Path src\Engine\Dsp.cs).Path, $verFile)
Compile 'dist\DualEarbuds-Setup.exe' $setupSources @('/win32icon:build\app.ico', '/resource:dist\DualEarbuds.exe,DualEarbuds.exe', '/main:DualEarbuds.Setup.SetupProgram')
Remove-Item bin\DualEarbuds-noicon.exe -ErrorAction SilentlyContinue

if ($Docs) {
  Write-Host 'Docs: screenshots + demo GIF'
  New-Item -ItemType Directory -Force ..\docs\images | Out-Null
  $p = Start-Process -FilePath 'dist\DualEarbuds.exe' -ArgumentList '--screenshots', "`"$PSScriptRoot\..\docs\images`"" -Wait -PassThru -WindowStyle Hidden
  if ($p.ExitCode -ne 0) { throw 'Screenshots failed' }
  $p = Start-Process -FilePath 'dist\DualEarbuds-Setup.exe' -ArgumentList '--screenshots', "`"$PSScriptRoot\..\docs\images`"" -Wait -PassThru -WindowStyle Hidden
  if ($p.ExitCode -ne 0) { throw 'Installer screenshots failed' }
}

Get-ChildItem dist | Format-Table Name, @{n='KB';e={[math]::Round($_.Length/1KB)}} -AutoSize
Write-Host 'Build OK'
