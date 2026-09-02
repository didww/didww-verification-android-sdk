#!/usr/bin/env bash
# DIDWW Verification SDK — toolchain environment for macOS and Linux.
#
#   source tools/android-env.sh
#
# Sets JAVA_HOME, ANDROID_HOME and PATH for this repository's build, in the shell you run
# it in and nowhere else.
#
# It is deliberately NOT something you add to ~/.zshrc or ~/.bashrc. A global JAVA_HOME is
# a machine-wide decision, and other projects on the same machine may need a different JDK;
# exporting 17 for every shell breaks them silently and at a distance.
#
# Nothing here is installed for you. When a piece is missing the script says which one and
# prints the command that installs it on the operating system you are actually running.
# See sample/README.md § 1 for the whole first-time sequence.

# ---------------------------------------------------------------------------------------
# Must be sourced, not executed. A child process that exports variables and exits has
# changed nothing, and the failure looks exactly like the script not working.
# ---------------------------------------------------------------------------------------
_didww_sourced=0
if [ -n "${BASH_VERSION:-}" ]; then
    [ "${BASH_SOURCE[0]}" != "${0}" ] && _didww_sourced=1
elif [ -n "${ZSH_VERSION:-}" ]; then
    case "${ZSH_EVAL_CONTEXT:-}" in *:file*) _didww_sourced=1 ;; esac
else
    # Unknown shell. Assume the user followed the instructions rather than refusing to run.
    _didww_sourced=1
fi

if [ "$_didww_sourced" -eq 0 ]; then
    echo "android-env.sh must be SOURCED, not executed:" >&2
    echo "    source tools/android-env.sh" >&2
    exit 1
fi
unset _didww_sourced

# ---------------------------------------------------------------------------------------
# Helpers. Everything is prefixed and unset at the end: this runs in the user's interactive
# shell, so leaving names behind is leaving litter in someone else's namespace.
# ---------------------------------------------------------------------------------------
_didww_os=$(uname -s)

_didww_fail() {
    echo "" >&2
    echo "android-env.sh: $1" >&2
    shift
    for _line in "$@"; do echo "    $_line" >&2; done
    unset _line
    return 1
}

# The feature version, as `java -version` reports it on stderr. Java 8 prints "1.8.0_452",
# so this yields 1 rather than 8 — which is correct here, since 1 is not 17 either way.
_didww_java_major() {
    [ -x "$1/bin/java" ] || return 1
    "$1/bin/java" -version 2>&1 | head -1 | sed -E 's/^[^"]*"([0-9]+).*/\1/'
}

_didww_path_prepend() {
    case ":${PATH}:" in
        *":$1:"*) ;;
        *) PATH="$1:${PATH}" ;;
    esac
}

# ---------------------------------------------------------------------------------------
# JAVA_HOME
#
# AGP 8.13 needs a JDK 17 to run the build itself, and every module declares
# jvmToolchain(17). An already-correct JAVA_HOME wins: someone using jenv, SDKMAN, asdf,
# mise or a hand-built JDK has already made this decision, and second-guessing them would
# make the script the thing that has to be worked around.
# ---------------------------------------------------------------------------------------
_didww_java=""

if [ -n "${JAVA_HOME:-}" ] && [ "$(_didww_java_major "$JAVA_HOME")" = "17" ]; then
    _didww_java="$JAVA_HOME"
fi

if [ -z "$_didww_java" ] && [ "$_didww_os" = "Darwin" ]; then
    # The registered-JDK index. Finds Temurin, Zulu, Corretto and Oracle installs under
    # /Library/Java, which is where a .pkg installer puts them.
    _didww_candidate=$(/usr/libexec/java_home -v 17 2>/dev/null)
    [ -n "$_didww_candidate" ] && _didww_java="$_didww_candidate"
fi

# Scans the unregistered install locations, in rough order of how likely each is to be the
# one meant. A JDK unpacked into ~/Library/Java (as IntelliJ and some tarball installs do) is
# invisible to /usr/libexec/java_home, so it has to be looked for directly.
#
# The body is a SUBSHELL — `( )`, not `{ }` — because it must change a globbing option and
# must not leave that change behind in someone's interactive shell.
#
# That option is not a detail. zsh's default `nomatch` makes an unmatched glob a fatal
# error, and inside a *sourced* file that aborts the entire script rather than the loop:
# every candidate after the first non-existent directory is never tried, and the user sees
# "no matches found" naming a path they have never heard of. bash instead passes the pattern
# through verbatim. Neither is wanted here — a candidate list wants unmatched patterns to
# disappear, which is what null_glob / nullglob do.
_didww_scan_for_jdk17() (
    if [ -n "${ZSH_VERSION:-}" ]; then
        setopt null_glob
    else
        shopt -s nullglob
    fi
    for candidate in \
        "${SDKMAN_DIR:-$HOME/.sdkman}"/candidates/java/17*/ \
        "$HOME"/.sdkman/candidates/java/current \
        "$HOME"/Library/Java/JavaVirtualMachines/*17*/Contents/Home \
        "$HOME"/.asdf/installs/java/*17*/ \
        "$HOME"/.local/share/mise/installs/java/*17*/ \
        /usr/lib/jvm/temurin-17-* \
        /usr/lib/jvm/java-17-openjdk* \
        /usr/lib/jvm/java-1.17.0-* \
        /usr/lib/jvm/*17* \
        /opt/java/openjdk \
        /Library/Java/JavaVirtualMachines/*17*/Contents/Home
    do
        candidate="${candidate%/}"
        [ "$(_didww_java_major "$candidate")" = "17" ] || continue
        printf '%s\n' "$candidate"
        return 0
    done
    return 1
)

if [ -z "$_didww_java" ]; then
    _didww_java=$(_didww_scan_for_jdk17)
fi

if [ -z "$_didww_java" ] && command -v javac > /dev/null 2>&1; then
    # Last resort: whatever `javac` on PATH belongs to. Two readlinks up from the binary is
    # the JDK home for a normal layout, and the version is checked before it is accepted.
    _didww_candidate=$(command -v javac)
    if command -v readlink > /dev/null 2>&1; then
        _didww_resolved=$(readlink -f "$_didww_candidate" 2>/dev/null || echo "$_didww_candidate")
        _didww_candidate=$(dirname "$(dirname "$_didww_resolved")")
        [ "$(_didww_java_major "$_didww_candidate")" = "17" ] && _didww_java="$_didww_candidate"
        unset _didww_resolved
    fi
fi

if [ -z "$_didww_java" ]; then
    if [ "$_didww_os" = "Darwin" ]; then
        _didww_fail "no JDK 17 found. The build needs one; it will not use a newer JDK." \
            "brew install --cask temurin@17" \
            "" \
            "Or set JAVA_HOME yourself if you manage JDKs with jenv, SDKMAN, asdf or mise."
    else
        _didww_fail "no JDK 17 found. The build needs one; it will not use a newer JDK." \
            "Debian/Ubuntu:  sudo apt-get install -y openjdk-17-jdk" \
            "Fedora/RHEL:    sudo dnf install -y java-17-openjdk-devel" \
            "Arch:           sudo pacman -S jdk17-openjdk" \
            "Any distro:     sdk install java 17.0.20-tem   (SDKMAN)" \
            "" \
            "Or set JAVA_HOME yourself if you already have one somewhere unusual."
    fi
    return 1
fi

# ---------------------------------------------------------------------------------------
# ANDROID_HOME
#
# Gradle also reads sdk.dir from local.properties, which is gitignored. Setting the
# environment variable instead keeps one answer for the shell, Gradle and the IDE.
# ---------------------------------------------------------------------------------------
_didww_sdk=""

for _didww_candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "$HOME/android-sdk" \
    /opt/android-sdk \
    /usr/lib/android-sdk
do
    [ -n "$_didww_candidate" ] || continue
    # An SDK root is recognised by containing a tool directory, not by existing. A stale
    # ANDROID_HOME pointing at an empty directory otherwise wins and fails much later,
    # inside AGP, with a message about a missing platform rather than a missing SDK.
    if [ -d "$_didww_candidate/cmdline-tools" ] || [ -d "$_didww_candidate/platform-tools" ]; then
        _didww_sdk="$_didww_candidate"
        break
    fi
done

if [ -z "$_didww_sdk" ]; then
    _didww_default_sdk="$HOME/Android/Sdk"
    [ "$_didww_os" = "Darwin" ] && _didww_default_sdk="$HOME/Library/Android/sdk"
    _didww_fail "no Android SDK found." \
        "Looked in ANDROID_HOME, ANDROID_SDK_ROOT and the platform defaults." \
        "" \
        "Installing Android Studio provides one at $_didww_default_sdk." \
        "For a headless machine, sample/README.md § 1.2 installs the command-line tools" \
        "directly — about 2 GB, no IDE."
    unset _didww_default_sdk
    return 1
fi

# ---------------------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------------------
JAVA_HOME="$_didww_java"
ANDROID_HOME="$_didww_sdk"
# Deprecated by Google in favour of ANDROID_HOME, but still read by parts of the toolchain
# and by third-party scripts. Keeping them in agreement is cheaper than finding out which.
ANDROID_SDK_ROOT="$_didww_sdk"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT

_didww_path_prepend "$ANDROID_HOME/emulator"
_didww_path_prepend "$ANDROID_HOME/platform-tools"
_didww_path_prepend "$ANDROID_HOME/cmdline-tools/latest/bin"
_didww_path_prepend "$JAVA_HOME/bin"
export PATH

echo "JAVA_HOME     $JAVA_HOME"
echo "ANDROID_HOME  $ANDROID_HOME"

# platform-tools carries adb, and without it nothing can be installed on a device or an
# emulator. Worth naming now rather than as `command not found` three steps later.
if [ ! -x "$ANDROID_HOME/platform-tools/adb" ]; then
    echo ""
    echo "note: adb is not installed. Install it with:"
    echo "    sdkmanager --install platform-tools"
fi

unset _didww_os _didww_java _didww_sdk _didww_candidate
unset -f _didww_fail _didww_java_major _didww_path_prepend _didww_scan_for_jdk17 2> /dev/null
