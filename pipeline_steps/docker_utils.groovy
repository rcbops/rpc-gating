import com.rackspace.exceptions.REException

/**
 * Run preparatory steps to allow host to use internal Docker registry.
 */
void prepareHost(){
  common.connect_kronos_vpn()
  withCredentials(
    [
      file(
        credentialsId: 'rackspace_ca_crt',
        variable: 'RS_CA_CERT'
      ),
      string(
        credentialsId: 'kronos_docker_registry_domain_name',
        variable: 'REGISTRY_DOMAIN_NAME'
      )
    ]
  ){
    sh(
      """#!/bin/bash
        if ! which docker ; then
          mkdir -p /etc/docker/certs.d/\${REGISTRY_DOMAIN_NAME}
          cp "\$RS_CA_CERT" /etc/docker/certs.d/\${REGISTRY_DOMAIN_NAME}
          apt-get update
          apt-get install -y docker.io
        fi
      """
    )
  }
}

/**
 * Generate a Docker image name for use with the internal registry.
 *
 * nameSep: old versions of Docker do not allow "/" in the name.
 */
String toInternalRegistryName(
    String name,
    String tag=env.BUILD_TAG,
    String registryProject="re",
    String component=env.RE_JOB_REPO_NAME,
    String nameSep="/"
  ){
  validateExist = [
    [name, "'name' must be set.'"],
    [registryProject, "'registryProject' must be set, it defaults to 're'."],
    [component, "'component' must be set, defaults to env.RE_JOB_REPO_NAME."],
    [nameSep, "'nameSep' must be set, defaults to '/'."]
  ]
  for (toValidate in validateExist){
    value = toValidate[0]
    errorMsg = toValidate[1]
    if (! value){
      throw new REException(errorMsg)
    }
  }
  withCredentials(
    [
      string(
        credentialsId: 'kronos_docker_registry_domain_name',
        variable: 'registryDomainName'
      )
    ]
  ){
    String qualifiedName
    if (name.startsWith(registryDomainName)){
      qualifiedName = name
    } else{
      qualifiedName = "${registryDomainName}/${registryProject}/${component}${nameSep}${name}"
    }

    if (tag){
      qualifiedName = qualifiedName + ":${tag}"
    } else if (! name.contains(":")){
      throw new REException("A tag is required.")
    }

    return qualifiedName
  }
}

/**
 * Helper function wrapping docker.withRegistry.
 */
void withInternalRegistry(Closure body, Integer tries=1){
  prepareHost()
  withCredentials(
    [
      string(
        credentialsId: 'kronos_docker_registry_url',
        variable: 'registryURL'
      )
    ]
  ){
    Integer currentTry = 0
    while (tries > 0){
      tries -= 1
      currentTry += 1
      try{
        docker.withRegistry(registryURL, "kronos_mk8s_jenkins_account"){
          body()
        }
      } catch (e){
        println "withInternalRegistry try ${currentTry}: the following exception has been caught:\n${e}"
        if (tries == 0){
          throw new REException(
            "withInternalRegistry Failure: Retries exhausted, review logs above for the cause of each failure."
          )
        } else if (common.isKronosVPNConnected()){
          println "Kronos VPN is up."
        } else{
          println "Kronos VPN connection appears down."
          try{
            println "Checking Kronos VPN status."
            sh "service vpnc.kronos status"
          } catch (ee){
          }
          println "Restarting Kronos VPN."
          sh "service vpnc.kronos restart"
          sleep 5
        }
      }
    }
  }
}

/**
 * Download the image from internal registry when it exists or build from Dockerfile when absent.
 *
 * Dockerfile is assumed to be in the current directory.
 */
def pullOrBuild(String name, String tag=env.BUILD_TAG, Boolean uploadOnBuild=true){
  String imageName = toInternalRegistryName(name, tag)
  def image
  closure = {
    try {
      image = docker.image(imageName)
      image.pull()
    } catch (e) {
      println e
      image = docker.build imageName
      if (uploadOnBuild){
        timeout(time: 10, unit: 'MINUTES'){
          image.push()
        }
      }
    }
  }
  withInternalRegistry(closure, 3)
  return image
}

return this;
