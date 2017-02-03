def prepare() {
  dir("openstack-ansible-ops") {
    git url: env.OSA_OPS_REPO, branch: env.OSA_OPS_BRANCH
  }
  dir("openstack-ansible-ops/multi-node-aio") {
    common.conditionalStage(
      stage_name: 'Setup Host',
      stage: {
        common.run_script(
          script: 'setup-host.sh',
          environment_vars: ["PARTITION_HOST=${env.PARTITION_HOST}"]
        )
      } //stage
    ) //conditionalStage

    common.conditionalStage(
      stage_name: 'Setup Cobbler',
      stage: {
        common.run_script(
          script: 'setup-cobbler.sh',
          environment_vars: ["DEFAULT_IMAGE=${env.DEFAULT_IMAGE}"]
        )
      } //stage
    ) //conditionalStage

    common.conditionalStage(
      stage_name: 'Setup Virtual Networks',
      stage: {
        common.run_script(
          script: 'setup-virsh-net.sh',
          environment_vars: []
        )
      } //stage
    ) //conditionalStage

    common.conditionalStage(
      stage_name: 'Deploy VMs',
      stage: {
        common.run_script(
          script: 'deploy-vms.sh',
          environment_vars: []
        )
      } //stage
    ) //conditionalStage

    common.conditionalStage(
      stage_name: 'Setup OpenStack Ansible',
      stage: {
        common.run_script(
          script: 'deploy-osa.sh',
          environment_vars: [
            "OSA_BRANCH=${env.OPENSTACK_ANSIBLE_BRANCH}",
            "RUN_OSA=false"]
        )
      } //stage
    ) //conditionalStage
  } //dir
  common.conditionalStage(
    stage_name: 'Prepare Configs',
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

        sudo -E sh -c 'echo "
        apply_security_hardening: false" >> /etc/openstack_deploy/user_osa_variables_overrides.yml'
        """
      } //dir
    } //stage
  ) //conditionalStage
}
return this;
