def python(Map args) {
  common.conditionalStage(
    stage_name: "Build Python Artifacts",
    stage: {
      environment_vars = args.environment_vars
      withEnv(environment_vars) {
        ansiColor('xterm') {
          dir("/opt/rpc-openstack/") {
            sh """#!/bin/bash
            scripts/artifacts-building/python/build-python-artifacts.sh
            """
          } // dir
        } // ansiColor
      } // withEnv
    } // stage
  ) // conditionalStage
}

def container(Map args) {
  common.conditionalStage(
    stage_name: "Build Container Artifacts",
    stage: {
      environment_vars = args.environment_vars
      withEnv(environment_vars) {
        ansiColor('xterm') {
          dir("/opt/rpc-openstack/") {
            sh """#!/bin/bash
            scripts/artifacts-building/containers/build-process.sh
            """
          } // dir
        } // ansiColor
      } // withEnv
    } // stage
  ) // conditionalStage
}

return this
