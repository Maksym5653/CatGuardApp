#!/bin/sh
# Запуск через Java, щоб обійти проблеми з правами доступу
java -jar gradle/wrapper/gradle-wrapper.jar "$@"