@echo off
chcp 65001 >nul
title Lion-Agent 后端 (lion-langchain4j-agent)
cd /d %~dp0

echo ============================================
echo  Lion LangChain4j Agent 后端启动脚本
echo ============================================

if not exist .env (
    echo [提示] 未检测到 .env, 已从 .env.example 自动复制, 请编辑填写数据库/千问密钥。
    copy /y .env.example .env >nul
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Maven(mvn), 请先安装 JDK 17+ 与 Maven 3.8+ 并配置 PATH。
    pause
    exit /b 1
)

echo [启动] 执行 mvn spring-boot:run, 按 Ctrl+C 可停止...
call mvn spring-boot:run
pause
