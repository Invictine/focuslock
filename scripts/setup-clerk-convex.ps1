# Requires: $env:CLERK_SECRET_KEY = "sk_test_..." (from https://dashboard.clerk.com -> API keys)
# Does: validates secret, creates "convex" JWT template via Clerk Backend API,
#       scaffolds desktop/.env + local.properties entries, prints Convex env values.
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$secret = $env:CLERK_SECRET_KEY
if ([string]::IsNullOrWhiteSpace($secret) -or -not $secret.StartsWith("sk_")) {
  Write-Host "Set `$env:CLERK_SECRET_KEY first. Get it from https://dashboard.clerk.com -> API keys."
  Write-Host 'Example: $env:CLERK_SECRET_KEY = "sk_test_..."; powershell -ExecutionPolicy Bypass -File scripts/setup-clerk-convex.ps1'
  exit 1
}

$headers = @{ Authorization = "Bearer $secret"; "Content-Type" = "application/json" }

Write-Host "1/3 Validating Clerk secret..."
try {
  $me = Invoke-RestMethod -Method Get -Uri "https://api.clerk.com/v1/allowlist_identifiers?limit=1" -Headers $headers
  Write-Host "    OK (secret valid)."
} catch {
  Write-Host "    FAILED: $($_.Exception.Message)"
  Write-Host "    Check the secret and try again."
  exit 1
}

Write-Host "2/3 Creating 'convex' JWT template (idempotent)..."
$templateBody = @{
  name = "convex"
  claims = @{
    aud = "convex"
  }
  lifetime = 60
  allowed_clock_skew = 5
} | ConvertTo-Json -Depth 6
try {
  $existing = Invoke-RestMethod -Method Get -Uri "https://api.clerk.com/v1/jwt_templates" -Headers $headers
  $found = @($existing) | Where-Object { $_.name -eq "convex" }
  if ($found) { Write-Host "    Template 'convex' already exists — keeping it." }
  else {
    Invoke-RestMethod -Method Post -Uri "https://api.clerk.com/v1/jwt_templates" -Headers $headers -Body $templateBody | Out-Null
    Write-Host "    Created."
  }
} catch {
  Write-Host "    NOTE: auto-create failed ($($_.Exception.Message))."
  Write-Host "    Create it manually: Clerk dashboard -> JWT Templates -> New template named 'convex'."
}

Write-Host "3/3 Scaffolding env files..."
$desktopEnv = Join-Path $root "desktop\.env"
$desktopExample = Join-Path $root "desktop\.env.example"
if (-not (Test-Path $desktopEnv) -and (Test-Path $desktopExample)) {
  Copy-Item $desktopExample $desktopEnv
  Write-Host "    Created desktop\.env (fill VITE_CLERK_PUBLISHABLE_KEY + VITE_CONVEX_URL)."
} else { Write-Host "    desktop\.env already exists — left untouched." }

$localProps = Join-Path $root "local.properties"
$need = @("clerk.publishableKey=", "convex.url=")
if (Test-Path $localProps) {
  $cur = Get-Content $localProps -Raw
  $missing = $need | Where-Object { $cur -notmatch [regex]::Escape($_.TrimEnd("=")) }
  if ($missing) { Add-Content $localProps ("`n" + ($missing -join "`n")); Write-Host "    Appended missing keys to local.properties." }
  else { Write-Host "    local.properties already has clerk/convex keys." }
} else {
  Set-Content $localProps ($need -join "`n")
  Write-Host "    Created local.properties with clerk/convex placeholders."
}

Write-Host ""
Write-Host "NEXT:"
Write-Host "  1. Clerk dashboard -> API keys: paste publishable key (pk_...) into desktop\.env + local.properties"
Write-Host "  2. Clerk dashboard -> Domains/Frontend API URL -> Convex dashboard -> env CLERK_JWT_ISSUER_DOMAIN"
Write-Host "  3. Clerk dashboard -> Integrations -> Convex -> Activate; JWT Templates -> confirm 'convex'"
Write-Host "  4. npm install; npx convex dev   (pushes auth.config + generates convex/_generated)"
Write-Host "  5. Android: .\gradlew.bat :app:assembleDebug | Desktop: cd desktop; npm install; npm run dev"
