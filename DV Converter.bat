@echo off
cd /d "%~dp0"
start "" pythonw "%~dp0dv_converter.py"
if errorlevel 1 python "%~dp0dv_converter.py"
