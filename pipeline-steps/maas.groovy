def prepare(Map args) {
  common.runStage(
    stage_name: 'Prepare MaaS',
    conditional: True,
    stage: {
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
        dir("rpc-gating/playbooks"){
          pyrax_cfg = common.writePyraxCfg(
            username: env.PUBCLOUD_USERNAME,
            api_key: env.PUBCLOUD_API_KEY
          )
          common.install_ansible()
          withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
            common.venvPlaybook(
              playbooks: ['aio-maas-entities.yml'],
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
  ) // runStage
}

def deploy() {
  common.runStage(
    stage_name: 'Setup MaaS',
    conditional: True,
    stage: {
      common.openstack_ansible(
        path: '/opt/rpc-openstack/rpcd/playbooks',
        playbook: 'setup-maas.yml'
      ) //openstack_ansible
    } //stage
  ) //runStage
}

def verify() {
  common.runStage(
    stage_name: 'Verify MaaS',
    conditional: True,
    stage: {
      common.openstack_ansible(
        path: '/opt/rpc-openstack/rpcd/playbooks',
        playbook: 'verify-maas.yml'
      ) //openstack_ansible
    } //stage
  ) //runStage
}

return this;
