# syntax=docker/dockerfile:1
#
# Compila el APK de la app del chofer sin necesitar Android Studio ni el SDK en la maquina:
# JDK 17 + Gradle (imagen oficial) + Android SDK command line tools. Corre tambien los tests
# unitarios. El APK queda en /apk y el servicio "apk" del docker-compose.yml lo copia a build-output/.
#
# Nota: instalar el SDK implica aceptar las licencias del Android SDK (sdkmanager --licenses).

FROM gradle:8.11.1-jdk17 AS sdk
ARG CMDLINE_TOOLS=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/* \
 && mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSL -o /tmp/tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
 && unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" \
 && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
 && rm /tmp/tools.zip \
 && yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null \
 && "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;34.0.0" > /dev/null

FROM sdk AS build
WORKDIR /proyecto
COPY . .
# API REST del componente central (WildFly) que usa la app
ARG BASE_URL=https://env-6537388.web.elasticloud.uy/frontofficeciudadanos-web
RUN --mount=type=cache,target=/home/gradle/.gradle/caches \
    gradle --no-daemon -q "-Pcargauy.baseUrl=${BASE_URL}" testDebugUnitTest assembleDebug \
 && mkdir /apk \
 && cp app/build/outputs/apk/debug/app-debug.apk /apk/cargauy-chofer-debug.apk

FROM alpine:3.20
COPY --from=build /apk /apk
CMD ["sh", "-c", "cp /apk/*.apk /build-output/ && ls -l /build-output"]

