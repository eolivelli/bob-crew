# Run as root by bobcrew-entrypoint in sessions with capabilities: starts Docker Engine for the agent when
# the session has the docker capability. Its output goes to /var/log/bobcrew/dockerd.log, never to stdout
# (which carries ACP).
case ",${BOBCREW_CAPABILITIES:-}," in
  *,docker,*) ;;
  *) return 0 ;;
esac
command -v dockerd >/dev/null 2>&1 || { echo "the docker capability was asked for, but this image has no dockerd"; return 1; }
mkdir -p /var/log/bobcrew

# cgroup v2 allows controllers in a child group only when the parent holds no processes: move this
# container's processes into a child group, as Docker's own docker:dind image does
if [ -f /sys/fs/cgroup/cgroup.controllers ] && [ ! -d /sys/fs/cgroup/init ]; then
  mkdir -p /sys/fs/cgroup/init
  xargs -rn1 < /sys/fs/cgroup/cgroup.procs > /sys/fs/cgroup/init/cgroup.procs 2>/dev/null || :
  sed -e 's/ / +/g' -e 's/^/+/' < /sys/fs/cgroup/cgroup.controllers > /sys/fs/cgroup/cgroup.subtree_control 2>/dev/null || :
fi

nohup dockerd < /dev/null > /var/log/bobcrew/dockerd.log 2>&1 &
i=0
until docker version >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "dockerd did not start; the end of its log:"
    tail -n 20 /var/log/bobcrew/dockerd.log
    return 1
  fi
  sleep 0.5
done
echo "dockerd is running"
