def prepare(){
  common.runStage(
    stage_name: "Prepare Deployment",
    conditional: True,
    stage: {
      prepareRpcGit()
      ansiColor('xterm'){
        dir("/opt/rpc-openstack"){
          withEnv( common.get_deploy_script_env() + [
            "DEPLOY_AIO=yes",
            "DEPLOY_OA=no",
            "DEPLOY_SWIFT=${env.DEPLOY_SWIFT}",
            "DEPLOY_ELK=${env.DEPLOY_ELK}",
            "DEPLOY_RPC=no"
          ]){
            sh """#!/bin/bash
            scripts/deploy.sh
            """
          } // env
        } // dir
      } // ansiColor
      common.prepareConfigs(
        deployment_type: "aio"
      )
    } //stage param
  )
}
return this;
