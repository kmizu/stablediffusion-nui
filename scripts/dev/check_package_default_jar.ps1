$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$packageScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../package_windows.ps1'))
$missingJdk = Join-Path ([IO.Path]::GetTempPath()) ('sdfx-missing-jdk-' + [guid]::NewGuid().ToString('N'))
$process = [Diagnostics.Process]::new()
$process.StartInfo.FileName = (Join-Path $PSHOME 'powershell.exe')
$process.StartInfo.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $packageScript + '" -JdkHome "' + $missingJdk + '"'
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.CreateNoWindow = $true
$process.StartInfo.RedirectStandardError = $true
$process.StartInfo.RedirectStandardOutput = $true
try {
    if (-not $process.Start()) { throw 'Could not start Windows PowerShell.' }
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $combined = $stdout + $stderr
    if ($process.ExitCode -eq 0 -or $combined -notmatch 'Required file missing:' -or $combined -notmatch 'package_windows\.ps1') {
        throw "Default JAR path did not reach JDK validation: $combined"
    }
    if ($combined -match 'Cannot bind argument.*empty string') {
        throw "Default JAR path was evaluated before PSScriptRoot: $combined"
    }
    Write-Host 'PASS package default JAR resolution under Windows PowerShell'
} finally {
    $process.Dispose()
}
