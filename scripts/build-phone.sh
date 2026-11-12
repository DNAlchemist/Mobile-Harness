#!/usr/bin/env bash
# Run from the project root: bash scripts/build-phone.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# The hosting Android app exports its Bionic library directory. Do not pass it
# to the Linux JDK or its Gradle children inside PRoot.
unset LD_LIBRARY_PATH

# Limit memory/concurrency on phones without changing desktop build defaults.
# Apply the CPU limit to both the launcher and the build JVM. A small heap and
# one worker also reduce peak usage during Gradle's first-run JAR transforms.
export JAVA_OPTS="${JAVA_OPTS:-} -Xmx128m -XX:ActiveProcessorCount=2"
# Disable the instrumentation agent to avoid another native startup dependency.
if [ "$#" -eq 0 ]; then
    set -- assembleOnlineDebug
fi
# Optional ARM64-host NDK, kept outside the repository. Read its real revision
# rather than pretending it is the desktop/F-Droid NDK version.
ndk_path="${MH_NDK_PATH:-}"
if [ -z "$ndk_path" ] && [ -f /opt/native-tools/android-ndk-r29/source.properties ]; then
    ndk_path=/opt/native-tools/android-ndk-r29
fi
native_args=()
if [ -n "$ndk_path" ]; then
    if [ ! -f "$ndk_path/source.properties" ]; then
        echo "Incomplete NDK: $ndk_path/source.properties is missing" >&2
        exit 1
    fi
    ndk_version=$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ndk_path/source.properties" | tr -d '\r')
    native_args+=("-PmhNdkPath=$ndk_path" "-PmhNdkVersion=$ndk_version")
fi
exec gradle "$@" "${native_args[@]}" \
    --no-daemon --max-workers=1 --no-watch-fs \
    -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
    -Pkotlin.compiler.execution.strategy=in-process \
    -Pandroid.builder.sdkDownload=false \
    -Dorg.gradle.internal.instrumentation.agent=false \
    --console=plain --stacktrace
