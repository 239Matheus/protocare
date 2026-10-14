# Proto Care — run Penpot `common` tests on the JVM with the portable toolchain.
#
# Usage (PowerShell, from anywhere):
#   tools\common-test.ps1
#   tools\common-test.ps1 --focus common-tests.governance.token-color-test
#
# Output goes to tools\common-test.log (never pipe test output to filters).

$Root = Split-Path -Parent $PSScriptRoot
$JdkDir = (Get-ChildItem -Path (Join-Path $Root "tools\jdk") -Directory -Filter "jdk-25*" | Select-Object -First 1).FullName

$env:JAVA_HOME = $JdkDir
$env:PATH = "$JdkDir\bin;$Root\tools\deps-clj;$env:PATH"

$Log = Join-Path $Root "tools\common-test.log"
Push-Location (Join-Path $Root "penpot\common")
try {
    & "$Root\tools\deps-clj\deps.exe" -M:dev:test @args *> $Log
} finally {
    Pop-Location
}

Get-Content $Log | Where-Object { $_ -notmatch "^Downloading" } | Select-String -Pattern "tests,|FAIL in|ERROR in"
Write-Host "full log: tools\common-test.log"
