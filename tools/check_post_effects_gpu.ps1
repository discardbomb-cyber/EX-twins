param([string]$RunId = (Get-Date -Format 'yyyy-MM-dd-HHmmss'))
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
    $taskJavac = Join-Path (Split-Path -Parent $taskJava) 'javac.exe'
    $taskJars = foreach ($taskModule in @('lwjgl','lwjgl-glfw','lwjgl-opengl')) {
        Get-ChildItem -LiteralPath "$taskCache/caches/modules-2/files-2.1/org.lwjgl/$taskModule/3.3.3" -Recurse -Filter '*.jar' |
            Where-Object { $_.Name -eq "$taskModule-3.3.3.jar" -or $_.Name -eq "$taskModule-3.3.3-natives-windows.jar" } |
            ForEach-Object FullName
    }
    $taskJars += Get-ChildItem -LiteralPath "$taskCache/caches/modules-2/files-2.1/org.joml/joml/1.10.5" -Recurse -Filter 'joml-1.10.5.jar' | ForEach-Object FullName
    $taskClasspath = $taskJars -join ';'
    New-Item -ItemType Directory -Force work/gpu-classes,work/test-runs | Out-Null
    $taskCompileLog = "work/test-runs/$RunId-gpu-compile.log"
    $taskRunLog = "work/test-runs/$RunId-gpu-check.log"
    if ((Test-Path $taskCompileLog) -or (Test-Path $taskRunLog)) { throw 'RunId already exists; preserve the original logs' }
    & $taskJavac -cp $taskClasspath -d work/gpu-classes tools/PostEffectGpuCheck.java src/main/java/dev/hurtify/relicsaddon/client/LensScreenBounds.java *> $taskCompileLog
    if ($LASTEXITCODE -ne 0) { Get-Content $taskCompileLog; exit $LASTEXITCODE }
    & $taskJava -cp "work/gpu-classes;$taskClasspath" dev.hurtify.relicsaddon.client.PostEffectGpuCheck *> $taskRunLog
    $taskExit = $LASTEXITCODE
    Get-Content $taskRunLog
    exit $taskExit
} finally {
    Pop-Location
}
