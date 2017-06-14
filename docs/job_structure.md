# Stages

## Allocate Resources
* Purpose: Create a node for use by Jenkins. This may be creating a public cloud instance, reserving lab nodes, or some other process.
* Inputs:
  * (optional) Node name. Some processes (pub cloud) will need a name, others will have fixed names
  * Keys File: A URL to a list of SSH public keys, all keys found at this URL must be added to root's authorised keys on all nodes
* Outputs:
  * List of Hostnames/IPs, primary host first
  * Detail all created resources, to be read by the cleanup job
  * Includes server UUIDs, monitoring agents/checks
* Example Implementations:
  * Public cloud instance
  * On Metal Instance
  * Heat Multi-node
* Node: CIT Jenkins Slave

## Connect Slave
* Purpose: Deploy the jenkins slave to the primary node produced by `Allocate Node`, register slave with Jenkins Master.
* Inputs:
  * Hostname: The hostname or IP of the target
  * SSH Private Key: Key to use to connect to the target
  * Slave Name:  This will be used to name the slave, so it can be targed for subsequent steps
* Node: CIT Jenkins Slave

## Validate Hosts
* Purpose: Ensure all hosts returned by `Allocate Resources` are up and responsive, and that all hosts are clean (rekicks completed, etc.)
* Inputs:
  * list of hosts
* Node: Newly created slave

## Prepare Hosts
* Purpose: Perform all necessary pre-deployment tasks.
  * Configure networking
  * Install package dependencies.
    * Ansible versions, etc.
  * Drop private ssh key so primary node can connect to other nodes (if there are any)
  * Configure devices (loopback, physical) for cinder/swift/ceph
* Inputs:
  * List of hosts (as other hosts will need to be connected to)
  * Software dependencies
  * SSH Private Key: Key to use to connect to the targets
* Example Implementations:
  * See: `Allocate Node`, each of those types will require different prep steps
  * This could be multiple steps:
    * Eg there could be a step for configuring ansible and one for configuring networking
    * The ansible step could probably be reused across envs, but the networking step is likely to be bespoke
    * Each pipeline would run as many of the prepare host steps as it requires
* Node: Newly created slave

## Prepare Deployment
* Purpose: Drop deployment code and generate config so that the environment is ready for deploy
* Inputs:
  * Version to deploy
  * Feature enable/disable flags
* Example Implementations:
  * OSA
  * RPCO
* Node: Newly created slave

## Deploy
* Purpose: Deploy an environment
* Node: Newly created slave

## Test
* Inputs:
  * Test list / test selection
  * Verbosity flags
* Outputs:
  * Return testresults.xml: junitxml test file that can be interpreted by Jenkins
* Example Implementations:
  * Tempest
  * Selenium
  * Holland
  * Ceph Func
* Node: Newly created slave

## Upgrade
* Purpose: Upgrade an existing deploy
* Inputs:
  * Version to upgrade to latest or designated tag
* Outputs:
  * Logs, upgrade artifacts
  * Output from test VMs, network ping tests, etc.
* Node: Newly created slave

## Push Public Artifacts
* Purpose: Push artifacts that may be reused in future deployments up to the public repo servers.
* Inputs:
  * Repo Server config (host, key, dir)
* Node: Newly created slave

## Archive Artifacts to Jenkins
* Purpose: Gather all artifacts to be stored in Jenkins onto the primary host.
* Node: Newly created slave

## Cleanup
* Purpose: Free all resources used in the job, cleanup anything that will be reused or does not tear down automatically
* Inputs:
  * Hosts: list of hosts
  * Resources: File detailing all resources to be removed, created by the corresponding `Allocate Resources` implementation, includes monitoring agents/checks.
* Node: CIT Jenkins Slave
