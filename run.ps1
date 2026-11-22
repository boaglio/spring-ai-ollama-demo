<#
.SYNOPSIS
  Runs the 1.1 and 2.1 booking demos against a local Ollama (Windows version of run.sh).

.EXAMPLE
  .\run.ps1                              # both
  .\run.ps1 2.1                          # one of them
  $env:AUTO_APPROVE = "true"; .\run.ps1  # approve the cancel without asking
  $env:CHAT_MODEL = "llama3.1:8b"; .\run.ps1
#>
param(
    [ValidateSet("1.1", "2.1")]
    [string[]] $Versions = @("1.1", "2.1")
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

# Emoji and accented Portuguese text need UTF-8 on the Windows console
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $env:OLLAMA_URL)     { $env:OLLAMA_URL = "http://localhost:11434" }
if (-not $env:CHAT_MODEL)     { $env:CHAT_MODEL = "qwen3:8b" }
if (-not $env:DECISION_MODEL) { $env:DECISION_MODEL = "d1-omni:600m" }
$autoApprove = if ($env:AUTO_APPROVE -eq "true") { "true" } else { "false" }

# 1) Ollama must be running
try {
    $tags = Invoke-RestMethod -Uri "$($env:OLLAMA_URL)/api/tags" -TimeoutSec 5
}
catch {
    Write-Host "Ollama is not answering at $($env:OLLAMA_URL)"
    exit 1
}

# 2) The models must be pulled (the decision model is only used by 2.1)
$need = @($env:CHAT_MODEL)
if ($Versions -contains "2.1") { $need += $env:DECISION_MODEL }
$pulled = @($tags.models | ForEach-Object { $_.name })
foreach ($model in $need) {
    if ($pulled -notcontains $model) {
        $answer = Read-Host "Model $model is not pulled. Pull it now? [y/N]"
        if ($answer -match '^[yYsS]') {
            ollama pull $model
            if ($LASTEXITCODE -ne 0) { exit 1 }
        }
        else {
            Write-Host "Skipping: $model missing"
            exit 1
        }
    }
}

# 3) Build once
$jars = $Versions | ForEach-Object { "booking-$_\target\booking-$_-1.0.0.jar" }
if (($jars | Where-Object { -not (Test-Path $_) }).Count -gt 0) {
    mvn -q -B package -DskipTests
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

# 4) Run. With "Stop", Windows PowerShell 5.1 would treat any stderr line from java as an error.
$ErrorActionPreference = "Continue"
foreach ($v in $Versions) {
    Write-Host ""
    Write-Host "################################  Spring AI $v  ################################"
    $javaArgs = @(
        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED",
        "-jar", "booking-$v\target\booking-$v-1.0.0.jar",
        "--spring.ai.retry.max-attempts=2", "--demo.auto-approve=$autoApprove"
    )
    if ($autoApprove -eq "true") {
        # Nothing to type, so the output can also go to run-<version>.log
        & java @javaArgs 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath "run-$v.log"
    }
    else {
        # PowerShell passes a program's output on line by line, so piping it to Tee-Object
        # would hide the "[s/N]" prompt (it has no newline) until you answered it.
        # Run straight to the console instead; no log file in this mode.
        & java @javaArgs
    }
}
