# Local Jenkins and JJB deploy
This setup is based on the following:
https://technologyconversations.com/2017/06/16/automating-jenkins-docker-setup/
https://github.com/jsallis/docker-jenkins-job-builder

This directory contains the tooling to run Jenkins with the rpc-gating jobs
deployed locally via a docker swarm service. Running the service should
perform the following:
  * Build a docker image containing Jenkins with the defined set of Jenkins plugins.
  * Deploy the rpc-gating jobs to the Jenkins instance via Jenkins job builder.

The intent of this is just to be able to review the impact of jjb changes on
Jenkins jobs. This setup does not have the ability to independently execute
those jobs.

## Usage
All commands shown below are executed from within the root of your rpc-gating
checkout.

### Build Jenkins Docker Image
This will build the docker image containing Jenkins.
```
docker image build -t rpc-gating/jenkins_sandbox -f docker-jenkins-sandbox .
```

### Run Docker container
Here we run a docker container from the image generated above. The Jenkins web
interface port is mapped to port 8080 locally so that we can access it from
the browser on our local system. The current directory (intended to be the
root of the rpc-gating checkout) is mounted within the container.
```
docker run -d \
  -p 127.0.0.1:8080:8080 \
  --volume "$PWD":/opt/jenkins-job rpc-gating/jenkins_sandbox
```

### Deploy rpc_jobs to Jenkins
Here we run Jenkins job builder on the container in order to compile the jobs
and update them on the Jenkins server.
```
docker exec -it \
  --workdir /opt/jenkins-job \
  <container-id> \
  jenkins-jobs update rpc_jobs
```
