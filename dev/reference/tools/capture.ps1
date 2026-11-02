param([string]$OutName = "capture.png")
# Reference capture: full guest display -> PNG, run from inside the WinTAK VM.
# Z:\Downloads is VMPal's shared folder bound to the Mac's Downloads folder;
# pull the PNG out Mac-side, then move it into reference/captures/ (gitignored).
# Usage: powershell -ExecutionPolicy Bypass -File Z:\Downloads\capture.ps1 -OutName wintak-ref-N-<name>.png
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class DPI { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[DPI]::SetProcessDPIAware() | Out-Null
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap $vs.Width, $vs.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($vs.X, $vs.Y, 0, 0, $vs.Size)
$g.Dispose()
$bmp.Save("Z:\Downloads\$OutName", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output ("saved Z:\Downloads\" + $OutName + " " + $vs.Width + "x" + $vs.Height)