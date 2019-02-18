if (env.RPC_GATING_BRANCH != "master") {
  library "rpc-gating@${env.RPC_GATING_BRANCH}"
} else {
  library "rpc-gating-master"
}
common.globalWraps(){
  common.use_node('ArtifactBuilder2') {
    // ArtifactBuilder2 only has a single executor, so no other jobs will be attempting to
    // modify the repo while this is running.
    stage("Preparation"){
      sh """
        # The vars for these are set in common.stdjob which I'm not using
        mkdir -p ${WORKSPACE}/results
        mkdir -p ${WORKSPACE}/artifacts
      """
    }
    stage("Verify Repos"){
      sh """#!/bin/bash
        set -xe
        # Note that the hash cache will only be used during
        # this build as the workspace will be cleared at the end
        # Can't use .venv as its py2 only.
        virtualenv --python=python3 .venv3
        . .venv3/bin/activate
        pip3 install click sh junit-xml pyyaml
        fname(){
          # UUoE because <<< didn't work in jenkins,
          # despite working on artifact builder 2 in manual testing.
          echo \${1} | sed 's+/+-+g'
        }
        for repo in integrated \
          independant/rax-maas-xenial \
          independant/hwraid-xenial \
          independant/rax-maas-trusty \
          independant/hwraid-trusty
        do
          cd /var/www/artifacts/apt/public/\${repo}
          python3 ${WORKSPACE}/rpc-gating/scripts/apt_repo_hash_check.py \
            --hash-cache ${WORKSPACE}/verify.cache.yaml \
            --repo-path /var/www/artifacts/apt/public/\${repo}/ \
            find-and-verify-packages-files \
              --yaml-report ${WORKSPACE}/artifacts/\$(fname \${repo}).yaml \
              --junit-report ${WORKSPACE}/results/\$(fname \${repo}).xml
        done
      """
    }
    stage("Collect Artifacts"){
      common.archive_artifacts()
    }
    stage("Create Jira Issue on Failure"){
      // Junit sets the build result to unstable if at least one test fails.
      // but IMO that means the job should fail.
      if(currentBuild.result == "UNSTABLE"){
        currentBuild.result = "FAILURE"
        common.build_failure_issue("RE", ["rpc-repo","illegal-package-update"])
      }
    } // stage
  } // node
} // globalWraps
