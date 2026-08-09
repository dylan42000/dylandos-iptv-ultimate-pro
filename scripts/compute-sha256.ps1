# ─── DYLANDOS IPTV — APK SHA256 + Size Reporter ───────────────────────────
# Run from the android/ directory:
#   cd android
#   powershell -ExecutionPolicy Bypass -File ../scripts/compute-sha256.ps1
# Outputs the exact JSON fields to paste into ota-update-*.json

param(
    [string]$FirestickApk = "app\build\outputs\apk\firestick\release\app-firestick-release.apk",
    [string]$PremiumApk   = "app\build\outputs\apk\premium\release\app-premium-release.apk",
    [string]$OtaJsonPath  = "ota-update-2.2.0.json"
)

function Get-FileSha256 {
    param([string]$Path)
    if (-not (Test-Path $Path)) { Write-Error "Not found: $Path"; return $null }
    $hash = Get-FileHash -Path $Path -Algorithm SHA256
    return $hash.Hash.ToLower()
}

function Format-Bytes {
    param([long]$Bytes)
    if ($Bytes -ge 1MB) { return "$([math]::Round($Bytes / 1MB, 2)) MB" }
    return "$([math]::Round($Bytes / 1KB, 1)) KB"
}

Write-Host ""
Write-Host "═══════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  DYLANDOS APK SHA256 REPORTER" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

$results = @{}

foreach ($entry in @(
    @{ Name = "firestick"; Path = $FirestickApk },
    @{ Name = "premium";   Path = $PremiumApk  }
)) {
    $name = $entry.Name
    $path = $entry.Path

    if (-not (Test-Path $path)) {
        Write-Host "[$name] NOT FOUND: $path" -ForegroundColor Red
        continue
    }

    $sha256 = Get-FileSha256 -Path $path
    $size   = (Get-Item $path).Length
    $results[$name] = @{ sha256 = $sha256; size = $size }

    Write-Host "[$name]" -ForegroundColor Yellow
    Write-Host "  File:   $path"
    Write-Host "  Size:   $(Format-Bytes $size) ($size bytes)"
    Write-Host "  SHA256: $sha256" -ForegroundColor Green
    Write-Host ""
}

Write-Host "─── Paste into your OTA JSON ───────────────────────" -ForegroundColor Cyan
Write-Host ""

if ($results["firestick"]) {
    $f = $results["firestick"]
    Write-Host '"flavors": {' -ForegroundColor White
    Write-Host '  "firestick": {' -ForegroundColor White
    Write-Host "    `"apkSize`": $($f.size)," -ForegroundColor Green
    Write-Host "    `"sha256`": `"$($f.sha256)`"" -ForegroundColor Green
    Write-Host '  },' -ForegroundColor White
}

if ($results["premium"]) {
    $p = $results["premium"]
    Write-Host '  "premium": {' -ForegroundColor White
    Write-Host "    `"apkSize`": $($p.size)," -ForegroundColor Green
    Write-Host "    `"sha256`": `"$($p.sha256)`"" -ForegroundColor Green
    Write-Host '  }' -ForegroundColor White
    Write-Host '}' -ForegroundColor White
}

Write-Host ""

# ── Optionally patch the OTA JSON in-place ──────────────────────────────────
if (Test-Path $OtaJsonPath) {
    $json = Get-Content $OtaJsonPath -Raw | ConvertFrom-Json

    if ($results["firestick"]) {
        $json.flavors.firestick.apkSize = $results["firestick"].size
        $json.flavors.firestick.sha256  = $results["firestick"].sha256
    }
    if ($results["premium"]) {
        $json.flavors.premium.apkSize = $results["premium"].size
        $json.flavors.premium.sha256  = $results["premium"].sha256
    }
    if ($results["firestick"]) {
        $json.apkSize = $results["firestick"].size
        $json.sha256  = $results["firestick"].sha256
    }

    $json | ConvertTo-Json -Depth 10 | Set-Content $OtaJsonPath -Encoding UTF8
    Write-Host "✓ Patched $OtaJsonPath in-place with real sizes and sha256 values." -ForegroundColor Green
} else {
    Write-Host "OTA JSON not found at '$OtaJsonPath' — copy values manually above." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "═══════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Done. Upload APKs to your host, then update apkUrl." -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""
