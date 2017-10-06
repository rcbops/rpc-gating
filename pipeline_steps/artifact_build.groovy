def get_rpc_repo_creds(){
  return [
    string(
      credentialsId: "RPC_REPO_IP",
      variable: "REPO_HOST"
    ),
    string(
      credentialsId: "RPC_REPO_SSH_USERNAME_TEXT",
      variable: "REPO_USER"
    ),
    file(
      credentialsId: "RPC_REPO_SSH_USER_PRIVATE_KEY_FILE",
      variable: "REPO_USER_KEY"
    ),
    file(
      credentialsId: "RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE",
      variable: "REPO_HOST_PUBKEY"
    ),
    file(
      credentialsId: "RPC_REPO_GPG_SECRET_KEY_FILE",
      variable: "GPG_PRIVATE"
    ),
    file(
      credentialsId: "RPC_REPO_GPG_PUBLIC_KEY_FILE",
      variable: "GPG_PUBLIC"
    )
  ]
}

def apt() {
  common.use_node('ArtifactBuilder2') {
    withCredentials(get_rpc_repo_creds()) {
      common.prepareRpcGit()
      ansiColor('xterm') {
        dir("/opt/rpc-openstack/") {
          sh """#!/bin/bash
          scripts/artifacts-building/apt/build-apt-artifacts.sh
          """
        } // dir
      } // ansiColor
    } // withCredentials
  } // use_node
}

def git(String image) {
  pubcloud.runonpubcloud(image: image) {
    try {
      withCredentials(get_rpc_repo_creds()) {
        common.prepareRpcGit()
        ansiColor('xterm') {
          dir("/opt/rpc-openstack/") {
            sh """#!/bin/bash
            scripts/artifacts-building/git/build-git-artifacts.sh
            """
          } // dir
        } // ansiColor
      } // withCredentials
    } catch (e) {
      print(e)
      throw e
    } finally {
      common.rpco_archive_artifacts()
    }
  } // pubcloud slave
}

def python(String image) {
  pubcloud.runonpubcloud(image: image) {
    try {
      withCredentials(get_rpc_repo_creds()) {
        common.prepareRpcGit()
        ansiColor('xterm') {
          dir("/opt/rpc-openstack/") {
            sh """#!/bin/bash
            scripts/artifacts-building/python/build-python-artifacts.sh
            """
          } // dir
        } // ansiColor
      } // withCredentials
    } catch (e) {
      print(e)
      throw e
    } finally {
      common.rpco_archive_artifacts()
    }
  } // pubcloud slave
}

def container(String image) {
  pubcloud.runonpubcloud(image: image) {
    try {
      withCredentials(get_rpc_repo_creds()) {
        common.prepareRpcGit()
        ansiColor('xterm') {
          dir("/opt/rpc-openstack/") {
            sh """#!/bin/bash
            scripts/artifacts-building/containers/build-process.sh
            """
          } // dir
        } // ansiColor
      } // withCredentials
    } catch (e) {
      print(e)
      throw e
    } finally {
      common.rpco_archive_artifacts()
    }
  } // pubcloud slave
}

def cloudimage(Map args) {
  inventory="inventory.${common.rand_int_str()}"
  inventory_path="${WORKSPACE}/rpc-gating/playbooks/${inventory}"
  String instance_name = common.gen_instance_name()
  try {
    pubcloud.getPubCloudSlave(
      image: args.src_image,
      instance_name: instance_name,
      inventory: inventory,
      inventory_path: inventory_path,
      region: args.region
    )
    pubcloud.savePubCloudSlave(
      image: args.dest_image,
      instance_name: instance_name,
      inventory: inventory,
      inventory_path: inventory_path,
      region: args.region
    )
  } catch (e) {
    print(e)
    throw e
  } finally {
    pubcloud.delPubCloudSlave(
      instance_name: instance_name,
      inventory: inventory,
      inventory_path: inventory_path,
      region: args.region
    )
  }
}

return this
