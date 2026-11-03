# Build the firmware from PowerShell; see flash.sh for macOS/Linux flashing.
[CmdletBinding()]
param(
    [switch]$Android,
    [switch]$DebugSerial,
    [string]$BuildRoot,
    [string]$ConfigFile,
    [ValidateRange(1, 64)]
    [int]$Jobs = 4
)

$ErrorActionPreference = 'Stop'
$cli = (Get-Command arduino-cli -ErrorAction Stop).Source
$sketch = Join-Path $PSScriptRoot 'hid_bridge'
$target = if ($Android) { 'android' } else { 'iphone' }
if (-not $BuildRoot) {
    $BuildRoot = if ($env:ARDUINO_DIRECTORIES_DATA) {
        Join-Path (Split-Path $env:ARDUINO_DIRECTORIES_DATA -Parent) 'build'
    } else {
        Join-Path $PSScriptRoot 'build'
    }
}
$targetDir = Join-Path $BuildRoot $target
$output = Join-Path $targetDir 'firmware'
$include = (Join-Path $sketch 'nimble_cfg.h').Replace('\', '/')
$flags = '-include "' + $include + '"'
if ($Android) { $flags += ' -DIA_TARGET_ANDROID=1' }
if ($DebugSerial) { $flags += ' -DDEBUG_SERIAL=1' }
$fqbn = 'esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc,FlashSize=8M,PSRAM=opi,PartitionScheme=default_8MB'
$cliArgs = @()
if ($ConfigFile) { $cliArgs += @('--config-file', $ConfigFile) }
$cliArgs += @(
    'compile', '--fqbn', $fqbn,
    '--jobs', $Jobs,
    '--build-path', (Join-Path $targetDir 'intermediate'),
    '--output-dir', $output,
    '--build-property', "compiler.cpp.extra_flags=$flags",
    '--build-property', "compiler.c.extra_flags=$flags",
    $sketch
)
& $cli @cliArgs
if ($LASTEXITCODE -ne 0) { throw "Firmware build failed (exit $LASTEXITCODE)." }
Write-Host "Firmware: $output"
