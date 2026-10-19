# Generates the spike's synthetic test files with FFmpeg (a local tool only; never shipped): HEVC at 60 fps in 1080p and 4K,
# SDR and HDR10, each with three audio tracks (AAC stereo, AC-3 5.1, E-AC-3 stereo) and a styled ASS subtitle track.
# Real files (PGS, film ASS, TrueHD, Dolby Vision) come from a Jellyfin library instead.
#
#   winget install Gyan.FFmpeg
#   .\spikes\desktop-video\make-test-media.ps1              # writes to spikes\desktop-video\build\test-media
#   .\spikes\desktop-video\make-test-media.ps1 -Seconds 30 -OutDir D:\spike-media
param(
    [string]$OutDir = (Join-Path $PSScriptRoot "build\test-media"),
    [int]$Seconds = 90
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$ass = Join-Path $OutDir "styled.ass"
$end = [TimeSpan]::FromSeconds($Seconds - 1).ToString("h\:mm\:ss\.ff")
@"
[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,2,2,40,40,60,1
Style: Sign,Georgia,80,&H0000D7FF,&H000000FF,&H00402000,&H00000000,1,1,0,0,100,100,2,0,1,5,0,8,40,40,80,1
Style: Box,Consolas,48,&H00FFFFFF,&H000000FF,&H00C06000,&HA0000000,0,0,0,0,100,100,0,0,3,4,0,1,60,40,200,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:00:05.00,Default,,0,0,0,,ASS default style: white, black outline, shadow, bottom centre
Dialogue: 0,0:00:01.00,0:00:30.00,Sign,,0,0,0,,{\fad(500,500)}Sign: yellow italic, thick outline, top centre
Dialogue: 0,0:00:05.00,0:00:15.00,Default,,0,0,0,,{\move(200,900,1700,300)\c&H00FF00&}Moving green line (move tag)
Dialogue: 0,0:00:15.00,0:00:25.00,Box,,0,0,0,,{\an1}Opaque box style, bottom left
Dialogue: 0,0:00:15.00,0:00:25.00,Default,,0,0,0,,{\pos(1500,540)\frz20\fscx150\c&H0000FF&}Rotated, scaled, red at (1500,540)
Dialogue: 0,0:00:25.00,$end,Default,,0,0,0,,{\k50}Ka{\k50}ra{\k50}o{\k50}ke {\k100}timing, {\b1}bold{\b0} and {\i1}italic{\i0}
"@ | Set-Content -Encoding UTF8 -Path $ass

function New-TestFile([string]$Name, [int]$Width, [int]$Height, [bool]$Hdr, [string]$Bitrate) {
    $out = Join-Path $OutDir $Name
    $video = "testsrc2=s=${Width}x${Height}:r=60:d=$Seconds,noise=alls=10:allf=t"
    if ($Hdr) {
        $pix = "yuv420p10le"
        $x265 = "hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:" +
            "master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):max-cll=1000,400"
        $colour = @("-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc")
    } else {
        $pix = "yuv420p"
        $x265 = "colorprim=bt709:transfer=bt709:colormatrix=bt709"
        $colour = @("-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709")
    }
    $x265 += ":log-level=error"
    & ffmpeg -hide_banner -y `
        -f lavfi -i $video `
        -f lavfi -i "sine=f=440:d=${Seconds}" `
        -f lavfi -i "sine=f=660:d=${Seconds},pan=5.1|FL=c0|FR=c0|FC=c0|LFE=c0|BL=c0|BR=c0" `
        -f lavfi -i "sine=f=880:d=${Seconds},pan=stereo|c0=c0|c1=c0" `
        -i $ass `
        -map 0:v -map 1:a -map 2:a -map 3:a -map 4:s `
        -c:v libx265 -preset ultrafast -pix_fmt $pix -b:v $Bitrate -maxrate $Bitrate -bufsize $Bitrate -x265-params $x265 @colour `
        -c:a:0 aac -b:a:0 192k -c:a:1 ac3 -b:a:1 448k -c:a:2 eac3 -b:a:2 256k -c:s ass `
        -metadata:s:a:0 language=eng -metadata:s:a:0 title="440 Hz AAC stereo" `
        -metadata:s:a:1 language=ger -metadata:s:a:1 title="660 Hz AC-3 5.1" `
        -metadata:s:a:2 language=jpn -metadata:s:a:2 title="880 Hz E-AC-3 stereo" `
        -metadata:s:s:0 language=eng -metadata:s:s:0 title="Styled ASS" `
        $out
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed for $Name" }
    Write-Host "wrote $out"
}

New-TestFile "hevc-1080p60-sdr.mkv" 1920 1080 $false "20M"
New-TestFile "hevc-1080p60-hdr10.mkv" 1920 1080 $true "25M"
New-TestFile "hevc-2160p60-sdr.mkv" 3840 2160 $false "60M"
New-TestFile "hevc-2160p60-hdr10.mkv" 3840 2160 $true "80M"
