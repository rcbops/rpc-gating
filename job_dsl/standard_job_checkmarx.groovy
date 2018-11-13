// Can't use env.FOO = {FOO} to transfer JJB vars to groovy
// as this file won't be templated by JJB.
// Alternative is to use parameters with JJB vars as the defaults.
library "rpc-gating@${RPC_GATING_BRANCH}"
common.globalWraps(){
  common.standard_job_slave(env.SLAVE_TYPE) {
    try {

      stage("Configure Git"){
        common.configure_git()
      }

      stage("Clone repo to scan"){
        common.clone_with_pr_refs(
          "repo",
          repo_url,
          branch
        )
      }

      stage("Checkmarx Scan"){
        withCredentials([
          string(
            credentialsId: 'CHECKMARX_RE_TEAM_ID',
            variable: 'groupId'
          ),
          string(
            credentialsId: 'CHECKMARX_SERVER',
            variable: 'serverUrl'
          )
        ]){
          // Switch to scan repo dir to avoid sending the gating venv to checkmarx
          dir("repo"){
            presets = [
              // values generated using the snippet generator
              // ${jenkins}/pipeline-syntax/
              // sample step > step: general build step
              //    Build Step > Execute Checkmarx Scan
              "default": "36",
              "pci": "5",
              "all": "1"
            ]
            if (!presets.keySet().contains(SCAN_TYPE)){
              throw new Exception("Invalid scan type: ${SCAN_TYPE}, should be default or pci")
            }
            step([$class: 'CxScanBuilder',
                  avoidDuplicateProjectScans: false, // duplicate detection isn't great and kills scans of the same project with different parameters
                  comment: '',
                  credentialsId: '',
                  excludeFolders: '',
                  excludeOpenSourceFolders: '',
                  exclusionsSetting: 'global',
                  failBuildOnNewResults: true,
                  failBuildOnNewSeverity: 'LOW',
                  filterPattern: '''!**/_cvs/**/*, !**/.svn/**/*,   !**/.hg/**/*,   !**/.git/**/*,  !**/.bzr/**/*, !**/bin/**/*,
                  !**/obj/**/*,  !**/backup/**/*, !**/.idea/**/*, !**/*.DS_Store, !**/*.ipr,     !**/*.iws,
                  !**/*.bak,     !**/*.tmp,       !**/*.aac,      !**/*.aif,      !**/*.iff,     !**/*.m3u, !**/*.mid, !**/*.mp3,
                  !**/*.mpa,     !**/*.ra,        !**/*.wav,      !**/*.wma,      !**/*.3g2,     !**/*.3gp, !**/*.asf, !**/*.asx,
                  !**/*.avi,     !**/*.flv,       !**/*.mov,      !**/*.mp4,      !**/*.mpg,     !**/*.rm,  !**/*.swf, !**/*.vob,
                  !**/*.wmv,     !**/*.bmp,       !**/*.gif,      !**/*.jpg,      !**/*.png,     !**/*.psd, !**/*.tif, !**/*.swf,
                  !**/*.jar,     !**/*.zip,       !**/*.rar,      !**/*.exe,      !**/*.dll,     !**/*.pdb, !**/*.7z,  !**/*.gz,
                  !**/*.tar.gz,  !**/*.tar,       !**/*.gz,       !**/*.ahtm,     !**/*.ahtml,   !**/*.fhtml, !**/*.hdm,
                  !**/*.hdml,    !**/*.hsql,      !**/*.ht,       !**/*.hta,      !**/*.htc,     !**/*.htd, !**/*.war, !**/*.ear,
                  !**/*.htmls,   !**/*.ihtml,     !**/*.mht,      !**/*.mhtm,     !**/*.mhtml,   !**/*.ssi, !**/*.stm,
                  !**/*.stml,    !**/*.ttml,      !**/*.txn,      !**/*.xhtm,     !**/*.xhtml,   !**/*.class, !**/*.iml, !Checkmarx/Reports/*.*''',
                  fullScanCycle: 10,
                  generatePdfReport: true,
                  groupId: groupId,
                  includeOpenSourceFolders: '',
                  osaArchiveIncludePatterns: '*.zip, *.war, *.ear, *.tgz',
                  password: '',
                  preset: presets[SCAN_TYPE],
                  projectName: repo_name,
                  serverUrl: serverUrl,
                  sourceEncoding: '1',
                  username: '',
                  vulnerabilityThresholdEnabled: true,
                  highThreshold: 1,
                  includeOpenSourceFolders: '',
                  lowThreshold: 1,
                  mediumThreshold: 1,
                  vulnerabilityThresholdResult: 'FAILURE',
                  waitForResultsEnabled: true])
          } // dir
        } // withCredentials
      } // stage
    } catch(e) {
      print(e)
      // Only create failure card when run as a post-merge job
      if (env.ghprbPullId == null && ! common.isAbortedBuild() && env.JIRA_PROJECT_KEY != '') {
        print("Creating build failure issue.")
        common.build_failure_issue(env.JIRA_PROJECT_KEY)
      } else {
        print("Skipping build failure issue creation.")
      } // if
      throw e
    } // try
  }
} // globalWraps
