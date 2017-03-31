def prepare() {
  dir("openstack-ansible-ops") {
    git url: env.OSA_OPS_REPO, branch: env.OSA_OPS_BRANCH
  }
  dir("openstack-ansible-ops/multi-node-aio") {
    common.runStage(
      stage_name: 'Prepare Multi-Node AIO',
      conditional: True,
      stage: {
        timeout(time: 45, unit: "MINUTES"){
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
              "RUN_OSA=false"]
          ) //run_script
        } //timeout
      } //stage
    ) //runStage
  } //dir
  common.runStage(
    stage_name: 'Prepare RPC Configs',
    conditional: True,
    stage: {
      dir("/opt/rpc-openstack") {
        git branch: env.RPC_BRANCH, url: env.RPC_REPO
        sh """
        git submodule update --init

        sudo cp /etc/openstack_deploy/user_variables.yml /etc/openstack_deploy/user_variables.yml.bak
        sudo cp -R /opt/rpc-openstack/openstack-ansible/etc/openstack_deploy /etc
        sudo cp /etc/openstack_deploy/user_variables.yml.bak /etc/openstack_deploy/user_variables.yml

        sudo mv /etc/openstack_deploy/user_secrets.yml /etc/openstack_deploy/user_osa_secrets.yml
        sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/user_*_defaults.yml /etc/openstack_deploy
        sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/user_rpco_secrets.yml /etc/openstack_deploy
        sudo cp /opt/rpc-openstack/rpcd/etc/openstack_deploy/env.d/* /etc/openstack_deploy/env.d
        """
      } //dir
      common.prepareConfigs(
        deployment_type: "onmetal"
      )
    } //stage
  ) //runStage
}

return this;
