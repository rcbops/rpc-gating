/* This code is heavily borrowed from
   http://stackoverflow.com/questions/35025829/i-want-to-create-jenkins-credentials-via-ansible */

import com.cloudbees.plugins.credentials.CredentialsScope
import hudson.util.Secret
import java.nio.file.Files
import jenkins.model.*
import org.apache.commons.fileupload.*
import org.apache.commons.fileupload.disk.*
import org.jenkinsci.plugins.plaincredentials.impl.*

def addJenkinsCred = { cred_type, cred_id, secret ->
    def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
        com.cloudbees.plugins.credentials.common.StandardCredentials.class,
        Jenkins.instance
    )

    def c = creds.findResult { it.id == cred_id ? it : null }

    if ( c ) {
        println "found credential with id of ${cred_id}"
    } else {
        def credentials_store = Jenkins.instance.getExtensionList(
            'com.cloudbees.plugins.credentials.SystemCredentialsProvider'
            )[0].getStore()

        def scope = CredentialsScope.GLOBAL
        def result = null

        if (cred_type == 'text') {
            result = credentials_store.addCredentials(
                com.cloudbees.plugins.credentials.domains.Domain.global(),
                new StringCredentialsImpl(scope, cred_id, cred_id, Secret.fromString(secret))
                )
        } else if (cred_type == 'file') {
            def file = new File(secret)
            def factory = new DiskFileItemFactory()
            def dfi = factory.createItem("", "application/octet-stream", false, file.getName())
            def out = dfi.getOutputStream()

            Files.copy(file.toPath(), out)

            result = credentials_store.addCredentials(
                com.cloudbees.plugins.credentials.domains.Domain.global(),
                new FileCredentialsImpl(scope, cred_id, cred_id, dfi, "", "")
                )

        } else {
            println "invalid credential type specified"
        }

        if (result) {
            println "credential added for ${cred_id}"
        } else {
            println "failed to add credential for ${cred_id}"
        }
    }
}

addJenkinsCred(args[0], args[1], args[2])
