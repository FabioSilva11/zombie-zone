$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$modulesRoot = Join-Path $projectRoot 'node_modules'
$vendorRoot = Join-Path $projectRoot 'app\src\main\assets\game\vendor'
$noticesRoot = Join-Path $projectRoot 'app\src\main\assets\notices'

$copies = @(
    @('three\build\three.module.js', 'three.module.js'),
    @('three\build\three.core.js', 'three.core.js'),
    @('three\examples\jsm\loaders\GLTFLoader.js', 'three\addons\loaders\GLTFLoader.js'),
    @('three\examples\jsm\utils\BufferGeometryUtils.js', 'three\addons\utils\BufferGeometryUtils.js'),
    @('three\examples\jsm\utils\SkeletonUtils.js', 'three\addons\utils\SkeletonUtils.js'),
    @('camera-controls\dist\camera-controls.module.js', 'camera-controls.module.js'),
    @('@dimforge\rapier3d-compat\dist\rapier.mjs', 'rapier.mjs'),
    @('@dimforge\rapier3d-compat\dist\rapier_wasm3d_bg.wasm', 'rapier_wasm3d_bg.wasm'),
    @('nipplejs\dist\index.mjs', 'nipplejs.mjs')
)

foreach ($copy in $copies) {
    $source = Join-Path $modulesRoot $copy[0]
    $destination = Join-Path $vendorRoot $copy[1]
    if (-not (Test-Path -LiteralPath $source)) { throw "Runtime source missing: $source" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
}

New-Item -ItemType Directory -Force -Path $noticesRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $modulesRoot 'three\LICENSE') -Destination (Join-Path $noticesRoot 'THREE_LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $modulesRoot 'camera-controls\LICENSE') -Destination (Join-Path $noticesRoot 'CAMERA_CONTROLS_LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $modulesRoot '@dimforge\rapier3d-compat\LICENSE') -Destination (Join-Path $noticesRoot 'RAPIER_LICENSE.txt') -Force

$nippleNotice = @'
nipplejs 1.0.4
Copyright (c) 2014 Yoann Moinet
Licensed under the MIT License.
Source: https://github.com/yoannmoinet/nipplejs
'@
Set-Content -LiteralPath (Join-Path $noticesRoot 'NIPPLEJS_LICENSE.txt') -Value $nippleNotice -Encoding utf8

foreach ($copy in $copies) {
    $destination = Join-Path $vendorRoot $copy[1]
    if (-not (Test-Path -LiteralPath $destination)) { throw "Runtime output missing: $destination" }
}

Write-Output "Packaged $($copies.Count) offline runtime files."
