void add_nodes() {
  sh """#!/usr/bin/env python
from itertools import chain
import json

def get_next_octet(start=1, end=255, used=None):
    if not used:
        used = set()

    for i in range(start, end):
        i = str(i)
        if i not in used:
            used.add(i)
            yield i

def add_nodes(config, base_name, number, octets):
    start = len(config) + 1
    end = start + number
    for i in range(start, end):
        name = "{}{}".format(base_name, i)
        config[name] = next(octets)

def add_additional_nodes(compute=0, volume=0):

    config = {
       "infra": {
            "infra1": "100",
            "infra2": "101",
            "infra3": "102"
       },
       "logging": {
           "logging1": "110"
       },
       "nova_compute": {
       },
       "cinder": {
       },
       "swift": {
           "swift1": "140",
           "swift2": "141",
           "swift3": "142"
       },
       "deploy": {
           "deploy1":"150"
       }
    }

    used = set(chain(*(v.values() for v in config.values())))
    octets = get_next_octet(start=100, end=200, used=used)

    add_nodes(
        config=config["nova_compute"],
        base_name="compute",
        number=compute,
        octets=octets
    )
    add_nodes(
        config=config["cinder"],
        base_name="cinder",
        number=volume,
        octets=octets
    )

    return config

if __name__ == "__main__":
    with open("hosts.json", "w") as f:
        f.write(
            json.dumps(
                add_additional_nodes(
                    compute=${env.COMPUTE_NODES}, volume=${env.VOLUME_NODES}
                ),
                indent=4,
                sort_keys=True,
            )
        )
"""
}

def prepare() {
  common.conditionalStage(
    stage_name: 'Prepare Multi-Node AIO',
    stage: {
      if (env.STAGES.contains("Leapfrog Upgrade")) {
        common.prepareRpcGit(env.UPGRADE_FROM_REF, "/opt")
      } else {
        common.prepareRpcGit("auto", "/opt")
      }
      String osa_commit = common.get_current_git_sha("/opt/rpc-openstack/openstack-ansible")
      dir("openstack-ansible-ops") {
        git url: env.OSA_OPS_REPO, branch: "master"
        sh "git checkout ${env.OSA_OPS_BRANCH}"
      }
      dir("openstack-ansible-ops/${env.MULTI_NODE_AIO_DIR}") {
        timeout(time: 45, unit: "MINUTES") {
          add_nodes()
          env.MNAIO_ENTITIES = maas.get_mnaio_entity_names()
          common.run_script(
            script: 'build.sh',
            environment_vars: [
              "PARTITION_HOST=${env.PARTITION_HOST}",
              "NETWORK_BASE=172.29",
              "DNS_NAMESERVER=8.8.8.8",
              "OVERRIDE_SOURCES=true",
              "DEVICE_NAME=vda",
              "DEFAULT_NETWORK=eth0",
              "VM_DISK_SIZE=252",
              "DEFAULT_IMAGE=${env.DEFAULT_IMAGE}",
              "DEFAULT_KERNEL=${env.DEFAULT_KERNEL}",
              "OSA_BRANCH=${osa_commit}",
              "SETUP_HOST=true",
              "SETUP_VIRSH_NET=true",
              "VM_IMAGE_CREATE=true",
              "DEPLOY_OSA=true",
              "PRE_CONFIG_OSA=true",
              "RUN_OSA=false",
              "DATA_DISK_DEVICE=${env.DATA_DISK_DEVICE}",
              "CONFIG_PREROUTING=true",
              "OSA_PORTS=6080 6082 443 80 8443",
              ]
          ) //run_script
        } //timeout
      } // dir
    } //stage
  ) //conditionalStage
}

def prepare_configs(){
  common.conditionalStage(
    stage_name: 'Prepare RPC Configs',
    stage: {
      common.prepareConfigs(
        deployment_type: "onmetal"
      )
      sh """/bin/bash
      echo "multi_node_aio_prepare.prepare/Prepare RPC Configs"
      set -xe
      sudo cp /etc/openstack_deploy/user_variables.yml /etc/openstack_deploy/user_variables.yml.bak
      sudo cp -R /opt/rpc-openstack/openstack-ansible/etc/openstack_deploy /etc
      sudo cp /etc/openstack_deploy/user_variables.yml.bak /etc/openstack_deploy/user_variables.yml

      sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/user_*.yml /etc/openstack_deploy
      sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/env.d/* /etc/openstack_deploy/env.d
      """
    } //stage
  ) //conditionalStage
}


def connect_deploy_node(name, instance_ip) {
  String inventory_content = """
  [job_nodes:children]
  hosts
  [hosts]
  ${name} ansible_port=2222 ansible_host=${instance_ip}
  """
  common.drop_inventory_file(inventory_content)
  dir("rpc-gating/playbooks"){
    stash (
      name: "inventory",
      include: "inventory/hosts"
    )
  }
  ssh_slave.connect(port: 2222)
}

return this;
