def prepare(){
  common.conditionalStage(
    stage_name: "Prepare Deployment",
    stage: {
      dir("/opt/rpc-openstack"){
        git branch: env.RPC_BRANCH, url: env.RPC_REPO
        sh "git submodule update --init"
        ansiColor('xterm'){
          withEnv([
            "DEPLOY_AIO=yes",
            "DEPLOY_OA=no",
            "DEPLOY_ELK=${env.DEPLOY_ELK}",
            "DEPLOY_RPC=no",
            "ANSIBLE_FORCE_COLOR=true"
          ]){
            sh """#!/bin/bash
            scripts/deploy.sh
            """
          } //env
        } //color
      } //dir
      common.prepareConfigs(
        deployment_type: "aio"
      )
    } //stage param
  )
}
return this;
