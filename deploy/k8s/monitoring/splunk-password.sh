#!/bin/sh
# Changes the Splunk admin password in both places that must agree: Splunk itself (through its REST API, from inside
# the pod) and the secret "splunk" (used by the image's startup script, and by install.sh's first setup).
#
#   deploy/k8s/monitoring/splunk-password.sh        prompts for the new password (not echoed); bash, Git Bash or Linux
#
# The password never appears on a command line or in the output. Its length is checked by Splunk's password policy
# (Settings -> Password Management), and it may not contain a double quote or backslash (it goes into the secret as JSON).
set -eu
export MSYS_NO_PATHCONV=1
k() { kubectl --context "${KUBE_CONTEXT:-rancher-desktop}" -n monitoring "$@"; }

if [ -t 0 ]; then
    stty -echo; printf 'New Splunk admin password: '; IFS= read -r PW; printf '\nRepeat it: '; IFS= read -r PW2
    stty echo; printf '\n'
    [ "$PW" = "$PW2" ] || { echo "The two entries differ: nothing changed." >&2; exit 1; }
else
    IFS= read -r PW || true
fi
[ -n "$PW" ] || { echo "Empty password: nothing changed." >&2; exit 1; }
case "$PW" in *\"*|*\\*) echo "No \" or \\ in the password, please: nothing changed." >&2; exit 1 ;; esac

# 1. Splunk: authenticated with the current password, from the secret (the two agree).
OLD=$(k get secret splunk -o jsonpath='{.data.admin-password}' | base64 -d)
status=$(printf '%s\n%s\n' "$OLD" "$PW" | k exec -i deploy/splunk -- sh -c '
    IFS= read -r OLD; IFS= read -r NEW
    curl -sk -o /dev/null -w "%{http_code}" -u "admin:$OLD" \
        https://127.0.0.1:8089/services/authentication/users/admin \
        --data-urlencode "password=$NEW" --data-urlencode "oldpassword=$OLD"')
unset OLD
if [ "$status" != 200 ]; then
    echo "Splunk refused the change (HTTP $status: 401 means the secret no longer matches Splunk's password," \
         "400 its password policy): nothing changed." >&2
    exit 1
fi
# 2. The secret, keeping the HEC token.
k patch secret splunk --type merge -p "{\"stringData\":{\"admin-password\":\"$PW\"}}" >/dev/null
unset PW PW2
echo "Password changed in Splunk and in the secret. Log in at http://127.0.0.1:8000 as admin."
