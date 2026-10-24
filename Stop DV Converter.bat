@echo off
echo Stopping DV Converter and its ffmpeg / dovi_tool / MP4Box children...
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'DV Converter.exe' -or ($_.Name -like 'python*' -and $_.CommandLine -like '*dv_converter.py*') } | ForEach-Object { taskkill /PID $_.ProcessId /T /F }"
echo Done.
pause
