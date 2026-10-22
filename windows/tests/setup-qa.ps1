# Installer QA: install / repair / modify / uninstall inside a sandbox (--test-root).
# Touches no real Program Files, shortcuts or registry keys. Exit code 1 on any failure.
$ErrorActionPreference = 'Continue'
$root  = Split-Path -Parent $PSScriptRoot           # ...\windows
# %TEMP% can be an 8.3 short path (C:\Users\QASIMR~1\...); shortcuts store long paths, so expand it first.
Add-Type -Namespace DeQa -Name Native -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
public static extern uint GetLongPathName(string shortPath, System.Text.StringBuilder longPath, uint size);
'@
function LongPath($p) {
  $b = New-Object System.Text.StringBuilder 1024
  if ([DeQa.Native]::GetLongPathName($p, $b, 1024) -gt 0) { return $b.ToString() }
  return $p
}
$sb    = Join-Path (LongPath $env:TEMP) 'de-setup-sandbox'
$setup = Join-Path $root 'dist\DualEarbuds-Setup.exe'
if (Test-Path $sb) { Remove-Item $sb -Recurse -Force }
Remove-Item 'HKCU:\Software\DualEarbudsSetupTest' -Recurse -Force -ErrorAction SilentlyContinue

$fail = 0
# Snapshot the REAL keys first: the sandboxed runs must leave them exactly as they were (a real install may exist).
function RealState {
    $run = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name DualEarbuds -ErrorAction SilentlyContinue
    $un  = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\DualEarbudsPro' -ErrorAction SilentlyContinue
    $r = ''; if ($run -ne $null) { $r = $run.DualEarbuds }
    $u = ''; if ($un -ne $null) { $u = [string]$un.DisplayVersion + '|' + [string]$un.InstallLocation + '|' + [string]$un.UninstallString }
    return $r + '#' + $u
}
$realBefore = RealState
function Check($name, $cond) { if ($cond) { "PASS  $name" } else { "FAIL  $name"; $script:fail++ } }
function Run($a) { (Start-Process $setup -ArgumentList $a -Wait -PassThru -WindowStyle Hidden).ExitCode }

$exe = "$sb\Programs\Dual Earbuds\DualEarbuds.exe"
$uk  = 'HKCU:\Software\DualEarbudsSetupTest\Uninstall\DualEarbudsPro'
$rk  = 'HKCU:\Software\DualEarbudsSetupTest\Run'
$ver = (Get-Item (Join-Path $root 'dist\DualEarbuds.exe')).VersionInfo.ProductVersion

'--- install (startup on, firewall off)'
Check 'exit 0' ((Run "--silent --startup --no-firewall --test-root `"$sb`"") -eq 0)
Check 'app installed' (Test-Path $exe)
Check 'app identical to build' ((Get-FileHash $exe).Hash -eq (Get-FileHash (Join-Path $root 'dist\DualEarbuds.exe')).Hash)
Check 'setup copy for maintenance' (Test-Path "$sb\Programs\Dual Earbuds\DualEarbuds-Setup.exe")
Check 'desktop shortcut' (Test-Path "$sb\Desktop\Dual Earbuds.lnk")
Check 'start menu shortcut' (Test-Path "$sb\StartMenu\Dual Earbuds.lnk")
$lnk = (New-Object -ComObject WScript.Shell).CreateShortcut("$sb\Desktop\Dual Earbuds.lnk")
Check 'shortcut target' ((LongPath $lnk.TargetPath) -eq (LongPath $exe))
$u = Get-ItemProperty $uk -ErrorAction SilentlyContinue
Check 'uninstall entry' ($u -ne $null)
Check 'uninstall string' ($u.UninstallString -like '*--uninstall*')
Check 'modify path' ($u.ModifyPath -like '*--maintain*')
Check 'quiet uninstall string' ($u.QuietUninstallString -like '*--silent*')
Check 'estimated size' ($u.EstimatedSize -gt 0)
Check 'run at startup' ((Get-ItemProperty $rk -ErrorAction SilentlyContinue).DualEarbuds -like '*--tray*')
Check 'real Run and Uninstall keys untouched' ((RealState) -eq $realBefore)

'--- repair after damage'
Remove-Item $exe -Force
Remove-Item "$sb\Desktop\Dual Earbuds.lnk" -Force
Check 'repair exit 0' ((Run "--silent --repair --test-root `"$sb`"") -eq 0)
Check 'exe restored' (Test-Path $exe)
Check 'repair did not nest a folder' (-not (Test-Path "$sb\Programs\Dual Earbuds\Dual Earbuds"))
Check 'setup copy kept' (Test-Path "$sb\Programs\Dual Earbuds\DualEarbuds-Setup.exe")
Check 'shortcut restored' (Test-Path "$sb\Desktop\Dual Earbuds.lnk")
Check 'startup option kept' ((Get-ItemProperty $rk -ErrorAction SilentlyContinue).DualEarbuds -like '*--tray*')

'--- modify'
Check 'modify exit 0' ((Run "--silent --no-desktop --no-firewall --test-root `"$sb`"") -eq 0)
Check 'desktop shortcut removed' (-not (Test-Path "$sb\Desktop\Dual Earbuds.lnk"))
Check 'startup removed' ((Get-ItemProperty $rk -Name DualEarbuds -ErrorAction SilentlyContinue) -eq $null)

'--- uninstall into a folder that already had other files must not delete them'
$shared = "$sb\SharedFolder"
New-Item -ItemType Directory -Force $shared | Out-Null
Set-Content "$shared\keep-me.txt" 'user file'
Run "--silent --uninstall --test-root `"$sb`"" | Out-Null
Check 'install into non-empty folder exit 0' ((Run "--silent --no-firewall --dir `"$shared`" --test-root `"$sb`"") -eq 0)
Check 'went into a Dual Earbuds subfolder' (Test-Path "$shared\Dual Earbuds\DualEarbuds.exe")
Check 'uninstall exit 0' ((Run "--silent --uninstall --test-root `"$sb`"") -eq 0)
Check 'user file survived uninstall' (Test-Path "$shared\keep-me.txt")

'--- uninstall keeps or removes data'
Run "--silent --no-firewall --test-root `"$sb`"" | Out-Null
New-Item -ItemType Directory -Force "$sb\AppData" | Out-Null; Set-Content "$sb\AppData\settings.txt" 'x'
Check 'uninstall exit 0' ((Run "--silent --uninstall --test-root `"$sb`"") -eq 0)
Check 'exe removed' (-not (Test-Path $exe))
Check 'uninstall entry removed' (-not (Test-Path $uk))
Check 'data kept' (Test-Path "$sb\AppData\settings.txt")
Run "--silent --no-firewall --test-root `"$sb`"" | Out-Null
Check 'uninstall --remove-data exit 0' ((Run "--silent --uninstall --remove-data --test-root `"$sb`"") -eq 0)
Check 'data removed' (-not (Test-Path "$sb\AppData\settings.txt"))

'--- uninstall when nothing installed'
$ec = Run "--silent --uninstall --test-root `"$sb`""
Check 'no crash on double uninstall' ($ec -eq 0 -or $ec -eq 2)

Remove-Item 'HKCU:\Software\DualEarbudsSetupTest' -Recurse -Force -ErrorAction SilentlyContinue
"RESULT: $fail failed (app version $ver)"
if ($fail -gt 0) { exit 1 }
