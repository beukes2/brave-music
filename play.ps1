param(
    [int]$Index = 1
)

# Opens playlist entry $Index in Brave and turns music mode on (keeps it playing).
$adb = Join-Path "$env:LOCALAPPDATA\Android\Sdk" "platform-tools\adb.exe"
& $adb shell am start -n com.space.braveplay/.MainActivity --ei index $Index --ez music true
