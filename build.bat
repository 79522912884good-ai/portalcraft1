@echo off
rem Нужны JDK 21 и Gradle в PATH (или выполните один раз: gradle wrapper)
gradle build && echo Готово: build\libs\portalcraft-1.0.0.jar
pause
