#!/bin/bash -xeu

mkdir -p ${RE_HOOK_ARTIFACT_DIR}

# Record the datetime as a basic artifact
date > ${RE_HOOK_ARTIFACT_DIR}/datestamp

# Record the job parameters as an artifact
cat <<EOF > ${RE_HOOK_ARTIFACT_DIR}/environment
RE_JOB_NAME: ${RE_JOB_NAME}
RE_JOB_IMAGE: ${RE_JOB_IMAGE}
RE_JOB_SCENARIO: ${RE_JOB_SCENARIO}
RE_JOB_ACTION: ${RE_JOB_ACTION}
RE_JOB_FLAVOR: ${RE_JOB_FLAVOR}
RE_JOB_TRIGGER: ${RE_JOB_TRIGGER}
EOF
