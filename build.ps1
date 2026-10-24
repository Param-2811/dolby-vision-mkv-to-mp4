<#  build.ps1 - makes dist\DV Converter\ (portable app) and DV-Converter-win64.zip

    Needs: Python 3.10+ on PATH, internet on the first run (it downloads ffmpeg,
    dovi_tool and hdr10plus_tool into tools\), and winget only if MP4Box is missing.
    Run:   powershell -ExecutionPolicy Bypass -File build.ps1
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'      # Invoke-WebRequest is far faster without the progress bar
Set-Location $PSScriptRoot

$tools = Join-Path $PSScriptRoot 'tools'
$tmp = Join-Path $env:TEMP 'dv-converter-build'
New-Item -ItemType Directory -Force $tools, $tmp | Out-Null

function Get-Zip($url, $name) {
    $z = Join-Path $tmp "$name.zip"
    Write-Host "downloading $name ..."
    Invoke-WebRequest -UseBasicParsing $url -OutFile $z
    $d = Join-Path $tmp $name
    Expand-Archive $z $d -Force
    $d
}

function Get-GhAsset($repo, $pattern) {       # download URL of the newest release asset matching $pattern
    $rel = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest" -Headers @{'User-Agent' = 'dv-converter-build' }
    ($rel.assets | Where-Object name -like $pattern | Select-Object -First 1).browser_download_url
}

# ---- 1. third-party tools (skipped if already in tools\) -------------------
if (-not (Test-Path "$tools\ffmpeg.exe")) {
    # shared build on purpose: ffmpeg.exe + ffprobe.exe + DLLs is ~110 MB, the static pair is ~480 MB
    $d = Get-Zip 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl-shared.zip' 'ffmpeg'
    Copy-Item (Get-ChildItem "$d\*\bin\ffmpeg.exe", "$d\*\bin\ffprobe.exe", "$d\*\bin\*.dll").FullName $tools -Force
}

foreach ($t in @(@('dovi_tool', 'quietvoid/dovi_tool'), @('hdr10plus_tool', 'quietvoid/hdr10plus_tool'))) {
    if (Test-Path "$tools\$($t[0]).exe") { continue }
    $d = Get-Zip (Get-GhAsset $t[1] '*x86_64-pc-windows-msvc.zip') $t[0]
    Copy-Item (Get-ChildItem $d -Recurse -Filter "$($t[0]).exe").FullName $tools -Force
}

if (-not (Test-Path "$tools\mp4box.exe")) {
    $mp4box = Get-ChildItem "$tools\gpac\mp4box.exe", "$env:ProgramFiles\GPAC\mp4box.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $mp4box) {
        Write-Host 'installing GPAC (MP4Box) via winget ...'
        winget install -e --id GPAC.GPAC --accept-package-agreements --accept-source-agreements
        $mp4box = Get-Item "$env:ProgramFiles\GPAC\mp4box.exe"
    }
    # MP4Box hard-links GPAC's own ffmpeg 6 DLLs (avcodec-60 etc.), so all top-level DLLs come along.
    # Only GPAC's sdk\ and share\ folders are left out - those it does not need for muxing.
    Copy-Item (Get-ChildItem (Join-Path $mp4box.Directory 'mp4box.exe'), (Join-Path $mp4box.Directory '*.dll')).FullName $tools -Force
}

foreach ($t in 'ffmpeg', 'ffprobe', 'dovi_tool', 'hdr10plus_tool', 'mp4box') {
    if (-not (Test-Path "$tools\$t.exe")) { throw "tool missing after fetch: $t.exe (see README)" }
}

# ---- 2. the exe -----------------------------------------------------------
python -m pip install --quiet --upgrade pyinstaller tkinterdnd2
if ($LASTEXITCODE) { throw 'pip install failed' }
Remove-Item -Recurse -Force build, dist -ErrorAction SilentlyContinue
python -m PyInstaller --noconfirm --clean --windowed --name 'DV Converter' --collect-all tkinterdnd2 dv_converter.py
if ($LASTEXITCODE) { throw 'PyInstaller failed' }

# ---- 3. portable folder + zip --------------------------------------------
$out = 'dist\DV Converter'
New-Item -ItemType Directory -Force "$out\tools" | Out-Null
Copy-Item (Get-ChildItem $tools -File).FullName "$out\tools" -Force   # -File skips the unpruned tools\gpac\
Copy-Item 'README.md', 'LICENSE', 'THIRD-PARTY-NOTICES.md', 'Stop DV Converter.bat' $out -Force

$zip = 'DV-Converter-win64.zip'
Remove-Item $zip -ErrorAction SilentlyContinue
Compress-Archive "$out\*" $zip
Write-Host ("done: {0} ({1:N0} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
