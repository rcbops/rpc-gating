def prepare(){
  common.conditionalStage(
    stage_name: "Prepare Deployment",
    stage: {
      dir("/opt/rpc-openstack"){
        if (env.STAGES.contains("Upgrade")) {
          branch = env.UPGRADE_FROM_REF
        } else {
          branch = env.RPC_BRANCH
        }
        // checkout used instead of git as a custom refspec is required
        // to checkout pull requests
        checkout([$class: 'GitSCM',
          branches: [[name: branch ]],
          doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'CleanCheckout']],
          submoduleCfg: [],
          userRemoteConfigs: [
            [
              url: RPC_REPO,
              refspec: '+refs/pull/*:refs/remotes/origin/pr/* +refs/heads/*:refs/remotes/origin/*'
            ]
          ]
        ])
        sh "git submodule update --init"
        ansiColor('xterm'){
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
