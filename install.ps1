# mcp-tasks Windows Installer
# PowerShell installation script for mcp-tasks and mcp-tasks-server

#Requires -Version 5.1

# Stop on any error
$ErrorActionPreference = 'Stop'

# Configuration
$DefaultInstallDir = "$env:LOCALAPPDATA\Programs\mcp-tasks"
$InstallDir = if ($env:INSTALL_DIR) { $env:INSTALL_DIR } else { $DefaultInstallDir }
$GitHubRepo = "hugoduncan/mcp-tasks"
$BaseUrl = "https://github.com/$GitHubRepo/releases/latest/download"

# Detect architecture
function Get-Architecture {
  $arch = $env:PROCESSOR_ARCHITECTURE

  switch ($arch) {
    "AMD64" {
      return "amd64"
    }
    "ARM64" {
      Write-Error "Error: ARM64 architecture is not yet supported on Windows"
      Write-Error "Supported architectures: amd64"
      exit 1
    }
    default {
      Write-Error "Error: Unsupported architecture: $arch"
      Write-Error "Supported architectures: amd64"
      exit 1
    }
  }
}

# Download file using Invoke-WebRequest
function Download-File {
  param(
    [string]$Url,
    [string]$Output
  )

  try {
    Write-Host "  Downloading from: $Url"
    $ProgressPreference = 'SilentlyContinue'  # Speed up download
    Invoke-WebRequest -Uri $Url -OutFile $Output -UseBasicParsing
    $ProgressPreference = 'Continue'
  }
  catch {
    Write-Error "Error: Failed to download from $Url"
    Write-Error $_.Exception.Message
    exit 1
  }
}

# Backup existing binary
function Backup-Binary {
  param(
    [string]$BinaryPath
  )

  if (Test-Path $BinaryPath) {
    $BackupPath = "$BinaryPath.old"
    Write-Host "  Backing up existing binary: $BinaryPath -> $BackupPath"
    Move-Item -Path $BinaryPath -Destination $BackupPath -Force
  }
}

# Install a single binary
function Install-Binary {
  param(
    [string]$BinaryName,
    [string]$Architecture,
    [string]$TempDir
  )

  $RemoteName = "$BinaryName-windows-$Architecture.exe"
  $DownloadUrl = "$BaseUrl/$RemoteName"
  $TempFile = Join-Path $TempDir "$BinaryName.exe"
  $InstallPath = Join-Path $InstallDir "$BinaryName.exe"

  Write-Host "Downloading $BinaryName..."
  Download-File -Url $DownloadUrl -Output $TempFile

  Backup-Binary -BinaryPath $InstallPath

  Write-Host "  Installing $BinaryName to $InstallDir..."
  try {
    Move-Item -Path $TempFile -Destination $InstallPath -Force
  }
  catch {
    Write-Error "Error: Failed to install to $InstallDir"
    Write-Error $_.Exception.Message
    Write-Host "You may need to run this script with administrator privileges" -ForegroundColor Yellow
    exit 1
  }
}

# Check if directory is in PATH
function Test-InPath {
  param(
    [string]$Directory
  )

  $PathDirs = $env:Path -split ';'
  return $PathDirs -contains $Directory
}

# Add directory to user PATH
function Add-ToPath {
  param(
    [string]$Directory
  )

  Write-Host ""
  Write-Host "The installation directory is not in your PATH." -ForegroundColor Yellow
  Write-Host "Would you like to add it to your PATH? (Y/N): " -NoNewline -ForegroundColor Yellow
  $response = Read-Host

  if ($response -match '^[Yy]') {
    try {
      $UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
      $NewPath = if ($UserPath) { "$UserPath;$Directory" } else { $Directory }
      [Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')

      # Update current session PATH
      $env:Path = "$env:Path;$Directory"

      Write-Host "Added $Directory to your PATH" -ForegroundColor Green
      Write-Host "You may need to restart your terminal for the change to take effect" -ForegroundColor Yellow
    }
    catch {
      Write-Warning "Failed to add to PATH: $($_.Exception.Message)"
      Write-Host "You can manually add $Directory to your PATH" -ForegroundColor Yellow
    }
  }
  else {
    Write-Host ""
    Write-Host "Installation directory not added to PATH." -ForegroundColor Yellow
    Write-Host "To use the binaries, either:"
    Write-Host "  - Add $Directory to your PATH manually"
    Write-Host "  - Run using full path: $Directory\mcp-tasks.exe"
  }
}

# Cleanup temporary directory
function Remove-TempDirectory {
  param(
    [string]$TempDir
  )

  if ($TempDir -and (Test-Path $TempDir)) {
    Remove-Item -Path $TempDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}

# Main installation flow
function Main {
  Write-Host "mcp-tasks installer" -ForegroundColor Cyan
  Write-Host ""

  # Detect architecture
  Write-Host "Detecting platform..."
  $Architecture = Get-Architecture
  Write-Host "Platform: windows-$Architecture"
  Write-Host ""

  # Create installation directory
  if (-not (Test-Path $InstallDir)) {
    Write-Host "Creating installation directory: $InstallDir"
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
  }

  # Create temporary directory
  $TempDir = Join-Path $env:TEMP "mcp-tasks-install-$(Get-Random)"
  New-Item -ItemType Directory -Path $TempDir -Force | Out-Null

  try {
    # Install binaries
    Install-Binary -BinaryName "mcp-tasks" -Architecture $Architecture -TempDir $TempDir
    Write-Host ""
    Install-Binary -BinaryName "mcp-tasks-server" -Architecture $Architecture -TempDir $TempDir
    Write-Host ""

    # Success message
    Write-Host "Installation complete!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Binaries installed to: $InstallDir"
    Write-Host "  - mcp-tasks.exe"
    Write-Host "  - mcp-tasks-server.exe"
    Write-Host ""

    # Check PATH and offer to add
    if (-not (Test-InPath -Directory $InstallDir)) {
      Add-ToPath -Directory $InstallDir
      Write-Host ""
    }

    Write-Host "You can now use the mcp-tasks CLI and server."
    Write-Host "For more information, visit: https://github.com/$GitHubRepo"

    exit 0
  }
  finally {
    # Cleanup
    Remove-TempDirectory -TempDir $TempDir
  }
}

# Run main function
Main
