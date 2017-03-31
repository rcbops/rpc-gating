def python(Map args) {
  common.runStage(
    stage_name: "Build Python Artifacts",
    conditional: True,
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
  ) // runStage
}

def container(Map args) {
  common.runStage(
    stage_name: "Build Container Artifacts",
    conditional: True,
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
  ) // runStage
}

return this
