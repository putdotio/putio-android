#!/bin/sh
set -eu
state="$FAKE_PROOF_DIR/state"
mode=$(cat "$state/mode")
printf '%s %s\n' "${0##*/}" "$*" >> "$state/commands"
case "${0##*/}" in
  putio)
    test "$PUTIO_CLI_PROFILE" = devs-auto
    test -z "${PUTIO_CLI_TOKEN+x}"
    case "$1" in
      auth) printf '%s\n' '{"authenticated":true,"source":"profile","profile":"devs-auto","apiBaseUrl":"https://api.put.io"}' ;;
      whoami)
        if [ "$mode" = wrong-account ]; then id=99; else id=11; fi
        printf '{"info":{"user_id":%s,"username":"devs-auto"}}\n' "$id"
        ;;
      files)
        if [ "$mode" = missing-fixture ]; then files='[]'; else
          files='[{"id":13,"parent_id":12,"name":"Rename me"},{"id":14,"parent_id":12,"name":"Cancel me"}]'
        fi
        printf '{"parent":{"id":12,"name":"Owned container","file_type":"FOLDER"},"files":%s,"cursor":null}\n' "$files"
        ;;
      *) exit 91 ;;
    esac
    ;;
  apkanalyzer)
    case "$3" in
      */test.apk) printf '%s\n' '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.put.putio.mobile.debug.test"><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="io.put.putio.mobile.debug"/></manifest>' ;;
      *) printf '%s\n' '<manifest package="io.put.putio.mobile.debug"/>' ;;
    esac
    ;;
  adb)
    test "$1" = -s && test "$2" = emulator-5584
    shift 2
    case "$1" in
      get-state) echo device ;;
      install) test "$2" = -r; echo Success ;;
      shell)
        script=$2
        case "$script" in
          'getprop ro.build.version.sdk') echo 37 ;;
          "dumpsys activity processes 'io.put.putio.mobile.debug'")
            printf '%s\n' 'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)'
            if [ -f "$state/instrumentation" ]; then
              printf '%s\n' '  Active instrumentation:' '    * ActiveInstrumentation{abc123 io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner}' '      mClass=ComponentInfo{io.put.putio.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner} mFinished=false'
            fi
            ;;
          *'exec screenrecord'*)
            [ "$mode" != recorder-exit ] || exit 4
            capture=$(printf '%s\n' "$script" | sed -n 's|.*\(/data/local/tmp/putio-rename-[a-f0-9-]*\.mp4\).*|\1|p')
            test -n "$capture"
            printf '%s' "$capture" > "$state/capture"
            touch "$state/recorder"
            [ "$mode" = missing-pid ] || echo 27182 > "$state/pid"
            echo "$$" > "$state/recorder-host-pid"
            while [ -f "$state/recorder" ]; do sleep 0.1; done
            ;;
          "cat '/data/local/tmp/putio-rename-"*'.pid'*) cat "$state/pid" 2>/dev/null ;;
          "test -s '/data/local/tmp/putio-rename-"*) [ ! -f "$state/recorder" ] || echo ready ;;
          'if test -d /proc/27182; then cat /proc/27182/cmdline; else printf absent; fi')
            if [ -f "$state/recorder" ]; then printf 'screenrecord\000--time-limit\000180\000%s\000' "$(cat "$state/capture")"; else printf absent; fi
            ;;
          "'am' 'instrument'"*)
            touch "$state/instrumentation" "$state/instrumentation-started"
            case "$mode" in
              adb-exit) exit 17 ;;
              interrupt) while [ -f "$state/instrumentation" ]; do sleep 0.1; done ;;
              *)
                class=io.putdotio.android.AuthenticatedFilesRenameTest
                method=authenticatedRenamePreservesSessionAndCancel
                if [ "$mode" != missing-result ]; then
                  printf 'INSTRUMENTATION_STATUS: class=%s\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 1\n' "$class" "$method"
                  if [ "$mode" = skipped ]; then code=-3; else code=-2; fi
                  printf 'INSTRUMENTATION_STATUS: class=%s\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: %s\n' "$class" "$method" "$code"
                fi
                printf 'INSTRUMENTATION_CODE: -1\n'
                ;;
            esac
            ;;
          "am force-stop 'io.put.putio.mobile.debug'") rm -f "$state/instrumentation" ;;
          'kill -INT 27182'|'kill -KILL 27182') rm -f "$state/recorder" ;;
          "rm -f '/data/local/tmp/putio-rename-"*)
            test ! -f "$state/recorder"
            rm -f "$state/pid" "$state/capture"
            touch "$state/remote-files-removed"
            ;;
          *) exit 92 ;;
        esac
        ;;
      *) exit 93 ;;
    esac
    ;;
  *) exit 94 ;;
esac
