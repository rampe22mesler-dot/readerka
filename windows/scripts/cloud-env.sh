#!/usr/bin/env bash
# Source this file before building in the prepared Codex cloud machine.
export JAVA_HOME=/workspace/toolchains/jdk-17.0.16+8
export ANDROID_HOME=/workspace/toolchains/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/workspace/toolchains/android-user
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export GRADLE_USER_HOME=/workspace/toolchains/gradle-home
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/11.0/bin:$ANDROID_HOME/emulator:$PATH"
