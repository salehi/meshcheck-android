# Build environment for the MeshCheck Android app.
#
# Contains JDK 17 and the Android SDK command-line tools only. The SDK
# packages and the Gradle cache live in ./.docker-cache (bind-mounted at
# runtime by build.sh), so this image stays small and the SDK can be updated
# without rebuilding the image.

FROM eclipse-temurin:17-jdk-noble

# unzip is needed to unpack the cmdline-tools bundle.
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

# Android command-line tools (provides sdkmanager). The bundle is vendored
# under docker/vendor/ (downloaded manually, gitignored) rather than fetched
# at build time.
ARG CMDLINE_TOOLS_VERSION=11076708
COPY vendor/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip /tmp/cmdline-tools.zip
RUN mkdir -p /opt/android-cmdline-tools \
 && unzip -q /tmp/cmdline-tools.zip -d /opt/android-cmdline-tools \
 && mv /opt/android-cmdline-tools/cmdline-tools /opt/android-cmdline-tools/latest \
 && rm /tmp/cmdline-tools.zip

# Gradle, vendored under docker/vendor/ (downloaded manually, gitignored)
# rather than fetched at build time.
ARG GRADLE_VERSION=8.10.2
COPY vendor/gradle-${GRADLE_VERSION}-bin.zip /tmp/gradle.zip
RUN unzip -q /tmp/gradle.zip -d /opt \
 && rm /tmp/gradle.zip

# ANDROID_HOME / GRADLE_USER_HOME point at the bind-mounted ./.docker-cache
# directories. cmdline-tools and Gradle come from the image; the rest of the
# SDK is populated into the bind mount by docker/setup.sh.
ENV ANDROID_HOME=/sdk \
    ANDROID_SDK_ROOT=/sdk \
    GRADLE_USER_HOME=/.gradle \
    PATH="/opt/gradle-${GRADLE_VERSION}/bin:/opt/android-cmdline-tools/latest/bin:${PATH}"
