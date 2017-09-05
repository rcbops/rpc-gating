String get_mnaio_entity_names() {
  String entities = sh (
    script: """#!/usr/bin/env python
from ansible.parsing.dataloader import DataLoader
from ansible.vars import VariableManager
from ansible.inventory import Inventory
import json

variable_manager = VariableManager()
loader = DataLoader()
inventory = Inventory(loader=loader, variable_manager=variable_manager, host_list='playbooks/inventory/')

print(
    json.dumps(
        ["%s.{{ server_name }}" % hostname for hostname in inventory.get_group('pxe_servers').hosts]
    )
)
    """,
    returnStdout: true,
  )
  return entities
}

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
          withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
            common.venvPlaybook(
              playbooks: ['multi_node_aio_maas_entities.yml'],
              args: [
                "-e server_name=\"${args.instance_name}\"",
                "-e '{\"entity_labels\": ${env.MNAIO_ENTITIES}}'",
              ],
              vars: args
            )
          } // withEnv
        } // dir
      } // withCredentials
    } // stage
  ) // conditionalStage
}

// Add MaaS vars as properties of the env object
// This is similar to withEnv but doesn't require
// another level of nesting.
void add_maas_env_vars(){
  List vars = get_maas_token_and_url()
  for (def i=0; i<vars.size(); i++){
    List kv = vars[i].split('=')
    env[kv[0]] = kv[1]
  }
}

List get_maas_token_and_url() {
  return maas_utils(['get_token_url']).trim().tokenize(" ")
}

def maas_utils(List args){
  withCredentials(common.get_cloud_creds()) {
    return sh (
      script: """#!/bin/bash
        cd ${env.WORKSPACE}/rpc-gating/scripts
        set +x; . ${env.WORKSPACE}/.venv/bin/activate; set -x
        ./maasutils.py --username ${env.PUBCLOUD_USERNAME} --api-key ${env.PUBCLOUD_API_KEY} ${args.join(" ")}
      """,
      returnStdout: true
    )
  }
}

def entity_cleanup(Map args){
  common.conditionalStep(
    step_name: 'Cleanup',
    step: {
      if (env.MNAIO_ENTITIES) {
        withCredentials(common.get_cloud_creds()) {
          dir("rpc-gating/playbooks") {
            pyrax_cfg = common.writePyraxCfg(
              username: env.PUBCLOUD_USERNAME,
              api_key: env.PUBCLOUD_API_KEY
            )
            withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]) {
              common.venvPlaybook(
                playbooks: ['multi_node_aio_maas_cleanup.yml'],
                vars: [
                  "server_name": args.instance_name,
                  "entity_labels": env.MNAIO_ENTITIES,
                ]
              )
            } // withEnv
          } // directory
        } //withCredentials
      }
    } // step
  ) // conditionalStep
} //call

return this;
