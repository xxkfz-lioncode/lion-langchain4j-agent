@echo off
chcp 65001 >nul
title Lion-Agent 一键启动
cd /d %~dp0

echo ============================================
echo  Lion LangChain4j Agent 一键启动(前后端)
echo ============================================

start "lion-backend" cmd /k "cd /d %~dp0 && call start-backend.bat"
start "lion-frontend" cmd /k "cd /d %~dp0\frontend && call start.bat"

echo 后端与前端已在两个独立窗口启动。
echo 提示: 首次使用请先执行 docs\sql\init.sql 初始化数据库, 并在 .env 中填写千问 API Key。
pause
