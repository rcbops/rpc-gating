def deploy(){
  common.conditionalStep(
    step_name: "Deploy",
    step: {
      playbooks = common._parse_json_string(json_text: env.PLAYBOOKS)
      print(playbooks)
      print(playbooks.size())
      for (def i=0; i<playbooks.size(); i++){
        playbook = playbooks[i]
        print(playbook)
        stage(playbook.playbook){
          common.openstack_ansible(playbook)
        } //stage
      } // for
    } //  step
  ) //conditionalStep
}

def deploy_sh(Map args) {
  common.conditionalStage(
    stage_name: "Deploy RPC w/ Script",
    stage: {
      environment_vars = args.environment_vars +
        ['ANSIBLE_FORCE_COLOR=true', 'ANSIBLE_HOST_KEY_CHECKING=False']
      withEnv(environment_vars) {
        ansiColor('xterm') {
          dir("/opt/rpc-openstack/") {
            sh """#!/bin/bash
            scripts/deploy.sh
            """
          } // dir
        } // ansiColor
      } // withEnv
    } // stage
  ) // conditionalStage
}

return this;
