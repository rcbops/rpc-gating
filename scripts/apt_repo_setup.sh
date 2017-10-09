#!/bin/bash -xeu
# Copyright 2014-2017 , Rackspace US, Inc.
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

# This script is run on a single-use slave in order to replace
# the default apt sources with the appropriate RPC-O apt artifact
# repo. This script will only be used if the slave setup playbook
# determines that the repo being tested is RPC-O.

# Expect the appropriate release as a CLI parameter
RPC_RELEASE==${1:-"none"}

# Only do something is a release version is given
if [[ ${RPC_RELEASE} != "none" ]]; then

  # Set the appropriate URL as its used often
  HOST_RCBOPS_REPO="http://rpc-repo.rackspace.com"

  # Read the OS information
  source /etc/os-release
  source /etc/lsb-release

  # Only change the sources if there are artifacts available for this release
  CHECK_URL="${HOST_RCBOPS_REPO}/apt-mirror/integrated/dists/${RPC_RELEASE}-${DISTRIB_CODENAME}"
  if curl --output /dev/null --silent --head --fail ${CHECK_URL}; then

    # Replace the existing apt sources with the artifacted sources.
    sed -i '/^deb-src /d' /etc/apt/sources.list
    sed -i '/-backports /d' /etc/apt/sources.list
    sed -i '/-security /d' /etc/apt/sources.list
    sed -i '/-updates /d' /etc/apt/sources.list

    # Add the RPC-O apt repo source
    echo "deb ${HOST_RCBOPS_REPO}/apt-mirror/integrated/ ${RPC_RELEASE}-${DISTRIB_CODENAME} main" \
      > /etc/apt/sources.list.d/rpco.list

    # Install the RPC-O apt repo key
    curl --silent --fail ${HOST_RCBOPS_REPO}/apt-mirror/rcbops-release-signing-key.asc | apt-key add -

  fi
fi
