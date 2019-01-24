#!/bin/bash -e

# This script is used as a user data file when building
# machines which use images that are not managed by
# nodepool. See https://cloud-init.io for more information
# about how cloud-init uses user-data files.
#
# This script tries to ensure that the machine is prepared
# in a consistent way and it has all the required packages
# installed on it for jenkins/ansible to access it. Any
# further preparation should be done using jenkins/ansible.

# Get the distribution name
echo -e "\n### Getting the distribution name ###\n"
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

# Save a backup of the original apt sources
if [[ ! -f "/etc/apt/sources.list.original" ]]; then
  echo -e "\n### Saving a backup of the original apt sources ###\n"
  mv /etc/apt/sources.list /etc/apt/sources.list.original
  echo -e "\n###Rewriting the apt sources ###\n"
  DISTRO_MIRROR="http://mirror.rackspace.com/ubuntu"
  DISTRO_COMPONENTS="main,universe"
  cat << EOF >/etc/apt/sources.list
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE} ${DISTRO_COMPONENTS//,/ }
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE}-updates ${DISTRO_COMPONENTS//,/ }
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE}-backports ${DISTRO_COMPONENTS//,/ }
deb ${DISTRO_MIRROR} ${DISTRO_RELEASE}-security ${DISTRO_COMPONENTS//,/ }
EOF
fi

# Enable debug logging for apt to make diagnosis of apt failures easier
if [[ ! -f "/etc/apt/apt.conf.d/99debug" ]]; then
  echo -e "\n### Enabling apt debug logging ###\n"
  echo 'Debug::Acquire::http "true";' > /etc/apt/apt.conf.d/99debug
fi

# Add jenkins user and group
JENKINS_HOME="/var/lib/jenkins"
if ! getent group jenkins &> /dev/null; then
  echo -e "\n### Adding the jenkins group ###\n"
  groupadd jenkins
fi
if ! getent passwd jenkins &> /dev/null; then
  echo -e "\n### Adding the jenkins user ###\n"
  useradd --gid jenkins \
          --shell /bin/bash \
          --home-dir ${JENKINS_HOME} \
          --create-home jenkins
fi

# Fetch the rcbops public keys and add them to authorized_keys
echo -e "\n### Fetching the rcbops public keys ###\n"
SSH_PUBLIC_KEYS_URL="https://raw.githubusercontent.com/rcbops/rpc-gating/master/keys/rcb.keys"
curl --silent \
     --show-error \
     --fail \
     --connect-timeout 5 \
     --retry 3 \
     ${SSH_PUBLIC_KEYS_URL} > /tmp/ssh-public-keys

echo -e "\n### Configuring authorized_keys for jenkins and root ###\n"
for usr_home in /root ${JENKINS_HOME}; do
  mkdir -p ${usr_home}/.ssh
  chmod 700 ${usr_home}/.ssh
  cat /tmp/ssh-public-keys >> ${usr_home}/.ssh/authorized_keys
  chmod 644 ${usr_home}/.ssh/authorized_keys
done

# Configure sudoers for the jenkins user
echo -e "\n### Configuring sudoers for jenkins ###\n"
cat > /etc/sudoers.d/jenkins << EOF
jenkins ALL=(ALL) NOPASSWD:ALL
EOF
chmod 0440 /etc/sudoers.d/jenkins

# Ensure everything has the right owner
echo -e "\n### Ensuring jenkins owns all jenkins home files ###\n"
chown -R jenkins:jenkins ${JENKINS_HOME}

# For Ubuntu Trusty add the openjdk PPA for access
# to the openjdk8 packages
echo -e "\n### Adding openjdk PPA for Ubuntu Trusty hosts ###\n"
if [[ "${DISTRO_RELEASE}" == "trusty" ]]; then
  add-apt-repository -y ppa:openjdk-r/ppa
fi

# Prepare the list of packages to install
echo -e "\n### Preparing a list of packages to install ###\n"
pkgs_required=""
pkgs_to_install=""
pkgs_required+=" openjdk-8-jre-headless" # for the jenkins agent
pkgs_required+=" python-minimal" # required by ansible
pkgs_required+=" python-yaml" # required by ansible

for pkg in ${pkgs_required}; do
  if ! dpkg-query --list ${pkg} &>/dev/null; then
    pkgs_to_install+=" ${pkg}"
  fi
done

# Update the apt cache and installing the required packages
if [[ "${pkgs_to_install}" != "" ]]; then
  echo -e "\n### Updating the apt cache ###\n"
  apt-get update
  echo -e "\n### Installing the required packages ###\n"
  export DEBIAN_FRONTEND="noninteractive"
  apt-get install -y ${pkgs_to_install}
fi

# Regardless of the last command's return code, ensure that the script exits
# with RC=0, otherwise cloud-init may fail the boot.
echo -e "\n### Host preparation complete ###\n"
exit 0
