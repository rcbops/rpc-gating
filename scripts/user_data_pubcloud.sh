#!/bin/bash

# This file is used when creating public
# cloud instances.
# See playbooks/allocate_pubcloud.yml

source /etc/lsb-release

# Delete configuration which enables automatic upgrades
# to prevent the instance packages being upgraded before
# the correct apt repositories have been configured.
# ref: RE-458 / RE-473
if [[ "${DISTRIB_CODENAME}" == "trusty" ]]; then
    # The 'unattended-upgrades' package cannot
    # be purged on trusty as it is a dependency
    # for cloud-init. So instead we just remove
    # the configuration.
    rm -f /etc/apt/apt.conf.d/*unattended-upgrades
else
    export DEBIAN_FRONTEND=noninteractive
    apt-get purge -y unattended-upgrades
fi
exit 0
