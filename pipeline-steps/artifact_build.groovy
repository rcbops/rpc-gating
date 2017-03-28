def python(Map args) {
  common.conditionalStage(
    stage_name: "Build Python Artifacts",
    stage: {
      withEnv(args.environment_vars) {
        withCredentials([
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
          )
        ]){
          ansiColor('xterm') {
            dir("/opt/rpc-openstack/") {
              sh """#!/bin/bash
              scripts/artifacts-building/python/build-python-artifacts.sh
              """
            } // dir
          } // ansiColor
        } // withCredentials
      } // withEnv
    } // stage
  ) // conditionalStage
}

def container(Map args) {
  common.conditionalStage(
    stage_name: "Build Container Artifacts",
    stage: {
      withEnv(args.environment_vars) {
        withCredentials([
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
          )
        ]){
          ansiColor('xterm') {
            dir("/opt/rpc-openstack/") {
              sh """#!/bin/bash
              scripts/artifacts-building/containers/build-process.sh
              """
            } // dir
          } // ansiColor
        } // withCredentials
      } // withEnv
    } // stage
  ) // conditionalStage
}

return this
