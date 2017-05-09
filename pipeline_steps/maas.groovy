def prepare(Map args) {
  common.conditionalStage(
    stage_name: 'Prepare MaaS',
    stage: {
      withCredentials(common.get_cloud_creds()){
        dir("rpc-gating/playbooks"){
          pyrax_cfg = common.writePyraxCfg(
            username: env.PUBCLOUD_USERNAME,
            api_key: env.PUBCLOUD_API_KEY
          )
          common.install_ansible()
          withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
            common.venvPlaybook(
              playbooks: ['multi_node_aio_maas_entities.yml'],
              args: [
                "-e server_name=\"${args.instance_name}\""
              ],
              vars: args
            )
          } // withEnv
        } // dir
      } // withCredentials
    } // stage
  ) // conditionalStage
}

def deploy(vm=null) {
  common.conditionalStage(
    stage_name: 'Setup MaaS',
    stage: {
      common.openstack_ansible(
        vm: vm,
        path: '/opt/rpc-openstack/rpcd/playbooks',
        playbook: 'setup-maas.yml'
      ) //openstack_ansible
    } //stage
  ) //conditionalStage
}

def verify(vm=null) {
  common.conditionalStage(
    stage_name: 'Verify MaaS',
    stage: {
      common.openstack_ansible(
        vm: vm,
        path: '/opt/rpc-openstack/rpcd/playbooks',
        playbook: 'verify-maas.yml'
      ) //openstack_ansible
    } //stage
  ) //conditionalStage
}

List get_maas_token_and_url(String username, String api_key, String region) {
  def token_url = sh (
    script: """#!/bin/bash
cd ${env.WORKSPACE}/rpc-gating/scripts
. ${env.WORKSPACE}/.venv/bin/activate
./get_maas_token_and_url.py --username ${username} --api-key ${api_key} --region ${region}
""",
    returnStdout: true,
  )
  return token_url.trim().tokenize(" ")
}

def entity_cleanup(Map args){
  common.conditionalStep(
    step_name: 'Cleanup',
    step: {
      withCredentials(common.get_cloud_creds()) {
        dir("rpc-gating/playbooks") {
          common.install_ansible()
          pyrax_cfg = common.writePyraxCfg(
            username: env.PUBCLOUD_USERNAME,
            api_key: env.PUBCLOUD_API_KEY
          )
          withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]) {
            common.venvPlaybook(
              playbooks: ['multi_node_aio_maas_cleanup.yml'],
              vars: [
                "server_name": args.instance_name,
              ]
            )
          } // withEnv
        } // directory
      } //withCredentials
    } // step
  ) // conditionalStep
} //call

return this;
