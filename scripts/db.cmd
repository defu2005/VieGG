@echo off
rem PostgreSQL portable dung chung cho moi phien dev cua RentGG.
rem Cach dung: scripts\db.cmd start ^| stop ^| status ^| psql [ten_db]
setlocal
set "PGROOT=%LOCALAPPDATA%\rentgg-tools"
set "PGBIN=%PGROOT%\pg16\pgsql\bin"
set "PGDATA=%PGROOT%\pgdata"
set "PGPASSWORD=rentgg_dev"

if /i "%~1"=="start"  goto start
if /i "%~1"=="stop"   goto stop
if /i "%~1"=="status" goto status
if /i "%~1"=="psql"   goto psql
echo Cach dung: scripts\db.cmd start ^| stop ^| status ^| psql [ten_db]
exit /b 1

:start
"%PGBIN%\pg_ctl.exe" -D "%PGDATA%" -o "-p 5432" -l "%PGROOT%\pg.log" -w start
exit /b %errorlevel%

:stop
"%PGBIN%\pg_ctl.exe" -D "%PGDATA%" -m fast -w stop
exit /b %errorlevel%

:status
"%PGBIN%\pg_ctl.exe" -D "%PGDATA%" status
exit /b %errorlevel%

:psql
set "DB=%~2"
if "%DB%"=="" set "DB=rentgg"
"%PGBIN%\psql.exe" -h localhost -p 5432 -U rentgg -d %DB%
exit /b %errorlevel%
