# Build prostredie pre Android modul.
#
# Android SDK sa zamerne neinstaluje na hosta - vsetko zije v tomto obraze,
# takze sa da zmazat jednym `docker image rm` a nic po nom na stroji nezostane.
# Zaroven je build reprodukovatelny a rovnaky ako na CI.
FROM eclipse-temurin:21-jdk-noble

ARG CMDLINE_TOOLS=commandlinetools-linux-11076708_latest.zip
ARG ANDROID_PLATFORM=android-35
# 34.0.0 chce AGP 8.7 sam za seba - bez neho si ho dotahuje pri kazdom builde
ARG BUILD_TOOLS=35.0.0
ARG BUILD_TOOLS_AGP=34.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-cache

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/${CMDLINE_TOOLS}" -o /tmp/tools.zip \
    && unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/tools.zip

ENV PATH="${PATH}:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools"

# Licencie Android SDK sa odklikavaju tu, pri stavbe obrazu - nie na hostovi.
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true
RUN sdkmanager --install "platform-tools" "platforms;${ANDROID_PLATFORM}" \
    "build-tools;${BUILD_TOOLS}" "build-tools;${BUILD_TOOLS_AGP}" > /dev/null

WORKDIR /workspace
