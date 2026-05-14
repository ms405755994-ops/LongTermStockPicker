$ErrorActionPreference = "Stop"

$TaskName17 = "LongTermStockPicker GitHub Top100 17"
$TaskName03 = "LongTermStockPicker GitHub Top100 03"
$ProjectDir = "/mnt/d/2026MSHK/MSZCX/LongTermStockPicker"
$Command = "bash -lc `"cd $ProjectDir && scripts/run_daily_pc_pipeline.sh >> logs/daily_pc_pipeline.log 2>&1`""
$Action = "wsl.exe"

schtasks /Create /F /TN $TaskName17 /SC DAILY /ST 17:00 /TR "$Action $Command"
schtasks /Create /F /TN $TaskName03 /SC DAILY /ST 03:00 /TR "$Action $Command"

Write-Host "已创建每日定时任务："
Write-Host " - $TaskName17: 17:00"
Write-Host " - $TaskName03: 03:00"
Write-Host "日志：logs/daily_pc_pipeline.log"
