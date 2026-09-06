@echo off
chcp 65001 >nul
title Lion-Agent 前端 (Vue3)
cd /d %~dp0

echo ============================================
echo  Lion Agent 前端一键启动脚本
echo ============================================

where node >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Node.js, 请先安装 Node.js 18+ 并配置 PATH。
    pause
    exit /b 1
)

if not exist node_modules (
    echo [首次运行] 正在安装前端依赖, 请稍候...
    call npm install --registry=https://registry.npmmirror.com
    if errorlevel 1 (
        echo [错误] npm install 失败, 请检查网络后重试。
        pause
        exit /b 1
    )
)

echo [启动] npm run dev, 访问 http://localhost:5173 (Ctrl+C 停止)
call npm run dev
pause
