String get_mnaio_hostnames() {
  dir("openstack-ansible-ops/${env.MULTI_NODE_AIO_DIR}") {
    entities = sh (
      script: """#!/usr/bin/env python
from __future__ import print_function
from itertools import chain
import json

with open("hosts.json") as f:
    data = f.read()

hostnames = ["loadbalancer1"]
hostnames.extend(
    chain(*(node_type.keys() for node_type in json.loads(data).values()))
)
print(
    json.dumps(
        ["%s.{{ server_name }}" % hostname for hostname in hostnames]
    )
)
      """,
      returnStdout: true,
    )
  }
  return entities
}

def prepare(Map args) {
  common.conditionalStage(
    stage_name: 'Prepare MaaS',
    stage: {
      entities = get_mnaio_hostnames()
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
                "-e server_name=\"${args.instance_name}\"",
                "-e '{\"entity_labels\": ${entities}}'",
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

List get_maas_token_and_url() {
  return maas_utils(['get_token_url']).trim().tokenize(" ")
}

def maas_utils(List args){
  withCredentials(common.get_cloud_creds()) {
    return sh (
      script: """#!/bin/bash
        cd ${env.WORKSPACE}/rpc-gating/scripts
        . ${env.WORKSPACE}/.venv/bin/activate
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
      entities = get_mnaio_hostnames()
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
                "entity_labels": entities,
              ]
            )
          } // withEnv
        } // directory
      } //withCredentials
    } // step
  ) // conditionalStep
} //call

return this;
