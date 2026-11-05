#!/system/bin/sh
export NB_WRAPPED=1
log -t NB_WRAP "$@"
exec "$@"
