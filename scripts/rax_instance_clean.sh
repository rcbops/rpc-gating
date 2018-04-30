#!/bin/bash

set -x -v

# Copyright 2017, Rackspace US, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Prepare an Ubuntu public cloud image for the purpose of using it as a
# static starting point in gate tests.
#

#
# Config
#

# Set the apt sources we want
source /etc/lsb-release
echo "deb http://mirror.rackspace.com/ubuntu ${DISTRIB_CODENAME} main universe" > /etc/apt/sources.list
echo "deb http://mirror.rackspace.com/ubuntu ${DISTRIB_CODENAME}-updates main universe" >> /etc/apt/sources.list
echo "deb http://security.ubuntu.com/ubuntu ${DISTRIB_CODENAME}-security main universe" >> /etc/apt/sources.list

# Set cloud-init to preserve apt sources
sed -i '2s/^/apt_preserve_sources_list: True\n/' /etc/cloud/cloud.cfg.d/99-rackspace-general.cfg
sed -i '2s/^/ssh_deletekeys: True\n/' /etc/cloud/cloud.cfg.d/99-rackspace-general.cfg

# Delete extra apt sources which have nothing in them
rm -f /etc/apt/sources.list.d/cloud-support-*rax-devel*

# Delete extra cloud-init config files containing duplicate config
rm -f /etc/cloud/cloud.cfg.d/99-rackspace-.cfg
rm -f /etc/cloud/cloud.cfg.d/99-rax-openstack-guest-agents.cfg

# Delete configuration which enables automatic upgrades
# to prevent the instance packages being upgraded before
# the correct apt repositories have been configured.
# ref: RE-458
if [[ "${DISTRIB_CODENAME}" == "trusty" ]]; then
    # The 'unattended-upgrades' package cannot
    # be purged on trusty as it is a dependency
    # for cloud-init. So instead we just remove
    # the configuration.
    rm -f /etc/apt/apt.conf.d/*unattended-upgrades
else
    apt-get purge -y unattended-upgrades
fi

# Ensure that rc.local executes on startup
# This is only required for SystemD and is
# therefore only applied to Xenial.
if [[ "${DISTRIB_CODENAME}" == "xenial" ]]; then
    systemctl enable rc-local.service
fi

# Set the host ssh keys to regenerate at first boot if
# they are missing.
cp /etc/rc.local /etc/rc.local.bak
cat > /etc/rc.local <<'EOF'
#!/bin/bash
dpkg-reconfigure openssh-server
mv /etc/rc.local.bak /etc/rc.local
EOF

# minimal network conf that doesnt dhcp
# causes boot delay if left out, no bueno
cat > /etc/network/interfaces <<'EOF'
auto lo
iface lo inet loopback
EOF

# stage a clean hosts file
cat > /etc/hosts <<'EOF'
# The following lines are desirable for IPv6 capable hosts
::1     ip6-localhost ip6-loopback
fe00::0 ip6-localnet
ff00::0 ip6-mcastprefix
ff02::1 ip6-allnodes
ff02::2 ip6-allrouters
127.0.0.1 localhost
EOF

# set some stuff
echo 'net.ipv4.conf.eth0.arp_notify = 1' >> /etc/sysctl.conf
echo 'vm.swappiness = 0' >> /etc/sysctl.conf

# Remove the authorized key
echo > /root/.ssh/authorized_keys

#
# Clean up
#

apt-get -y clean
apt-get -y autoremove
rm -f /etc/ssh/ssh_host_*
rm -f /var/cache/apt/archives/*.deb
rm -f /var/cache/apt/*cache.bin
rm -f /var/lib/apt/lists/*_Packages
rm -f /root/.bash_history
rm -f /root/.nano_history
rm -f /root/.lesshst
rm -f /root/.ssh/authorized_keys.bak*
rm -f /root/.ssh/known_hosts
for k in $(find /var/log -type f); do
    echo > $k
done
for k in $(find /tmp -type f); do
    rm -f $k
done
for k in $(find /root -type f \( ! -iname ".*" \)); do
    rm -f $k
done
