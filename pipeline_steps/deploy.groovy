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
          if (!('vm' in args)){
            dir("/opt/rpc-openstack/") {
              sh """#!/bin/bash
              scripts/deploy.sh
              """
            } // dir
          } else {
            export_vars = ""
            for ( e in environment_vars ) {
              export_vars += "export ${e}; "
            }
            sh """#!/bin/bash
            sudo ssh -T -oStrictHostKeyChecking=no ${args.vm} \
              '${export_vars} cd /opt/rpc-openstack; FORKS=5; scripts/deploy.sh'
            """
          } // if vm
        } // ansiColor
      } // withEnv
    } // stage
  ) // conditionalStage
}

def upgrade(String stage_name, String upgrade_script, List env_vars, String vm = '') {
  common.conditionalStage(
    stage_name: stage_name,
    stage: {
      environment_vars = env_vars + common.get_deploy_script_env()
      withEnv(environment_vars){
        dir("/opt/rpc-openstack/openstack-ansible"){
          sh "git reset --hard"
        }
        common.prepareRpcGit()
        if (vm.empty){
          dir("/opt/rpc-openstack"){
            sh """
              scripts/$upgrade_script
            """
          } // dir
        } else {
            export_vars = ""
            for ( e in environment_vars ) {
              export_vars += "export ${e}; "
            }
            sh """#!/bin/bash
            sudo ssh -T -oStrictHostKeyChecking=no ${vm} \
              '${export_vars} cd /opt/rpc-openstack; scripts/$upgrade_script'
            """
          } // if vm
      } // withEnv
    } // stage
  ) // conditionalStage
}

def upgrade_minor(Map args) {
  upgrade("Minor Upgrade", "deploy.sh", args.environment_vars)
}

def upgrade_major(Map args) {
  upgrade("Major Upgrade", "test-upgrade.sh", args.environment_vars)
}

def upgrade_leapfrog(Map args) {
  if (!('vm' in args)){
    upgrade("Leapfrog Upgrade", "leapfrog/ubuntu14-leapfrog.sh", args.environment_vars)
  } else {
    upgrade("Leapfrog Upgrade", "leapfrog/ubuntu14-leapfrog.sh", args.environment_vars, args.vm)
  }
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
