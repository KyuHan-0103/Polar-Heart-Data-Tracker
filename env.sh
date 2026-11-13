# Project-local toolchain, the Android equivalent of a Python venv.
# Usage (from the project root):   source env.sh
# Everything lives in .tooling/ - nothing is installed system-wide.
_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
export JAVA_HOME="$_ROOT/.tooling/jdk/Contents/Home"
export ANDROID_HOME="$_ROOT/.tooling/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$_ROOT/.tooling/gradle-home"   # Gradle caches + downloaded deps
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
unset _ROOT
