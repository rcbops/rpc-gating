def get_rpc_repo_creds(){
  return [
    string(
      credentialsId: "RPC_REPO_IP",
      variable: "REPO_HOST"
    ),
    string(
      credentialsId: "RPC_REPO_SSH_USERNAME_TEXT",
      variable: "REPO_USER"
    ),
    file(
      credentialsId: "RPC_REPO_SSH_USER_PRIVATE_KEY_FILE",
      variable: "REPO_USER_KEY"
    ),
    file(
      credentialsId: "RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE",
      variable: "REPO_HOST_PUBKEY"
    ),
    file(
      credentialsId: "RPC_REPO_GPG_SECRET_KEY_FILE",
      variable: "GPG_PRIVATE"
    ),
    file(
      credentialsId: "RPC_REPO_GPG_PUBLIC_KEY_FILE",
      variable: "GPG_PUBLIC"
    )
  ]
}

def apt() {
  common.conditionalStage(
    stage_name: "Build Apt Artifacts",
    stage: {
      withCredentials(get_rpc_repo_creds()) {
        prepareRpcGit()
        ansiColor('xterm') {
          dir("/opt/rpc-openstack/") {
            sh """#!/bin/bash
            scripts/artifacts-building/apt/build-apt-artifacts.sh
            """
          } // dir
        } // ansiColor
      } // withCredentials
    } // stage
  ) // conditionalStage
}

def python() {
  common.conditionalStage(
    stage_name: "Build Python Artifacts",
    stage: {
      pubcloud.runonpubcloud {
        try {
          withCredentials(get_rpc_repo_creds()) {
            prepareRpcGit()
            ansiColor('xterm') {
              dir("/opt/rpc-openstack/") {
                sh """#!/bin/bash
                scripts/artifacts-building/python/build-python-artifacts.sh
                """
              } // dir
            } // ansiColor
          } // withCredentials
        } catch (e) {
          print(e)
          throw e
        } finally {
          common.archive_artifacts()
        }
      } // pubcloud slave
    } // stage
  ) // conditionalStage
}

def container() {
  common.conditionalStage(
    stage_name: "Build Container Artifacts",
    stage: {
      pubcloud.runonpubcloud {
        try {
          withCredentials(get_rpc_repo_creds()) {
            prepareRpcGit()
            ansiColor('xterm') {
              dir("/opt/rpc-openstack/") {
                sh """#!/bin/bash
                scripts/artifacts-building/containers/build-process.sh
                """
              } // dir
            } // ansiColor
          } // withCredentials
        } catch (e) {
          print(e)
          throw e
        } finally {
          common.archive_artifacts()
        }
      } // pubcloud slave
    } // stage
  ) // conditionalStage
}

return this
