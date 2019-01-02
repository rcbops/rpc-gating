// Upload all folders in the current working directory to checkmarx and scan for vulnerabilities.
// If you wish to scan a subdir of the working dir, call this function within dir("subdir"){}
def scan(String scan_type, String repo_name){
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
        presets = [
        // values generated using the snippet generator
        // ${jenkins}/pipeline-syntax/
        // sample step > step: general build step
        //    Build Step > Execute Checkmarx Scan
        "default": "36",
        "pci": "5",
        "all": "1"
        ]
        if (!presets.keySet().contains(scan_type)){
            throw new Exception("Invalid scan type: ${scan_type}, should be default or pci")
        }
        // This step has a habbit of throwing NPEs, retry it. RE
        retry(3) {
            // Try within retry so that sleep can be added on failure.
            // This may help if the issue is at the remote end.
            try {
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
                    preset: presets[scan_type],
                    projectName: repo_name,
                    serverUrl: serverUrl,
                    sourceEncoding: '1',
                    username: '',
                    vulnerabilityThresholdEnabled: true,
                    highThreshold: 0,
                    includeOpenSourceFolders: '',
                    lowThreshold: 0,
                    mediumThreshold: 0,
                    vulnerabilityThresholdResult: 'FAILURE',
                    waitForResultsEnabled: true]
                )
            } catch (Exception e){
                print ("Caught exception while running checkmarx scan: "+e)
                sleep(time: 30, unit: "SECONDS")
                // exception must propagate back to the retry call
                throw e
            } //try
        } // retry
    } // withCredentials
}

return this
