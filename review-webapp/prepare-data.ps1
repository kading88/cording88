. (Join-Path $PSScriptRoot 'scripts\common.ps1')
Read-ProjectEnv
& (Join-Path $ProjectRoot 'scripts\start-db.ps1')
& (Join-Path $ProjectRoot '.venv\Scripts\python.exe') -u (Join-Path $ProjectRoot 'model\prepare_data.py')
Assert-LastExit 'SOM preparation'
