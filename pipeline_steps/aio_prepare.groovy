def prepare(){
  common.conditionalStage(
    stage_name: "Prepare Deployment",
    stage: {
      if (env.STAGES.contains("Major Upgrade") || env.STAGES.contains("Leapfrog Upgrade")) {
        common.prepareRpcGit(env.UPGRADE_FROM_REF)
      } else {
        common.prepareRpcGit()
      }
      // If this branch can prepare its own configs, skip this stage.
      config_cap_file="${env.WORKSPACE}/rpc-openstack/gating/capabilities/aio_config"
      if (fileExists(config_cap_file)){
        print ("Skipping rpc-gating config prep as this is handled in repo."
               +" Determined by the existence of ${config_cap_file}.")
        return
      }
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
