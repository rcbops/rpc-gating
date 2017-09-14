# RPC-AIO Leapfrog Gate Jobs

Leapfrog periodic jobs are used to determine the health of an upgrade from a
specific revision of a deployed version of RPC-Openstack to the latest supported
revision in production.  They are in place to periodically test and will
eventually gate on pull requests going into the RPC-O repositories until the
leapfrogs have completed for each deployed version.

The current configuration that generates the various leapfrog gate jobs are
stored here:

[https://github.com/rcbops/rpc-gating/blob/master/rpc_jobs/rpc_aio_leapfrog.yml
](https://github.com/rcbops/rpc-gating/blob/master/rpc_jobs/rpc_aio_leapfrog.yml)

The base template for AIO jobs resides in
[rpc_aio.yml
](https://github.com/rcbops/rpc-gating/blob/master/rpc_jobs/rpc_aio.yml#L186)
so that we don't have to manage multiple templates.

Individual jobs are generated from the configuration file in the following
format which is what you'll see when you log into the Jenkins Server:

    RPC-AIO_{series}-{image}-{action}-{scenario}-{ztrigger}

So one example job might be:

    RPC-AIO_kilo-r11.1.18-trusty-leapfrogupgrade-swift

The configuration is broken up into several sections:

##### Series

The name of the job, the branch to test from rpc-openstack, and branches to run
PR jobs on if present.

#### Image

The short form of the image name and image name as reflected in the Public Cloud
Release Engineering account.  This is the image used as a base to run the tests
on.

#### Action

This specifies the various actions and tasks that will be ran during the build.

#### Scenario

This will run though various scenarios for the build, depending on what products
need to be layered on top of RPC-O to test with. (i.e. swift, ceph, etc)

##### Ztrigger

Determines the types of tests to be ran, periodically ran, or tested on PR from
the specified branches in the series.

#### Excludes

Uses to remove jobs that may not be needed.  A job will be generated for every
scenario and there may be situations you don't want a job created.  The excludes
will allow you to override those and ensure they aren't created.  Note: It
appears that you can only specify two rules at a time, if you do three or more,
the exclude rules may not be applied.

### Adding Additional Branches to be Tested

You'll want to make a PR to add additional branches to be tested and put those
under the series section.  As we move off older versions, the jobs can be pulled
out and removed.  The release engineering team can then deploy those jobs out
once they are ready.

*Example:*

    series:
      - kilo-r11.1.18: 
          branch: r11.1.18
          branches: "r11.1.18"

In the same way, if jobs need to be cleaned up or deleted, you can remove the
particular series from the configuration file and the release engineering team
can clean those up on the Jenkins server.

#### Periodic vs PR

The are two types of jobs that usually go under the ztrigger section, periodic
and pr.  pr jobs are ran on commits to validate a commit passes tests before
providing the green light.  When adding those types of jobs, you will need to
make sure to configure it on the repo in Github as well so that the job is fired
on commits.

Periodic jobs run on demand and validate that the code in the branch is still
valid and passes.

#### Location of Jobs

Jobs will be deployed to the RPC Jenkins Server here:

[https://rpc.jenkins.cit.rackspace.net/job/--job
name--](https://rpc.jenkins.cit.rackspace.net)

