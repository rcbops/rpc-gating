import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import groovy.transform.Field

import com.rackspace.exceptions.REException


void testPullRequest(String repoName, Boolean testWithAntecedents, String githubStatusContext){
    try {
        if (testWithAntecedents) {
           testPullRequestWithAntecedents(repoName, githubStatusContext)
        } else {
           testPullRequestOnly(repoName)
        }
    }catch (REException e){
        currentBuild.result="FAILURE"
    }
}


void testPullRequestOnly(String repoName){
    List allWorkflowJobs = Hudson.instance.getAllItems(WorkflowJob)
    List filteredComponentGateJobs = filterGateJobs(repoName, allWorkflowJobs)

    def parallelBuilds = [:]

    // Cannot do for (job in jobNames), see:
    // https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
    for (j in filteredComponentGateJobs) {
      WorkflowJob job = j
      String jobName = job.displayName
      parallelBuilds[jobName] = {
          build(
              job: jobName,
              wait: true,
              parameters: [
                  [
                      $class: "StringParameterValue",
                      name: "RPC_GATING_BRANCH",
                      value: RPC_GATING_BRANCH,
                  ],
                  [
                      $class: "StringParameterValue",
                      name: "BRANCH",
                      value: sha1,
                  ]
              ]
          )
      } // parallelBuilds
    } // for

    parallel parallelBuilds

    build(
      job: "Merge-Pull-Request",
      wait: false,
      parameters: [
        [
          $class: "StringParameterValue",
          name: "RPC_GATING_BRANCH",
          value: RPC_GATING_BRANCH,
        ],
        [
          $class: "StringParameterValue",
          name: "pr_repo",
          value: ghprbGhRepository,
        ],
        [
          $class: "StringParameterValue",
          name: "pr_number",
          value: ghprbPullId,
        ],
        [
          $class: "StringParameterValue",
          name: "commit",
          value: ghprbActualCommit,
        ],
      ]
    )
}


@Field WorkflowRun triggerBuild = currentBuild.rawBuild
@Field Result SUCCESS = Result.fromString("SUCCESS")
/**
 * Global variable for tracking the status of downstream gate builds
 * that are managed by testPullRequestWithAntecedents.
 */
@Field Map gateBuilds = [:]


/**
 * Test pull request with antecedent builds.
 *
 * For any build, an antecedent build is defined as being one:
 *   - of the same job type
 *   - triggered by a pull request targeted at the same base branch
 *   - with a lower build number
 *   - that is not finished
 *
 * If an antecedent build is no longer running, either it was successful
 * and the code has already merged or it failed and its changes can be
 * ignored.
 *
 * If multiple gate trigger builds are running, to ensure that each
 * pull request is tested against the HEAD of the branch that will exist
 * when it merges instead of the one that exists when the builds starts,
 * this function finds all the active antecedent builds and performs
 * tests on the assumption that the antecedent builds are all successful
 * and merge in the order they were started. If an antecedent fails, the
 * tests are restarted.
 */
void testPullRequestWithAntecedents(String repoName, String statusContext){
    List allWorkflowJobs = Hudson.instance.getAllItems(WorkflowJob)
    List filteredComponentGateJobs = filterGateJobs(repoName, allWorkflowJobs)

    triggerJobName = triggerBuild.getParent().displayName
    triggerJob = allWorkflowJobs.find {it.displayName == triggerJobName}
    antecedentTriggerBuilds = []
    pullRequestIDs = []
    triggerBuildTargetBranch = triggerBuild.getAction(ParametersAction).getParameter("ghprbTargetBranch").getValue()

    while (true) {
        updateGateBuilds()

        currentAntecedentTriggerBuilds = triggerJob.getBuilds().findAll {
            (
                it.isBuilding() == true
                && it.getNumber() < triggerBuild.getNumber()
                && it.getAction(ParametersAction).getParameter("ghprbTargetBranch").getValue() == triggerBuildTargetBranch
            )
        }.reverse()
        if (antecedentTriggerBuilds.size() != currentAntecedentTriggerBuilds.size()) {
            failedAntecedents = antecedentTriggerBuilds.findAll() {it.getResult() && it.getResult().isWorseThan(SUCCESS)}
            if (failedAntecedents) {
                println("Antecedent builds that have completed with failure:\n${failedAntecedents.join("\n")}")
                println("Terminating existing builds that depend on failed antecedents.")
                killGateBuilds()
            }else if (antecedentTriggerBuilds){
                completed = antecedentTriggerBuilds.findAll() {!(it in currentAntecedentTriggerBuilds)}
                println("Antecedent builds that have completed with success:\n${completed.join("\n")}")
            }
            antecedentTriggerBuilds = currentAntecedentTriggerBuilds
        } else{
           failedAntecedents = []
        }

        if (gateBuilds.isEmpty() || failedAntecedents){
            if (antecedentTriggerBuilds){
                println("Active antecedent builds:\n${antecedentTriggerBuilds.join("\n")}")
            }else {
                println("No active antecedent builds.")
            }
            println("Base branch being tested against: ${ghprbTargetBranch}.")
            pullRequestIDs = antecedentTriggerBuilds.collect(){getPullRequestID(it)}
            pullRequestIDs.add(getPullRequestID(triggerBuild))
            println("Pull requests to merge on top of base branch in tests: ${pullRequestIDs}.")
        }

        // A failed gate build only causes the trigger build to fail if all antecedents succeed
        if (antecedentTriggerBuilds.empty) {
            failedGateBuilds = getFailedGateBuilds()
            if (failedGateBuilds) {
                errMsg = "One or more builds failed to reach success:\n${failedGateBuilds.collect() {it.getAbsoluteUrl()}.join("\n")}"
                println(errMsg)
                throw new REException(errMsg)
            }
        }

        pullRequestIDsParam = common.dumpCSV(pullRequestIDs)
        def parallelBuilds = [:]
        for (j in filteredComponentGateJobs) {
            WorkflowJob job = j
            if (! (job in gateBuilds)) {
               gateBuilds[job] = [
                    "build": null,
                    "nextNumber": job.getNextBuildNumber(),
                ]
                String jobName = job.displayName
                parallelBuilds[jobName] = {
                    build(
                        job: jobName,
                        wait: false,
                        parameters: [
                            [
                                $class: "StringParameterValue",
                                name: "RPC_GATING_BRANCH",
                                value: RPC_GATING_BRANCH,
                            ],
                            [
                                $class: "StringParameterValue",
                                name: "BRANCH",
                                value: ghprbTargetBranch,
                            ],
                            [
                                $class: "StringParameterValue",
                                name: "pullRequestChain",
                                value: pullRequestIDsParam,
                            ],
                        ]
                    )
                } // parallelBuilds
            } // if
        } // for
        if (parallelBuilds){
            parallel parallelBuilds
        }
        if (antecedentTriggerBuilds.empty && isGateBuildsSuccess()){
            break
        }else {
            sleep(time: 120, unit: "SECONDS")
        }
    }

    (prRepoOrg, prRepoName) = ghprbGhRepository.split("/")
    /* When a check is required by GitHub, it prevents the pull request being merged if
       it is not marked as `"success"`. Normally GHPRB would be soley responsible for
       updating the status context however this would necessitate a separate job to
       perform the merge. The following section updates the pull request's status
       context on GitHub if all tests were successful to enable the same build to merge
       the pull request. GHPRB will the report success again assuming all subsequent
       steps succeed.
     */
    println("Updating pull request status context.")
    description = "Gate tests passed, merging..."
    github.create_status(prRepoOrg, prRepoName, ghprbActualCommit, "success", triggerBuild.getAbsoluteUrl(), description, statusContext)
    github.merge_pr(prRepoOrg, prRepoName, ghprbPullId, ghprbActualCommit)
}


List filterGateJobs(String repoName, List allWorkflowJobs){
    List componentGateJobs = (allWorkflowJobs.findAll {it.displayName =~ /GATE_${repoName}-${ghprbTargetBranch}/}).sort(false)
    println("Discovered the following pull request gate jobs for repo ${repoName}:")
    println(componentGateJobs.collect() {it.displayName}.join("\n"))

    List filteredComponentGateJobs = componentGateJobs.findAll {
      def job_skip_pattern = it.getProperty(hudson.model.ParametersDefinitionProperty)
                               .getParameterDefinition("skip_pattern")
                               .getDefaultValue()
      ! common.isSkippable(job_skip_pattern, "")
    }
    println("Remaining pull request gate jobs for repo ${repoName} after filtering out skip_pattern:")
    println(filteredComponentGateJobs.collect() {it.displayName}.join("\n"))

    return filteredComponentGateJobs
}


Integer getPullRequestID(build){
    build.getAction(ParametersAction).getParameter("ghprbPullId").getValue()
}


Boolean isGateBuildsSuccess(){
    gateBuilds.every {
        build = getBuild(it)
        build && ! build.hasntStartedYet() && ! build.isBuilding() && build.getResult() && build.getResult().equals(SUCCESS)
    }
}


List getFailedGateBuilds(){
    gateBuilds.findAll() {
        build = getBuild(it)
        (
            build
            && build.getResult()
            && build.getResult().isWorseThan(SUCCESS)
        )
    }.collect {getBuild(it)}
}


WorkflowRun getBuild(build){
    build.value["build"]
}


Integer getNextNumber(build){
    build.value["nextNumber"]
}


WorkflowJob getJob(build){
    build.key
}


WorkflowRun findBuild(WorkflowJob job, Integer oldestNumber){
    build = null
    potentialBuild = job.getLastBuild()
    while(potentialBuild.getNumber() >= oldestNumber){
        potentialBuildCause = potentialBuild.getCauses()[0]
        if (potentialBuildCause instanceof Cause.UpstreamCause){
            if (potentialBuildCause.getUpstreamBuild() == triggerBuild.getNumber()){
                build = potentialBuild
                break
            }
        }
        potentialBuild = potentialBuild.getPreviousBuild()
    }
    return build
}


void updateGateBuilds(){
    buildCount = gateBuilds.findAll {getBuild(it)}.size()
    missingBuilds = false
    gateBuilds.clone().each { gateBuild ->
        build = getBuild(gateBuild)
        if (! build){
            job = getJob(gateBuild)
            if (findQueueItem(job)){
                return
            }
            startedBuild = findBuild(job, getNextNumber(gateBuild))
            if (startedBuild){
              gateBuilds[job]["build"] = startedBuild
              println("New gate test build: ${startedBuild} ${startedBuild.getAbsoluteUrl()}")
            } else {
                println("Requested build of job ${job.displayName} cannot be found, preparing for new request.")
                gateBuilds.remove(job)
                missingBuilds = true
            }
        }
    }
    updatedBuildCount = gateBuilds.findAll {getBuild(it)}.size()
    if ((! missingBuilds) && gateBuilds.size() == updatedBuildCount && updatedBuildCount > buildCount){
        buildURLs = gateBuilds.findAll {getBuild(it)}.collect {getBuild(it).getAbsoluteUrl()}
        println("All gate tests started:\n${buildURLs.join("\n")}")
    }
}


Queue.WaitingItem findQueueItem(WorkflowJob job){
    item = null
    queue = Queue.getInstance()
    for (queueItem in queue.getItems()){
        if (queueItem instanceof Queue.WaitingItem){
            qica = queueItem.getAction(CauseAction)
            qiuc = (qica.findCause(Cause.UpstreamCause))
            if (qiuc.getUpstreamBuild() == triggerBuild.getNumber() && queueItem.task == job){
                item = queueItem
                break
            }
        }
    }
    return item
}


void killGateBuilds(){
    queue = Queue.getInstance()
    gateBuilds.each { gateBuild ->
        build = getBuild(gateBuild)
        if (! build){
            job = getJob(gateBuild)
            queueItem = findQueueItem(job)
            if (queueItem){
                println("Terminating queued downstream test ${queueItem}")
                queue.cancel(queueItem)
            }
            build = findBuild(job, getNextNumber(gateBuild))
        }
        if (build){
            println("Terminating downstream test ${build}")
            listener = build.asFlowExecutionOwner().getListener()
            listener.hyperlink(
                triggerBuild.getAbsoluteUrl(),
                "Terminated by upstream build due to failure in antecedent test.\n"
            )
            build.doKill()
        }
    }
    gateBuilds = [:]
}


return this;
