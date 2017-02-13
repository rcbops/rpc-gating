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
            "DEPLOY_RPC=no",
            "ANSIBLE_FORCE_COLOR=true"
          ]){
            sh """#!/bin/bash
            scripts/deploy.sh
            """
          } //env
        } //color
      } //dir
      withCredentials([
        string(
          credentialsId: "dev_pubcloud_username",
          variable: "PUBCLOUD_USERNAME"
        ),
        string(
          credentialsId: "dev_pubcloud_api_key",
          variable: "PUBCLOUD_API_KEY"
        ),
        string(
          credentialsId: "dev_pubcloud_tenant_id",
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
