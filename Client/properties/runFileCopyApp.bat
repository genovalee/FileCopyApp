@echo off
SET command_jar=S:\<機能別路徑>\<作業代號>\fileTrans.jar
SET command_prop=S:\<機能別路徑>\<作業代號>\config.properties
cmd /C java -jar "%command_jar%" "%command_prop%"  
ECHO %ERRORLEVEL%