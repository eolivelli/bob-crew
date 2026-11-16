# Run as root by bobcrew-entrypoint in sessions with capabilities: starts libvirtd, for Vagrant's libvirt
# provider, when the session has the libvirt capability. Its logs go to /var/log/bobcrew/libvirtd.log, never
# to stdout (which carries ACP).
case ",${BOBCREW_CAPABILITIES:-}," in
  *,libvirt,*) ;;
  *) return 0 ;;
esac
[ -x /usr/local/libexec/bobcrew/libvirtd ] || { echo "the libvirt capability was asked for, but this image has no libvirtd"; return 1; }
mkdir -p /var/log/bobcrew /run/libvirt /var/lib/libvirt/images

# a privileged container sees the server's /dev/kvm with the server's group id: give it the
# container's kvm group (this changes the container's device node only)
if [ -c /dev/kvm ]; then
  chgrp kvm /dev/kvm && chmod 660 /dev/kvm
else
  echo "no /dev/kvm on this server: virtual machines will not start"
fi

virtlogd -d
# not /usr/sbin/libvirtd: see the Dockerfile (a host's AppArmor profile for that path would confine it)
nohup /usr/local/libexec/bobcrew/libvirtd < /dev/null > /var/log/bobcrew/libvirtd.log 2>&1 &
i=0
until virsh -c qemu:///system version >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "libvirtd did not start; the end of its log:"
    tail -n 20 /var/log/bobcrew/libvirtd.log
    return 1
  fi
  sleep 0.5
done
virsh -c qemu:///system pool-info default >/dev/null 2>&1 || {
  virsh -c qemu:///system pool-define-as default dir --target /var/lib/libvirt/images
  virsh -c qemu:///system pool-start default
  virsh -c qemu:///system pool-autostart default
}
virsh -c qemu:///system net-start default >/dev/null 2>&1 || :
echo "libvirtd is running"
