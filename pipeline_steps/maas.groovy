String get_mnaio_entity_names() {
  String entities = sh (
    script: """#!/usr/bin/env python
from ansible.parsing.dataloader import DataLoader
from ansible import __version__ as ansible_version
from distutils.version import LooseVersion
import json

loader = DataLoader()

if LooseVersion(ansible_version) < '2.4':
    from ansible.vars import VariableManager
    from ansible.inventory import Inventory
    variable_manager = VariableManager()
    inventory = Inventory(loader=loader, variable_manager=variable_manager, host_list='playbooks/inventory/')
    host_list = inventory.get_group('pxe_servers').hosts
else:
    from ansible.inventory.manager import InventoryManager
    inventory = InventoryManager(loader=loader, sources=['playbooks/inventory/'])
    host_list = inventory.get_hosts(pattern="pxe_servers")

print(
    json.dumps(
        ["%s.{{ server_name }}" % hostname for hostname in host_list]
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
          if (!("inventory" in args)){
            args.inventory = "inventory"
          }
          unstash(args.inventory)
          raxrc_cfg = common.writeRaxmonCfg(
            username: env.PUBCLOUD_USERNAME,
            api_key: env.PUBCLOUD_API_KEY
          )
          withEnv(["RAXMON_RAXRC=${raxrc_cfg}"]){
            common.venvPlaybook(
              playbooks: ['multi_node_aio_maas_entities.yml'],
              args: [
                "-i inventory",
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
            if (!("inventory" in args)){
              args.inventory = "inventory"
            }
            unstash(args.inventory)
            raxrc_cfg = common.writeRaxmonCfg(
              username: env.PUBCLOUD_USERNAME,
              api_key: env.PUBCLOUD_API_KEY
            )
            withEnv(["RAXMON_RAXRC=${raxrc_cfg}"]){
              common.venvPlaybook(
                playbooks: ['multi_node_aio_maas_cleanup.yml'],
                args: [
                  "-i inventory"
                ],
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
