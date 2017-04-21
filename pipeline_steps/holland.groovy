def holland(){
  common.conditionalStage(
    stage_name: "Holland",
    stage: {
      //TODO: move test_holland into RPC to avoid this mess.
      dir("rpc-gating"){
        // only needed to pull the test_holland from the repo.
        git branch: env.RPC_GATING_BRANCH, url: env.RPC_GATING_REPO
      }
      / * recent versions of openstack-ansible allow execution of playbooks
        * from any directory, but that doesn't work all the way back to liberty
        */
      sh """cp rpc-gating/playbooks/test_holland.yml /opt/rpc-openstack/rpcd/playbooks"""
      common.openstack_ansible(
        playbook: "test_holland.yml",
        path: "/opt/rpc-openstack/rpcd/playbooks"
      )
    } //stage
  ) //conditionalStage
}

return this
