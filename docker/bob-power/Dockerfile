# The Power User image: bob-java-python plus Docker Engine and Vagrant with libvirt (QEMU/KVM), so the
# agent can run containers and virtual machines of its own.
#
# The daemons run only in sessions with capabilities: the server must allow them
# (bobcrew.allowed-capabilities=docker,libvirt) and the session must ask for them (`capabilities: docker,
# libvirt` in the role, or the boxes in the UI). The container then starts as root, privileged;
# bobcrew-entrypoint runs root.d/, which starts dockerd for the docker capability and libvirtd for the libvirt
# one, and drops to the bob user, which is in the docker, kvm and libvirt groups. Being in those groups is as
# good as being root in the container, and a privileged container's root is root on the server: give this to
# trusted users only. Without capabilities the image works as bob-java-python does.
#
# Docker's and libvirt's data live in anonymous volumes (below), removed with the session's container.
# Virtual machines need /dev/kvm on the server (Intel VT-x or AMD-V, nested virtualisation on a VM).
#
# UBI 9 has neither QEMU nor libvirt, so they come from CentOS Stream 9; Docker comes from Docker's
# CentOS repository and Vagrant from HashiCorp's.
#
# Build with scripts/build-images.sh --only power (after base and java-python).
ARG BASE_IMAGE=bobcrew/bob-java-python:latest
FROM ${BASE_IMAGE}

USER root

COPY etc/centos-stream.repo /etc/yum.repos.d/centos-stream.repo

RUN dnf -y install dnf-plugins-core \
 && dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo \
 && dnf config-manager --add-repo https://rpm.releases.hashicorp.com/RHEL/hashicorp.repo \
 && sed -i 's/\$releasever/9/g' /etc/yum.repos.d/hashicorp.repo /etc/yum.repos.d/docker-ce.repo \
 && dnf -y install \
      docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
      iptables-nft nftables fuse-overlayfs \
      qemu-kvm qemu-img libvirt-daemon libvirt-daemon-kvm libvirt-daemon-config-network libvirt-client \
      libvirt-devel dnsmasq \
      vagrant rsync pkgconf-pkg-config \
      ansible-core \
 && dnf clean all && rm -rf /var/cache/dnf \
 && usermod -aG docker,kvm,libvirt bob \
 && mkdir -p /usr/local/libexec/bobcrew \
 && cp /usr/sbin/libvirtd /usr/local/libexec/bobcrew/libvirtd

# The copy of libvirtd above is what root.d starts: AppArmor profiles attach by path, also inside
# containers, and a host with libvirt installed (Ubuntu, Debian) confines /usr/sbin/libvirtd to its own
# QEMU paths, so that libvirtd cannot run this image's /usr/libexec/qemu-kvm.
COPY etc/daemon.json /etc/docker/daemon.json
COPY etc/libvirtd.conf /etc/libvirt/libvirtd.conf
COPY etc/qemu.conf /etc/libvirt/qemu.conf
COPY root.d/ /etc/bobcrew/root.d/
COPY profile.d/ /etc/bobcrew/profile.d/

# dockerd cannot keep its layers on the container's own overlay file system, and VM disks are large:
# both go to anonymous volumes, which the server removes with the container
VOLUME ["/var/lib/docker", "/var/lib/containerd", "/var/lib/libvirt"]

USER bob
COPY --chown=bob:bob etc/libvirt-client.conf /home/bob/.config/libvirt/libvirt.conf
COPY --chown=bob:bob etc/Vagrantfile /home/bob/.vagrant.d/Vagrantfile
RUN vagrant plugin install vagrant-libvirt >&2 \
 && vagrant plugin list && docker --version && vagrant --version && /usr/libexec/qemu-kvm --version && ansible --version

USER root
RUN chmod 644 /etc/bobcrew/root.d/*.sh /etc/bobcrew/profile.d/*.sh
USER bob
