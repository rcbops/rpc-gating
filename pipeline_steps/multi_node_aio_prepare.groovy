def prepare() {
  common.conditionalStage(
    stage_name: 'Prepare Multi-Node AIO',
    stage: {
      if (env.STAGES.contains("Leapfrog Upgrade")) {
        common.prepareRpcGit(env.UPGRADE_FROM_REF, "/opt")
      } else {
        common.prepareRpcGit("auto", "/opt")
      }
      dir("/opt/rpc-openstack"){
        osa_commit = sh (script: """#!/bin/bash -x
            git_submodule=\$(git submodule status openstack-ansible)
            if [[ \$? == 0 ]]; then
              echo \$git_submodule | egrep --only-matching '[a-f0-9]{40}'
            else
              functions_sha=\$(python -c 'import re; f = open("scripts/functions.sh"); print(re.search("OSA_RELEASE:-\\"(.+)\\"}", f.read()).group(1))')
              if [[ \$? == 0 ]]; then
                echo \$functions_sha
              else
                echo "Unable to determine openstack-ansible SHA"
                exit 1
              fi
            fi
            """, returnStdout: true)
        print("Current SHA for openstack-ansible is '${osa_commit}'.")
      }
      dir("openstack-ansible-ops") {
        git url: env.OSA_OPS_REPO, branch: "master"
        sh "git checkout ${env.OSA_OPS_BRANCH}"
      }
      dir("openstack-ansible-ops/multi-node-aio") {
        sh """#!/bin/bash
          # The multi-node-aio tool is quite modest when it comes to allocating
          # RAM to VMs -- since we have RAM to spare we double that assigned to
          # infra nodes.
          echo "infra_vm_server_ram: 16384" | sudo tee -a playbooks/group_vars/all.yml
          cp -a ${WORKSPACE}/rpc-gating/scripts/dynamic_inventory.py playbooks/inventory
        """
        timeout(time: 45, unit: "MINUTES") {
          common.run_script(
            script: 'build.sh',
            environment_vars: [
              "PARTITION_HOST=${env.PARTITION_HOST}",
              "NETWORK_BASE=172.29",
              "DNS_NAMESERVER=8.8.8.8",
              "OVERRIDE_SOURCES=true",
              "DEVICE_NAME=vda",
              "DEFAULT_NETWORK=eth0",
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
              "ADDITIONAL_COMPUTE_NODES=${env.ADDITIONAL_COMPUTE_NODES}",
              "ADDITIONAL_VOLUME_NODES=${env.ADDITIONAL_VOLUME_NODES}",
              ]
          ) //run_script
        } //timeout
        env.MNAIO_ENTITIES = maas.get_mnaio_entity_names()
        sh "scp -o StrictHostKeyChecking=no /root/.ssh/authorized_keys infra1:/root/.ssh"
      } // dir
    } //stage
  ) //conditionalStage

  common.conditionalStage(
    stage_name: 'Prepare RPC Configs',
    stage: {
      common.prepareConfigs(
        deployment_type: "onmetal"
      )
      sh """/bin/bash
      echo "multi_node_aio_prepare.prepare/Prepare RPC Configs"
      set -xe
      scp -r -o StrictHostKeyChecking=no /opt/rpc-openstack infra1:/opt/
      scp -o StrictHostKeyChecking=no ${env.WORKSPACE}/user_zzz_gating_variables.yml infra1:/etc/openstack_deploy/user_zzz_gating_variables.yml

      ssh -T -o StrictHostKeyChecking=no infra1 << 'EOF'
      set -xe
      sudo cp /etc/openstack_deploy/user_variables.yml /etc/openstack_deploy/user_variables.yml.bak
      sudo cp -R /opt/rpc-openstack/openstack-ansible/etc/openstack_deploy /etc
      sudo cp /etc/openstack_deploy/user_variables.yml.bak /etc/openstack_deploy/user_variables.yml

      sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/user_*.yml /etc/openstack_deploy
      sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/env.d/* /etc/openstack_deploy/env.d
EOF
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
