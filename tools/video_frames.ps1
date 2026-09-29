# Contact sheet of frames from a gameplay video, using only Windows' built-in WinRT media APIs.
# powershell -File tools/video_frames.ps1 -Video clip.mp4 -Out sheet.png [-Times 1.5,2.0] [-Count 24] [-Columns 4] [-Width 320] [-Height 180]
param(
    [string]$Video,
    [string]$Out,
    [double[]]$Times = @(),
    [int]$Count = 24,
    [int]$Columns = 4,
    [int]$Width = 320,
    [int]$Height = 180
)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.Drawing
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Media.Editing.MediaComposition, Windows.Media.Editing, ContentType = WindowsRuntime]
$null = [Windows.Media.Editing.MediaClip, Windows.Media.Editing, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.ImageStream, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $null = $t.Wait(-1); $t.Result }

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Video)) ([Windows.Storage.StorageFile])
$clip = Await ([Windows.Media.Editing.MediaClip]::CreateFromFileAsync($file)) ([Windows.Media.Editing.MediaClip])
$comp = New-Object Windows.Media.Editing.MediaComposition
$null = [System.Collections.Generic.ICollection[Windows.Media.Editing.MediaClip]].GetMethod("Add").Invoke($comp.Clips, @($clip))
$duration = $clip.OriginalDuration.TotalSeconds
if ($Times.Count -eq 0) { $Times = 0..($Count - 1) | ForEach-Object { [Math]::Min($duration - 0.1, $duration * $_ / ($Count - 1)) } }

$rows = [Math]::Ceiling($Times.Count / $Columns)
$sheet = New-Object System.Drawing.Bitmap ($Columns * $Width), ($rows * ($Height + 14))
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.Clear([System.Drawing.Color]::Black)
$font = New-Object System.Drawing.Font "Arial", 8
for ($i = 0; $i -lt $Times.Count; $i++) {
    $stream = Await ($comp.GetThumbnailAsync([TimeSpan]::FromSeconds($Times[$i]), $Width, $Height, [Windows.Media.Editing.VideoFramePrecision]::NearestFrame)) ([Windows.Graphics.Imaging.ImageStream])
    $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
    $ms = New-Object System.IO.MemoryStream
    $net.CopyTo($ms); $net.Close()
    $img = [System.Drawing.Image]::FromStream($ms)
    $x = ($i % $Columns) * $Width; $y = [Math]::Floor($i / $Columns) * ($Height + 14)
    $g.DrawImage($img, $x, $y, $Width, $Height); $img.Dispose()
    $g.DrawString(("{0}: {1:N1}s" -f $i, $Times[$i]), $font, [System.Drawing.Brushes]::Yellow, $x + 2, $y + $Height)
}
$sheet.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $sheet.Dispose()
"{0}: {1:N1}s -> {2}" -f (Split-Path $Video -Leaf), $duration, $Out
