def prepare(){
  common.conditionalStage(
    stage_name: "Prepare Deployment",
    stage: {
      dir("/opt/rpc-openstack"){
        git branch: env.RPC_BRANCH, url: env.RPC_REPO
        sh "git submodule update --init"
        withEnv([
          "DEPLOY_AIO=yes",
          "DEPLOY_OA=no",
          "DEPLOY_RPC=no"
        ]){
          sh """#!/bin/bash
          scripts/deploy.sh
          """
        } //env
      } //dir
      withCredentials([
        usernamePassword(
          credentialsId: "dev_pubcloud_user_key",
          usernameVariable: "PUBCLOUD_USERNAME",
          passwordVariable: "PUBCLOUD_APIKEY"
        ),
        string(
          credentialsId: "dev_pubcloud_tennant",
          variable: "PUBCLOUD_TENANT_ID"
        )
      ]){
        dir("/opt/rpc-gating"){
          git branch: env.RPC_GATING_BRANCH, url: env.RPC_GATING_REPO
        } //dir
        dir("/opt/rpc-gating/playbooks"){
          ansiblePlaybook playbook: "aio_config.yml"
        } //dir
      } //withCredentials
    } //stage param
  ) //conditional stage
}
return this;
