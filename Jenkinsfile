properties([
  parameters([
    string(name: 'REGION',
           defaultValue: 'IAD'),
    string(name: 'FLAVOR',
           defaultValue: '2'),
    string(name: 'IMAGE',
           defaultValue: 'Ubuntu 16.04 LTS (Xenial Xerus) (PVHVM)'),
    string(name: 'STAGES',
           defaultValue: 'Allocate Resources, Connect Slave, Cleanup'),
    string(name: 'INSTANCE_NAME_RAW',
           defaultValue: 'AUTO'),
    string(name: 'RPC_GATING_REPO',
           defaultValue: 'https://github.com/rcbops/rpc-gating'),
    string(name: 'RPC_GATING_BRANCH',
           defaultValue: 'bug/1080_globallib')
  ]) //parameters
]) //properties

node(){
  deleteDir()
  checkout scm
  sh "env"
  pubcloud.runonpubcloud(step: {
    stage("Prepare"){
      sh """#!/bin/bash
        apt-get update
        apt-get -y install groovy2
        pip install jenkins-job-builder
      """
    }
    stage("checkout"){
      checkout scm
    }
    stage("lint"){
      sh "./lint.sh"
    }
  })
}
