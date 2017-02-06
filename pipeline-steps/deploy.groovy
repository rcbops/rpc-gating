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

// wrap this func in withEnv to set params
def deploy_sh(){
  dir("/opt/rpc-openstack/"){
    sh """#!/bin/bash
    scripts/deploy.sh
    """
  }
}

return this;
