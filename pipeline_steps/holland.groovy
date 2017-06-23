def holland(vm=null){
  common.conditionalStage(
    stage_name: "Holland",
    stage: {
      common.openstack_ansible(
        vm: vm,
        playbook: "/opt/rpc-openstack/scripts/test_holland.yml",
        path: "/opt/rpc-openstack/rpcd/playbooks"
      )
    } //stage
  ) //conditionalStage
}

return this
