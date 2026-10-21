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

New-Item -ItemType Directory -Force bin, dist | Out-Null

function Compile($out, $sources, $extra) {
  $args = $common + $refs + @("/target:winexe", "/out:$out") + $extra + $sources
  & $csc @args
  if ($LASTEXITCODE -ne 0) { throw "Compile failed: $out" }
}

Write-Host '1/4  App (without icon, to draw the icon)'
Compile 'bin\DualEarbuds-noicon.exe' ($engine + $appSrc) @()
Write-Host '2/4  Icon'
$p = Start-Process -FilePath 'bin\DualEarbuds-noicon.exe' -ArgumentList '--make-icon', "`"$PSScriptRoot\build\app.ico`"" -Wait -PassThru -WindowStyle Hidden
if ($p.ExitCode -ne 0 -or -not (Test-Path build\app.ico)) { throw 'Icon generation failed' }
Write-Host '3/4  App'
Compile 'dist\DualEarbuds.exe' ($engine + $appSrc) @('/win32icon:build\app.ico')
Write-Host '4/4  Installer'
$setupSources = $setup + @((Resolve-Path src\App\Theme.cs).Path, (Resolve-Path src\App\Ui.cs).Path, (Resolve-Path src\App\IconFactory.cs).Path,
                           (Resolve-Path src\Engine\QrCode.cs).Path, (Resolve-Path src\Engine\Support.cs).Path)
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
