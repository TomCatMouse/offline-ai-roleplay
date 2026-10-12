# Sensitive-info + large-object audit (pure ASCII script, reads UTF-8 files explicitly)
$ErrorActionPreference = "Continue"
# Repo root is derived from this script's own location (scripts\<name>.ps1 -> repo root),
# so no local machine path is baked in and the script runs from any clone.
$repo = (Split-Path -Parent $PSScriptRoot)
Set-Location $repo

Write-Host "=== 1) tracked text files: high-risk credential patterns ==="
$binRe = [regex]'\.so$|\.bin$|\.gguf$|\.zip$|\.apk$|\.jks$|\.png$|\.jpg$|\.jar$|\.dll$|\.dat$'
$textFiles = (git ls-files) | Where-Object { -not ($binRe.IsMatch($_)) -and $_ -match '\.(kt|java|xml|md|txt|ps1|kts|json|properties|toml|sh|gradle|yaml|yml|cfg|conf|bat|cmd|html|css|js)$' }
$secretRe = [regex]'ghp_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|xox[bap]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{20,}|-----BEGIN [A-Z ]*PRIVATE|hf_[a-zA-Z0-9]{20,}|sk-[a-zA-Z0-9]{20,}|AKIA[0-9A-Z]{16}'
$kwRe     = [regex]'(?i)(GITHUB_TOKEN|API_KEY|PASSWORD|SECRET)\s*[:=]\s*[\x22\x27\x60][A-Za-z0-9_\-/+=]{8,}'
$hits = 0
foreach ($f in $textFiles) {
    $path = Join-Path $repo $f
    if (!(Test-Path $path)) { continue }
    $t = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    foreach ($m in $secretRe.Matches($t)) { $hits++; Write-Host ("  [SECRET] {0} -> {1}" -f $f, $m.Value.Substring(0,[Math]::Min(24,$m.Value.Length))) }
    foreach ($m in $kwRe.Matches($t))  { $hits++; Write-Host ("  [KW]     {0} -> {1}" -f $f, $m.Value.Substring(0,[Math]::Min(40,$m.Value.Length))) }
}
Write-Host ("  text-file hits: {0} {1}" -f $hits, $(if ($hits -eq 0) { "OK" } else { "PROBLEM" }))

Write-Host ""
Write-Host "=== 2) git history: credential patterns in added lines ==="
$hist = git log -p --all --diff-filter=A 2>$null | Out-String
$hm = [regex]::Matches($hist, 'ghp_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|-----BEGIN [A-Z ]*PRIVATE KEY')
Write-Host ("  history hits: {0} {1}" -f $hm.Count, $(if ($hm.Count -eq 0) { "OK" } else { "PROBLEM" }))
foreach ($m in $hm) { Write-Host "  -> " + $m.Value.Substring(0,[Math]::Min(24,$m.Value.Length)) }

Write-Host ""
Write-Host "=== 3) objects > 5 MB anywhere in history ==="
$objs = git rev-list --objects --all 2>$null
$big = @()
foreach ($line in $objs) {
    $p = $line -split '\s', 2
    if ($p.Count -ne 2) { continue }
    $sz = & git cat-file -s $p[0] 2>$null
    if ($LASTEXITCODE -eq 0 -and $sz -and [long]$sz -gt 5242880) {
        $big += ("{0}  {1,10} MB  {2}" -f $p[0].Substring(0,12), [Math]::Round([long]$sz/1MB,2), $p[1])
    }
}
if ($big.Count -gt 0) { $big | ForEach-Object { Write-Host "  $_" } } else { Write-Host "  none OK" }

Write-Host ""
Write-Host "=== 4) local vs remote main ==="
$localSha = (git rev-parse main).Trim()
$tok = [Environment]::GetEnvironmentVariable("GITHUB_TOKEN", "User")
$remoteSha = "n/a"
$remoteUrl = (& git -C $repo remote get-url origin 2>$null | Select-Object -First 1)
if (-not $remoteUrl) {
    $remoteSha = "(no origin remote)"
} elseif ($tok -and $remoteUrl -match '^(https://)(.+)$') {
    $authUrl = $Matches[1] + "x-access-token:$tok@" + $Matches[2]
    # -c credential.helper= : the token is already inside the URL, so no helper is
    # needed -- and more importantly the system-level GCM must not store this
    # authentication into the credential manager. It once left a stray
    # "git:https://github.com" entry (username x-access-token) behind.
    $env:GIT_TERMINAL_PROMPT = "0"
    $remoteOut = & git -c credential.helper= ls-remote $authUrl main 2>$null
    if ($remoteOut) { $remoteSha = ($remoteOut -split '\s+')[0] }
}
Write-Host "  local  main: $localSha"
Write-Host "  remote main: $remoteSha"
if ($remoteSha -eq "(no origin remote)") {
    Write-Host "  skipped - no origin remote configured"
} elseif ($localSha -eq $remoteSha) { Write-Host "  in sync OK" } else { Write-Host "  OUT OF SYNC - check unpushed commits" }
Write-Host ""
Write-Host "=== 5) local tags ==="
$lt = git tag -l
if ($lt) { $lt | ForEach-Object { Write-Host "  $_" } } else { Write-Host "  (none - release tags live on remote, created via API)" }
