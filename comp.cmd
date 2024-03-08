echo on

REM # BUILD
@call mvn clean install -U -e

REM # MAKE docker image
@call docker build -t onenet-connector-fiware-data-app:0.1 .
