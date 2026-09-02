#!/usr/bin/env bash
# Answers one question: can this machine build the Android half of Trim?
#
# Milestone 2 needs the Android SDK, the Android Gradle Plugin and androidx, all of which
# are served only from dl.google.com. A container whose egress policy denies that host
# fails in a way that looks like a dependency-resolution bug, so check it up front rather
# than diagnosing it a third time.
#
# Usage: tools/preflight-android.sh    (exit 0 = ready, 1 = not)

set -uo pipefail
ready=0

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ok    %s\n' "$*"; }
bad()  { printf '  MISS  %s\n' "$*"; ready=1; }

say "Trim — Android build preflight"
say ""

say "Toolchain"
if command -v java >/dev/null 2>&1; then
  ok "java: $(java -version 2>&1 | grep -v 'JAVA_TOOL_OPTIONS' | head -1)"
else
  bad "java is not on PATH (JDK 17+ required)"
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$sdk" ] && [ -d "$sdk/platforms" ]; then
  ok "Android SDK at $sdk"
else
  bad "no Android SDK (set ANDROID_HOME or ANDROID_SDK_ROOT to an SDK with platforms/)"
fi

say ""
say "Network — the hosts an Android build resolves against"
# curl writes %{http_code} even when it fails, so capture its exit status separately
# rather than falling back inside the substitution — the two outputs would concatenate.
probe() {
  local host="$1" code
  code="$(curl -sS -o /dev/null -m 25 -w '%{http_code}' "https://$host/" 2>/dev/null)"
  if [ $? -ne 0 ] || [ -z "$code" ] || [ "$code" = "000" ]; then
    printf '000'
  else
    printf '%s' "$code"
  fi
}

for host in dl.google.com maven.google.com repo1.maven.org services.gradle.org; do
  code="$(probe "$host")"
  if [ "$code" = "000" ]; then
    case "$host" in
      dl.google.com|maven.google.com)
        bad "$host BLOCKED — this is the blocker: the Android SDK, AGP and every androidx artifact are served only from here" ;;
      *)
        bad "$host unreachable" ;;
    esac
  else
    ok "$host reachable (HTTP $code)"
  fi
done

say ""
say "Emulator (instrumented tests only; the module still builds without it)"
if [ -e /dev/kvm ]; then
  ok "/dev/kvm present"
else
  say "  note  no /dev/kvm — the module compiles, but connectedAndroidTest needs a device"
fi

say ""
if [ "$ready" -eq 0 ]; then
  say "Ready. See docs/M2-STATUS.md for what to build next."
else
  say "Not ready. The Milestone 1 core still builds and tests here:"
  say "    ./gradlew check"
  say "See docs/M2-STATUS.md for the environment Milestone 2 needs."
fi
exit "$ready"
