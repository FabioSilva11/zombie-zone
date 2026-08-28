$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$characterSource = Join-Path $projectRoot 'kenney_blocky-characters_20'
$dungeonSource = Join-Path $projectRoot 'kenney_modular-dungeon-kit_1.0'
$gameAssets = Join-Path $projectRoot 'app\src\main\assets\game'
$characterTarget = Join-Path $gameAssets 'models\characters'
$characterTextureTarget = Join-Path $characterTarget 'Textures'
$characterPreviewTarget = Join-Path $gameAssets 'images\characters'
$dungeonTarget = Join-Path $gameAssets 'models\dungeon'
$dungeonTextureTarget = Join-Path $dungeonTarget 'Textures'
$noticeTarget = Join-Path $projectRoot 'app\src\main\assets\notices'

foreach ($path in @($characterTarget, $characterTextureTarget, $characterPreviewTarget, $dungeonTarget, $dungeonTextureTarget, $noticeTarget)) {
    New-Item -ItemType Directory -Force -Path $path | Out-Null
}

foreach ($id in [char[]]'abcdefghijklmnopqr') {
    Copy-Item -LiteralPath (Join-Path $characterSource "Models\GLB format\character-$id.glb") -Destination $characterTarget -Force
    Copy-Item -LiteralPath (Join-Path $characterSource "Models\GLB format\Textures\texture-$id.png") -Destination $characterTextureTarget -Force
    Copy-Item -LiteralPath (Join-Path $characterSource "Previews\character-$id.png") -Destination $characterPreviewTarget -Force
}

$dungeonModels = @('room-large', 'room-small', 'room-wide', 'corridor', 'corridor-corner', 'gate-door', 'stairs')
foreach ($model in $dungeonModels) {
    Copy-Item -LiteralPath (Join-Path $dungeonSource "Models\GLB format\$model.glb") -Destination $dungeonTarget -Force
}

Copy-Item -LiteralPath (Join-Path $dungeonSource 'Models\GLB format\Textures\colormap.png') -Destination $dungeonTextureTarget -Force
Copy-Item -LiteralPath (Join-Path $dungeonSource 'Preview.png') -Destination (Join-Path $gameAssets 'images\dungeon-cover.png') -Force
Copy-Item -LiteralPath (Join-Path $characterSource 'License.txt') -Destination (Join-Path $noticeTarget 'KENNEY_BLOCKY_CHARACTERS_LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $dungeonSource 'License.txt') -Destination (Join-Path $noticeTarget 'KENNEY_MODULAR_DUNGEON_LICENSE.txt') -Force

$copied = Get-ChildItem -LiteralPath $gameAssets -Recurse -File
$sizeMb = [math]::Round((($copied | Measure-Object -Property Length -Sum).Sum / 1MB), 2)
Write-Output "Copied $($copied.Count) game asset files ($sizeMb MB)."

