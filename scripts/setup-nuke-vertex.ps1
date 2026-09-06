# FocusLock Nuke x Vertex AI setup — driven entirely by your gcloud CLI.
# Run: powershell -ExecutionPolicy Bypass -File scripts/setup-nuke-vertex.ps1 [-ProjectId your-id] [-Location us-central1] [-Model gemini-2.0-flash]
param(
  [string]$ProjectId = "",
  [string]$Location = "us-central1",
  [string]$Model = "gemini-2.0-flash"
)

$ErrorActionPreference = "Stop"
function Info($m) { Write-Host "→ $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "✓ $m" -ForegroundColor Green }
function Warn($m) { Write-Host "! $m" -ForegroundColor Yellow }

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

# 1. Find or install gcloud
$gcloud = (Get-Command gcloud -ErrorAction SilentlyContinue)?.Source
if (-not $gcloud) {
  $candidates = @(
    "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
    "C:\Program Files\Google Cloud SDK\bin\gcloud.cmd",
    "C:\Program Files (x86)\Google Cloud SDK\bin\gcloud.cmd"
  )
  foreach ($c in $candidates) { if (Test-Path $c) { $gcloud = $c; break } }
}
if (-not $gcloud) {
  Warn "gcloud CLI not found. Installing via winget…"
  winget install --id Google.CloudSDK -e --accept-source-agreements --accept-package-agreements
  $gcloud = (Get-Command gcloud -ErrorAction SilentlyContinue)?.Source
  if (-not $gcloud) { throw "gcloud still not found after install. Restart terminal and re-run." }
}
Ok "gcloud: $gcloud"
& $gcloud --version | Select-Object -First 2

# 2. Auth (user + application-default — Vertex needs both)
Info "Checking gcloud auth…"
$accounts = & $gcloud auth list --format="value(account)" 2>$null
if (-not $accounts) {
  Info "No account — opening browser login…"
  & $gcloud auth login
}
Info "Ensuring application-default credentials (for local Convex dev)…"
& $gcloud auth application-default login --quiet 2>$null
if ($LASTEXITCODE -ne 0) { Warn "application-default login needs browser — follow the prompt."; & $gcloud auth application-default login }

# 3. Project
if ([string]::IsNullOrWhiteSpace($ProjectId)) {
  $ProjectId = (& $gcloud config get-value project 2>$null).Trim()
}
if ([string]::IsNullOrWhiteSpace($ProjectId) -or $ProjectId -eq "(unset)") {
  $def = "focuslock-nuke-$(Get-Random -Minimum 1000 -Maximum 9999)"
  $ProjectId = Read-Host "GCP Project ID to use (enter to create '$def')"
  if ([string]::IsNullOrWhiteSpace($ProjectId)) { $ProjectId = $def }
}
$existing = & $gcloud projects describe $ProjectId --format="value(projectId)" 2>$null
if (-not $existing) {
  Info "Creating project $ProjectId…"
  & $gcloud projects create $ProjectId --name="FocusLock Nuke"
}
& $gcloud config set project $ProjectId | Out-Null
Ok "Project: $ProjectId"

# 4. Enable Vertex AI
Info "Enabling aiplatform.googleapis.com… (first enable can take ~1 min)"
& $gcloud services enable aiplatform.googleapis.com --project $ProjectId
Ok "Vertex AI enabled"

# 5. Access token for Convex server action (short-lived; refreshed by cron/dev)
Info "Minting access token for Convex → Vertex…"
$token = (& $gcloud auth print-access-token).Trim()
if ([string]::IsNullOrWhiteSpace($token)) { throw "Could not mint access token." }

# 6. Push config into Convex (server-side — never ships in the APK)
Info "Setting Convex env: VERTEX_PROJECT / VERTEX_LOCATION / GEMINI_MODEL / VERTEX_ACCESS_TOKEN…"
Push-Location (Join-Path $RepoRoot "convex\..")
try {
  npx --yes convex env set VERTEX_PROJECT $ProjectId | Out-Null
  npx --yes convex env set VERTEX_LOCATION $Location | Out-Null
  npx --yes convex env set GEMINI_MODEL $Model | Out-Null
  # NOTE: access tokens expire ~1h. For prod, switch to a service-account key via:
  #   gcloud iam service-accounts create focuslock-nuke --project $ProjectId
  # and store its key in Convex. This script uses the token for immediate dev use.
  npx --yes convex env set VERTEX_ACCESS_TOKEN $token | Out-Null
} finally { Pop-Location }
Ok "Convex env set"

# 7. Desktop .env hint (public values only — token stays server-side)
$desktopEnv = Join-Path $RepoRoot "desktop\.env"
if (-not (Test-Path $desktopEnv)) {
  Copy-Item (Join-Path $RepoRoot "desktop\.env.example") $desktopEnv
  Ok "Created desktop/.env from example — fill in Clerk + Convex URL"
} else { Info "desktop/.env already exists — leaving it" }

# 8. Verify Vertex call
Info "Verifying $Model in $Location…"
$verifyUrl = "https://$Location-aiplatform.googleapis.com/v1/projects/$ProjectId/locations/$Location/publishers/google/models/${Model}:generateContent"
$verifyBody = @{ contents = @(@{ role = "user"; parts = @(@{ text = "Reply with: nuke link ok" }) }) } | ConvertTo-Json -Depth 6
try {
  $res = Invoke-RestMethod -Method Post -Uri $verifyUrl -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body $verifyBody -TimeoutSec 30
  $text = $res.candidates[0].content.parts[0].text
  Ok "Vertex reachable. Model said: $($text.Trim())"
} catch {
  Warn "Direct verify failed: $($_.Exception.Message)"
  Warn "Common fixes: billing enabled on project, or wait 2 min after API enable, then re-run."
}

Write-Host ""
Ok "Done. Nuke flow: NUKE button (phone or desktop) → 10-min reset → coach chat (Vertex $Model) → unlocks both via Convex."
Write-Host "  Refresh token later with: gcloud auth print-access-token  →  npx convex env set VERTEX_ACCESS_TOKEN <token>" -ForegroundColor Yellow
Write-Host "  Redeploy backend: npx convex dev (or npx convex deploy)" -ForegroundColor Yellow
