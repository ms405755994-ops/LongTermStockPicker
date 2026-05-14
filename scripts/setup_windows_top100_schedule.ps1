$ErrorActionPreference = "Stop"

$ProjectDir = "/mnt/d/2026MSHK/MSZCX/LongTermStockPicker"
$Pipeline = "cd $ProjectDir && scripts/run_daily_pc_pipeline.sh >> logs/daily_pc_pipeline.log 2>&1"
$Action = New-ScheduledTaskAction -Execute "wsl.exe" -Argument "bash -lc `"$Pipeline`""

$Task17 = "LongTermStockPicker GitHub Top100 17"
$Task03 = "LongTermStockPicker GitHub Top100 03"

Register-ScheduledTask `
    -TaskName $Task17 `
    -Action $Action `
    -Trigger (New-ScheduledTaskTrigger -Daily -At "17:00") `
    -Description "LongTermStockPicker: update PostgreSQL, score market, export GitHub Top100" `
    -Force | Out-Null

Register-ScheduledTask `
    -TaskName $Task03 `
    -Action $Action `
    -Trigger (New-ScheduledTaskTrigger -Daily -At "03:00") `
    -Description "LongTermStockPicker: update PostgreSQL, score market, export GitHub Top100" `
    -Force | Out-Null

Write-Host "已创建每日定时任务："
Write-Host " - ${Task17}: 17:00"
Write-Host " - ${Task03}: 03:00"
Write-Host "日志：logs/daily_pc_pipeline.log"
