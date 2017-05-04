def prepare() {
  common.conditionalStage(
    stage_name: 'Prepare Multi-Node AIO',
    stage: {
      dir("openstack-ansible-ops") {
        git url: env.OSA_OPS_REPO, branch: env.OSA_OPS_BRANCH
      }
      dir("openstack-ansible-ops/${env.MULTI_NODE_AIO_DIR}") {
        timeout(time: 45, unit: "MINUTES") {
          sh """
          sed -i "s,<memory unit='GiB'>1,<memory unit='GiB'>8," templates/vmnode-config/deploy.openstackci.local.xml
          sed -i "s,<currentMemory unit='GiB'>1,<currentMemory unit='GiB'>8," templates/vmnode-config/deploy.openstackci.local.xml
          sed -i "s,<vcpu placement='static'>1,<vcpu placement='static'>10," templates/vmnode-config/deploy.openstackci.local.xml
          """
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
              "OSA_BRANCH=${env.OPENSTACK_ANSIBLE_BRANCH}",
              "SETUP_HOST=true",
              "SETUP_VIRSH_NET=true",
              "VM_IMAGE_CREATE=true",
              "DEPLOY_OSA=true",
              "PRE_CONFIG_OSA=true",
              "RUN_OSA=false",
              "DATA_DISK_DEVICE=${env.DATA_DISK_DEVICE}",
              "CONFIG_PREROUTING=false"]
          ) //run_script
        } //timeout
      } // dir
    } //stage
  ) //conditionalStage

  common.conditionalStage(
    stage_name: 'Prepare RPC Configs',
    stage: {
      common.prepareRpcGit(env.RPC_BRANCH)
      common.prepareConfigs(
        deployment_type: "onmetal"
      )
      sh """/bin/bash
      echo "multi_node_aio_prepare.prepare/Prepare RPC Configs"
      set -xe
      scp -r -o StrictHostKeyChecking=no /opt/rpc-openstack deploy1:/opt/
      scp -o StrictHostKeyChecking=no ${env.WORKSPACE}/user_zzz_gating_variables.yml deploy1:/etc/openstack_deploy/user_zzz_gating_variables.yml

      ssh -T -o StrictHostKeyChecking=no deploy1 << 'EOF'
      set -xe
      sudo cp /etc/openstack_deploy/user_variables.yml /etc/openstack_deploy/user_variables.yml.bak
      sudo cp -R /opt/rpc-openstack/openstack-ansible/etc/openstack_deploy /etc
      sudo cp /etc/openstack_deploy/user_variables.yml.bak /etc/openstack_deploy/user_variables.yml

      sudo mv /etc/openstack_deploy/user_secrets.yml /etc/openstack_deploy/user_osa_secrets.yml
      sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/user_*_defaults.yml /etc/openstack_deploy
      sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/user_rpco_secrets.yml /etc/openstack_deploy
      sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/env.d/* /etc/openstack_deploy/env.d
EOF
      """
    } //stage
  ) //conditionalStage
}

/*
 * Post-deploy networking configuration
 */
def multi_node_aio_networking(){
  common.conditionalStep(
    step_name: "Deploy RPC w/ Script",
    step: {
      dir("openstack-ansible-ops") {
        git url: env.OSA_OPS_REPO, branch: env.OSA_OPS_BRANCH
      }
      dir("openstack-ansible-ops/${env.MULTI_NODE_AIO_DIR}") {
        common.run_script(
          script: 'config-deploy-node.sh',
          environment_vars: [
            "DEPLOY_OSA=false",
            "OSA_PORTS=6080 6082 443 80 8443",
            "CONFIG_PREROUTING=true"]
        )
      }
    }
  )
}

return this;
