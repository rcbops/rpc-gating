#!/bin/bash

# Save a backup of the original file
mv /etc/apt/sources.list /etc/apt/sources.list.original

# Set the environment variables
DISTRO_MIRROR="http://mirror.rackspace.com/ubuntu"
DISTRO_COMPONENTS="main,universe"

# Get the distribution name
if [[ -e /etc/lsb-release ]]; then
  source /etc/lsb-release
  DISTRO_RELEASE=${DISTRIB_CODENAME}
elif [[ -e /etc/os-release ]]; then
  source /etc/os-release
  DISTRO_RELEASE=${UBUNTU_CODENAME}
else
  echo "Unable to determine distribution due to missing lsb/os-release files."
  exit 1
fi

# Rewrite the apt sources file
cat << EOF >/etc/apt/sources.list
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE} ${DISTRO_COMPONENTS//,/ }
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE}-updates ${DISTRO_COMPONENTS//,/ }
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE}-backports ${DISTRO_COMPONENTS//,/ }
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE}-security ${DISTRO_COMPONENTS//,/ }
EOF
