@echo off
rem Windows launcher: runs run.ps1 without changing the PowerShell execution policy.
rem   run.cmd            both apps
rem   run.cmd 2.1        one of them
rem   set AUTO_APPROVE=true ^& run.cmd
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
