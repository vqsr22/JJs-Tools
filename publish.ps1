# Builds JJ's Tools, pushes the source and README, and puts the jar on a GitHub release.
#
# Run it from the project folder, the one holding gradlew.bat:
#   .\publish.ps1
#
# It will not push anything until the build has succeeded, so a broken build stops here rather
# than halfway through a release.

[CmdletBinding()]
param(
    # Override the version. By default it is read from gradle.properties, which is the one place
    # the version is written down, so the tag can never disagree with the jar.
    [string] $Version,

    # Commit message. The version is appended.
    [string] $Message = "Release",

    # Push the code and skip the GitHub release.
    [switch] $NoRelease,

    # Build and show what would happen, without pushing anything.
    [switch] $DryRun
)

$ErrorActionPreference = "Stop"

# PowerShell 7.3 and up treat anything a program writes to stderr as a terminating error. Gradle
# writes its warnings there, so without this a perfectly good build stops the script.
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repo = "vqsr22/JJs-Tools"

function Step($text)  { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Note($text)  { Write-Host "    $text" -ForegroundColor DarkGray }
function Fail($text)  { Write-Host "`nX  $text" -ForegroundColor Red; exit 1 }

# --- Tools -------------------------------------------------------------------------------------
# git is often installed but not on PATH, which is what "git is not recognized" means. Rather than
# telling you to fix your PATH, the usual install spots are checked and the full path is used.

function Find-Tool($name, $candidates) {
    $onPath = Get-Command $name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }

    return $null
}

$git = Find-Tool "git" @(
    "$env:ProgramFiles\Git\cmd\git.exe",
    "${env:ProgramFiles(x86)}\Git\cmd\git.exe",
    "$env:LOCALAPPDATA\Programs\Git\cmd\git.exe"
)

if (-not $git) {
    Fail "git not found. Install it from https://git-scm.com/download/win, then open a new terminal."
}

$gh = Find-Tool "gh" @(
    "$env:ProgramFiles\GitHub CLI\gh.exe",
    "${env:ProgramFiles(x86)}\GitHub CLI\gh.exe",
    "$env:LOCALAPPDATA\Programs\GitHub CLI\gh.exe"
)

# --- Where are we ------------------------------------------------------------------------------

if (-not (Test-Path ".\gradlew.bat")) {
    Fail "Run this from the project folder, the one with gradlew.bat in it."
}

if (-not $Version) {
    $line = Select-String -Path ".\gradle.properties" -Pattern "^\s*mod_version\s*=\s*(.+?)\s*$"
    if (-not $line) { Fail "Could not read mod_version from gradle.properties." }
    $Version = $line.Matches[0].Groups[1].Value
}

$tag = "v$Version"
Step "JJ's Tools $Version"
Note "repo $repo"
Note "git  $git"
if ($gh) { Note "gh   $gh" } else { Note "gh   not found, the release will have to be made by hand" }

# --- Build -------------------------------------------------------------------------------------

Step "Building"
& .\gradlew.bat build
if ($LASTEXITCODE -ne 0) { Fail "Build failed. Nothing has been pushed." }

# The build also produces -sources and -dev jars, which are not what people download.
$jar = Get-ChildItem ".\build\libs\*.jar" |
    Where-Object { $_.Name -notmatch "-(sources|dev|dev-shadow)\.jar$" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) { Fail "No jar in build\libs. The build said it worked, which it clearly did not." }
Note "jar  $($jar.Name)  $([math]::Round($jar.Length / 1KB)) KB"

# --- Repo --------------------------------------------------------------------------------------

if (-not (Test-Path ".\.git")) {
    Step "Setting up the repo for the first time"
    if ($DryRun) { Note "dry run, stopping here" ; exit 0 }

    & $git init
    & $git branch -M main
    & $git remote add origin "https://github.com/$repo.git"
}
elseif (-not (& $git remote get-url origin 2>$null)) {
    & $git remote add origin "https://github.com/$repo.git"
}

Step "Committing"
& $git add -A

# git commit fails when there is nothing staged, which is fine and not an error worth stopping for.
$staged = & $git diff --cached --name-only
if ($staged) {
    Note "$($staged.Count) file(s) changed"
    if (-not $DryRun) { & $git commit -m "$Message $Version" | Out-Null }
}
else {
    Note "nothing changed, the code is already up to date"
}

if ($DryRun) {
    Step "Dry run"
    Note "would push main and create $tag with $($jar.Name)"
    exit 0
}

Step "Pushing"
& $git push -u origin main
if ($LASTEXITCODE -ne 0) { Fail "Push failed. The release was not created." }

# --- Release -----------------------------------------------------------------------------------

if ($NoRelease) { Step "Done, release skipped" ; exit 0 }

if (-not $gh) {
    Step "Code pushed. Make the release by hand"
    Note "https://github.com/$repo/releases/new?tag=$tag"
    Note "attach $($jar.FullName)"
    Note "or install the GitHub CLI from https://cli.github.com and run this again"
    exit 0
}

Step "Creating release $tag"

# Notes file is optional; without it GitHub is asked to write them from the commits.
$notes = ".\RELEASE-NOTES-$Version.md"
$notesArgs = if (Test-Path $notes) { @("--notes-file", $notes) } else { @("--generate-notes") }

$existing = & $gh release view $tag --repo $repo 2>$null
if ($existing) {
    Note "$tag already exists, replacing the jar on it"
    & $gh release upload $tag $jar.FullName --repo $repo --clobber
}
else {
    $preRelease = if ($Version -match "beta|alpha|rc") { @("--prerelease") } else { @() }

    & $gh release create $tag $jar.FullName `
        --repo $repo `
        --title "JJ's Tools $Version" `
        @notesArgs @preRelease
}

if ($LASTEXITCODE -ne 0) { Fail "The release step failed, but the code was pushed." }

Step "Done"
Note "https://github.com/$repo/releases/tag/$tag"
