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
      environment_vars = args.environment_vars + common.get_deploy_script_env()
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

def upgrade(Map args) {
  common.conditionalStage(
    stage_name: "Upgrade",
    stage: {
      environment_vars = args.environment_vars + common.get_deploy_script_env()
      withEnv(environment_vars){
        dir("/opt/rpc-openstack/openstack-ansible"){
          sh "git reset --hard"
        }
        common.prepareRpcGit()
        dir("/opt/rpc-openstack"){
          sh """
            scripts/test-upgrade.sh
          """
        } // dir
      } // withEnv
    } // stage
  ) // conditionalStage
}

def addChecksumRule(){
  sh """#!/bin/bash
    cd /opt/rpc-openstack/rpcd/playbooks
    ansible neutron_agent \
      -m command \
      -a '/sbin/iptables -t mangle -A POSTROUTING -p tcp --sport 80 -j CHECKSUM --checksum-fill'
    ansible neutron_agent \
      -m shell \
      -a 'DEBIAN_FRONTEND=noninteractive apt-get install iptables-persistent'
  """
}

return this;
