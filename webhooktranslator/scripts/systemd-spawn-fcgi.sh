#!/bin/bash
# From: http://redmine.lighttpd.net/projects/spawn-fcgi/wiki/Systemd/2
set -e
env
if [ "${LISTEN_PID}" != $$ ]; then
    echo >&2 "file descriptors not for us, pid not matching: '${LISTEN_PID}' != '$$'"
    exit 255
fi

if [ "${LISTEN_FDS}" != "1" ]; then
    echo >&2 "Requires exactly one socket passed to fastcgi, got: '${LISTEN_FDS:-0}'"
    exit 255
fi

unset LISTEN_FDS

# move socket from 3 to 0
exec 0<&3
exec 3<&-

# spawn fastcgi backend
exec "$@"
