def lifecycle_integration(){
  common.conditionalStage(
    stage_name: "Prepare Lifecycle",
    stage: {
      if (env.UPGRADE_FROM_REF.contains("kilo") || env.UPGRADE_FROM_REF.contains("r11.")) {
        kilo_prepare()
      }
      if (env.UPGRADE_FROM_REF.contains("liberty") || env.UPGRADE_FROM_REF.contains("r12.")) {
        liberty_prepare()
      }
      if (env.UPGRADE_FROM_REF.contains("mitaka") || env.UPGRADE_FROM_REF.contains("r13.")) {
        mitaka_prepare()
      }
    }
  )
}

def kilo_prepare() {
  dir("/opt/rpc-openstack") {
    sh """#!/bin/bash
    sed -i 's/mattwillsher.sshd/willshersystems.sshd/' openstack-ansible/ansible-role-requirements.yml
    sed -i 's/rabbit_mq_container: 3/rabbit_mq_container: 1/' openstack-ansible/etc/openstack_deploy/openstack_user_config.yml.aio
    pip install --force-reinstall pyopenssl
    pip uninstall paramiko
    pip install paramiko==1.15.2
    """
  }
}

def liberty_prepare() {

}

def mitaka_prepare() {

}

return this;
