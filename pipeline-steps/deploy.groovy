def deploy(){
  common.runStep(
    step_name: "Deploy",
    conditional: True,
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
  ) //runStep
}

def deploy_sh(Map args) {
  common.runStage(
    stage_name: "Deploy RPC w/ Script",
    conditional: True,
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
  ) // runStage
}

def upgrade(Map args) {
  common.runStage(
    stage_name: "Upgrade",
    conditional: True,
    stage: {
      environment_vars = args.environment_vars + common.get_deploy_script_env()
      withEnv(environment_vars){
        dir("/opt/rpc-openstack/openstack-ansible"){
          sh "git reset --hard"
        }
        dir("/opt/rpc-openstack"){
          git branch: env.RPC_BRANCH, url: env.RPC_REPO
          sh """
            env
            git submodule update --init
            scripts/test-upgrade.sh
          """
        } // dir
      } // withEnv
    } // stage
  ) // runStage
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
