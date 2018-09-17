import org.apache.commons.httpclient.*
import org.apache.commons.httpclient.auth.*
import org.apache.commons.httpclient.methods.*

// see https://wiki.jenkins.io/display/JENKINS/Authenticating+scripted+clients

Map parseJenkinsUrl(url="fromEnv"){
    if(url == "fromEnv"){
        url = env.JENKINS_URL
    }
    List urlParts = url.split(/[:\/]+/)
    Map data = [
        'protocol': urlParts[0],
        'server': urlParts[1],
        'url': url
    ]
    return data
}

@Grab(group='commons-httpclient', module='commons-httpclient', version='3.1')
HttpClient getAuthedClient(){
  Map url = parseJenkinsUrl()
  String username
  String apiToken
  common.withRequestedCredentials("jenkins_api_creds"){
      username = env.JENKINS_USERNAME
      apiToken = env.JENKINS_API_KEY
  }

  HttpClient client = new HttpClient()
  client.state.setCredentials(
    new AuthScope(url['server'], AuthScope.ANY_PORT, AuthScope.ANY_REALM),
    new UsernamePasswordCredentials( username, apiToken )
  )

  // Jenkins does not do any authentication negotiation,
  // i.e. it does not return a 401 (Unauthorized)
  // but immediately a 403 (Forbidden)
  client.params.authenticationPreemptive = true

  return client
}

void quietDown(cancel=false) {
  Map url = parseJenkinsUrl()
  HttpClient client = getAuthedClient()
  String path
  if (cancel){
      path = "cancelQuietDown"
  } else {
      path = "quietDown" // note capitalisation
  }
  println("About to request ${path}")
  PostMethod post = new PostMethod( "${url['url']}/${path}" )
  post.doAuthentication = true

  try {
    int result = client.executeMethod(post)
    println("Succesfully requested ${path}, Return Code: ${result}")
  } catch (Exception e){
    println("Failed to request ${path}: ${e}")
  } finally {
    post.releaseConnection()
  }
}
