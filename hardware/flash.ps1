# Copyright (c) 2026 Henry Yuan. MIT; see LICENSE.
# Flash an existing build; connect the board's native USB port to the PC first.
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^COM[0-9]+$')]
    [string]$Port,
    [switch]$Android,
    [string]$FirmwareDir,
    [string]$ConfigFile
)

$ErrorActionPreference = 'Stop'
$cli = (Get-Command arduino-cli -ErrorAction Stop).Source
$target = if ($Android) { 'android' } else { 'iphone' }
if (-not $FirmwareDir) {
    $buildRoot = if ($env:ARDUINO_DIRECTORIES_DATA) {
        Join-Path (Split-Path $env:ARDUINO_DIRECTORIES_DATA -Parent) 'build'
    } else { Join-Path $PSScriptRoot 'build' }
    $FirmwareDir = Join-Path $buildRoot "$target\firmware"
}
foreach ($binary in @('hid_bridge.ino.bin', 'hid_bridge.ino.bootloader.bin', 'hid_bridge.ino.partitions.bin')) {
    if (-not (Test-Path -LiteralPath (Join-Path $FirmwareDir $binary) -PathType Leaf)) {
        throw "Missing $binary under $FirmwareDir. Run hardware/build.ps1 first."
    }
}
$fqbn = 'esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc,FlashSize=8M,PSRAM=opi,PartitionScheme=default_8MB'
$cliArgs = @()
if ($ConfigFile) { $cliArgs += @('--config-file', $ConfigFile) }
$cliArgs += @('upload', '--fqbn', $fqbn, '--port', $Port, '--input-dir', $FirmwareDir,
              (Join-Path $PSScriptRoot 'hid_bridge'))
if ($PSCmdlet.ShouldProcess("$Port ($target)", "Flash firmware from $FirmwareDir")) {
    & $cli @cliArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Flash failed. If the port is unavailable, hold BOOT, tap RESET, release BOOT, check the new COM port and retry."
    }
    Write-Host 'Flash complete. Unplug the board from the PC and connect its native USB data port to the phone.'
}
