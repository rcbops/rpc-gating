# Debugging Gate Failures

## Blue Ocean
[Blue ocean](https://rpc.jenkins.cit.rackspace.net/blue/organizations/jenkins/pipelines) is the new UI for [Jenkins](https://rpc.jenkins.cit.rackspace.net/), that makes it easier to find the point where
a job failed without trawling through the console. To use blue ocean, follow
the link from a github pull request status to a job, then click "Open Blue
Ocean" from the left sidebar.

<img src="images/debugging/sidebar.png" width=200>

Blue ocean displays a horizontal pipeline of stages. Below that is a vertical
list of steps within the selected stage, expand any step to view its output.
Failed stages and steps are shown in red to assist with finding the failure point.

![](images/debugging/blueocean.png)



## The Console
Jenkins collects all of the output from commands it executes, these outputs are
streamed to the console for each Job. This includes logging for Jenkins
operations such as creating a single use instance for a project's jobs to
execute on. As the console includes logging for steps not defined by a project,
it's inefficient to wade through it when looking for the cause of a failure.

## Artifacts
While command outputs as viewed in blue ocean or the console are useful, they
don't tell the whole story. Jobs can produce artifacts which are stored
for a [set time](https://github.com/rcbops/rpc-gating/blob/master/playbooks/upload_to_cloud_files.yml#L13-L19) after the completion of a job. Any job can produce artifacts
and use the provided functions to publish those. Jobs artifacts should include
everything required to debug a build.

To view artifacts, click "Build Artifact Links" in the sidebar from the standard
Jenkins build page.

### Artifact Example: RPCO

RPC-O builds archive the etc and log dirs of each container and the host.
This includes the openstack_deploy directory, all the ansible facts, the generated
configuration files and log files for each service. This comprehensive set of
artifacts saves times recreating problems as usually the required information
is stored with the failing job.





For AIO jobs, the host is the deploy node, so the openstack_deploy directory
which contains all the ansible config can be found in the hosts's etc folder.
That folder also contains all the ansible facts that were cached.

 
## Logging in
If the console and artifacts don't provide enough information, it is possible
to login to a gate instance and investigate the problem. Normally instances
are deleted as soon as they finish executing a job. In order to retain
the instance, the job must be rerun with "Pause" added to the STAGES parameter.
![](images/debugging/add_pause.png)
Note that it's not possible to change parameters via a pull request comment
recheck command, so the rerun will have to be triggered from the Jenkins UI. To
trigger a build select 'Rebuild' (not replay!) from the side bar.

Adding Pause will cause Jenkins to wait for input before clearing up the node.
Information collection should be completed at the earliest opportunity as the
node will be cleaned up by a periodic job when it hits the instance [age
limit](https://github.com/rcbops/rpc-gating/blob/master/rpc_jobs/periodic_cleanup.yml#L6-L9).
It's important to continue the paused Jenkins job after you have finished
investigating, as even if the instance is deleted the Job will be reserving at
least one Jenkins executor. To resume a job, view the console for the running job,
the last line will contain links for continue and abort. If you don't have
permissions to resume the job ping us in [slack #rpc-releng ](https://rackspace.slack.com/messages/C5E42823W)

### Obtaining the IP
Search the console for "Stage Complete: Connect Slave" and you should find a
block similar to this one:

```
Stage Complete: Connect Slave
[Pipeline] }
[Pipeline] // stage
[Pipeline] node
Running on raltdcp-10-77a6 in /var/lib/jenkins/workspace/RPC-AIO_liberty-trusty-deploy-ceph-pr
```

Note the node name after "running on". To view the config for that node replace
YOUR-NODE-NAME with the node name you noted in the last step.

https://rpc.jenkins.cit.rackspace.net/computer/YOUR-NODE-NAME

The ip is shown in brackets after the node name.
![](images/debugging/node_ip.png)

Once you have the ip, ssh to it as root. If your key is not present, see the
next section.

### SSH keys
All the public keys in the [keys file](https://github.com/rcbops/rpc-gating/blob/master/keys/rcb.keys) are injected
into root's authorized keys for all instances used for testing. If you need
access to these instances, create a PR against that file to add your key.


## Contact Us
If you have any issues, please ask the team for help in [slack #rpc-releng](https://rackspace.slack.com/messages/C5E42823W
)
