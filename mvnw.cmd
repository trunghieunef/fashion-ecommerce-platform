@echo off
setlocal
set "MAVEN_PROJECTBASEDIR=%~dp0"
if "%JAVA_HOME%"=="" (set "JAVACMD=java") else (set "JAVACMD=%JAVA_HOME%\bin\java.exe")
"%JAVACMD%" %JAVA_OPTS% %MAVEN_OPTS% -classpath "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
set "ERROR_CODE=%ERRORLEVEL%"
endlocal & exit /b %ERROR_CODE%
