@echo off
chcp 65001 >nul
echo ========================================================
echo   Сборка мода Inverted World (Forge 1.20.1)
echo ========================================================

if exist gradlew.bat (
    call gradlew.bat build
) else (
    where gradle >nul 2>nul
    if %ERRORLEVEL% EQU 0 (
        call gradle build
    ) else (
        echo [!] В папке не найден gradlew.bat и не установлен системный Gradle.
        echo Пожалуйста, скачайте Forge 1.20.1 MDK с files.minecraftforge.net,
        echo скопируйте из него файлы gradlew.bat и папку gradle в эту папку,
        echo и запустите этот файл снова!
        pause
        exit /b 1
    )
)

echo.
echo [+] Готовый мод находится в папке: build\libs\invertedworld-1.20.1-1.0.0.jar
pause
