#!/usr/bin/env bash
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

# -------------- Import ---------------------#
export BASE_DIR=${BASE_DIR:-"/opt/rpc-openstack"}
source ${BASE_DIR}/scripts/functions.sh
# ------------ End import -------------------#

#---------- Required Vars -------------------#
export PUBCLOUD_USERNAME=${PUBCLOUD_USERNAME:?"PUBCLOUD_USERNAME is required"}
export PUBCLOUD_API_KEY=${PUBCLOUD_API_KEY:?"PUBCLOUD_API_KEY is required"}
export WORKSPACE=${WORKSPACE:-"$HOME"}
export CONTAINER=${CONTAINER:-"jenkins_logs"}
export SRC=${SRC:-"${WORKSPACE}/logs.tar.bz2"}
export HTML_REPORT_DEST=${HTML_REPORT_DEST:-"${WORKSPACE}/artifacts_report/index.html"}
export RAX_CREDS_FILE=${RAX_CREDS_FILE:-"$HOME/.pyrax.cfg"}
#--------------- End Vars -------------------#


echo "[rackspace_cloud]" > ${RAX_CREDS_FILE}
echo "identity_type: rackspace" >> ${RAX_CREDS_FILE}
echo "username: ${PUBCLOUD_USERNAME}" >> ${RAX_CREDS_FILE}
echo "api_key: ${PUBCLOUD_API_KEY}" >> ${RAX_CREDS_FILE}

echo "Uploading artifacts to Cloud Files..."
run_ansible -e container=${CONTAINER} -e src=${SRC} \
  -e html_report_dest=${HTML_REPORT_DEST} \
  -e description_file=${DESCRIPTION_FILE} \
  playbooks/upload_to_cloud_files.yml
