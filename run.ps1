param(
    [ValidateRange(0, 65535)]
    [int]$Port = 8080,
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
$outDir = Join-Path $PSScriptRoot 'out'
$sourceDirs = @('src/main/java')
if ($Test) {
    $sourceDirs += 'src/test/java'
}
$sourcePaths = @(foreach ($directory in $sourceDirs) {
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot $directory) -Recurse -File -Filter '*.java' |
        Select-Object -ExpandProperty FullName
})

& javac --release 17 -encoding UTF-8 -d $outDir @sourcePaths
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if ($Test) {
    & java '-Dfile.encoding=UTF-8' -cp $outDir dev.qapulse.PulseTest $PSScriptRoot
} else {
    & java '-Dfile.encoding=UTF-8' -cp $outDir dev.qapulse.PulseServer $Port $PSScriptRoot
}
exit $LASTEXITCODE
