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
              playbooks: ['aio_maas_entities.yml'],
              venv: ".venv",
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

def deploy() {
  common.conditionalStage(
    stage_name: 'Setup MaaS',
    stage: {
      common.openstack_ansible(
        path: '/opt/rpc-openstack/rpcd/playbooks',
        playbook: 'setup-maas.yml'
      ) //openstack_ansible
    } //stage
  ) //conditionalStage
}

def verify() {
  common.conditionalStage(
    stage_name: 'Verify MaaS',
    stage: {
      common.openstack_ansible(
        path: '/opt/rpc-openstack/rpcd/playbooks',
        playbook: 'verify-maas.yml'
      ) //openstack_ansible
    } //stage
  ) //conditionalStage
}

List get_maas_token_and_url(String username, String api_key, String region, String venv) {
  def token_url = sh (
    script: """#!/bin/bash
cd ${env.WORKSPACE}/rpc-gating/scripts
. ${venv}/bin/activate
./get_maas_token_and_url.py --username ${username} --api-key ${api_key} --region ${region}
""",
    returnStdout: true,
  )
  return token_url.trim().tokenize(" ")
}

return this;
