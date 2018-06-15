def apt() {
  common.use_node('ArtifactBuilder2') {
    common.withRequestedCredentials("rpc_repo") {
      common.prepareRpcGit("auto", env.WORKSPACE)
      dir("${env.WORKSPACE}/rpc-openstack") {
        sh """#!/bin/bash
        scripts/artifacts-building/apt/build-apt-artifacts.sh
        """
      } // dir
    } // withCredentials
  } // use_node
}

def git(String image) {
  common.use_node(image) {
    try {
      common.withRequestedCredentials("rpc_repo") {
        common.prepareRpcGit()
        dir("/opt/rpc-openstack/") {
          sh """#!/bin/bash
          scripts/artifacts-building/git/build-git-artifacts.sh
          """
        } // dir
      } // withCredentials
    } catch (e) {
      print(e)
      throw e
    } finally {
      common.archive_artifacts()
    }
  } // use_node
}

def python(String image) {
  common.use_node(image) {
    try {
      common.withRequestedCredentials("rpc_repo") {
        common.prepareRpcGit()
        dir("/opt/rpc-openstack/") {
          sh """#!/bin/bash
          scripts/artifacts-building/python/build-python-artifacts.sh
          """
        } // dir
      } // withCredentials
    } catch (e) {
      print(e)
      throw e
    } finally {
      common.archive_artifacts()
    }
  } // use_node
}

return this
