import os
import subprocess
import sys
import pwsafe

CREDS = [
    {'type': 'text', 'username': 'dev_pubcloud_api_key'},
    {'type': 'text', 'username': 'dev_pubcloud_username'},
    {'type': 'text', 'username': 'dev_pubcloud_tenant_id'},
    {'type': 'text', 'username': 'rpc-jenkins-svc-github-pat'},
    {
        'type': 'file',
        'username': 'id_rsa_cloud10_jenkins_file',
        'filename': 'id_rsa_cloud10_jenkins'
    },
]


def add_cred(jenkins, ssh_key, secret_type, username, secret):
    cmd = "java -jar jenkins-cli.jar -s %s -i %s groovy " \
          "add_jenkins_cred.groovy %s %s %s" % \
          (jenkins, ssh_key, secret_type, username, secret)
    print(subprocess.check_output(cmd.split()))


def main():
    sso_username = os.environ.get('SSO_USERNAME', None)
    sso_password = os.environ.get('SSO_PASSWORD', None)
    project_id = os.environ.get('PWSAFE_PROJECT_ID', None)
    jenkins_url = os.environ.get('JENKINS_URL', None)
    jenkins_ssh_key = os.environ.get('JENKINS_SSH_KEY', None)
    tmp_dir = os.environ.get('TMP_DIR', None)

    if not (sso_username and sso_password and project_id and jenkins_url and
            tmp_dir):
        print("Exiting, one or more environment vars not set.")
        sys.exit(1)

    cli = pwsafe.PWSafeClient(sso_username, sso_password)
    proj = cli.projects[project_id]

    for cred in proj.credentials.list():
        for c in CREDS:
            if cred.username == c['username']:
                if c['type'] == 'text':
                    add_cred(
                        jenkins_url, jenkins_ssh_key, c['type'], c['username'],
                        cred.password
                    )
                elif c['type'] == 'file':
                    secret_path = os.path.join(tmp_dir, c['filename'])
                    secret_file = open(secret_path, 'w')
                    secret_file.write(cred.password)
                    secret_file.close()
                    add_cred(
                        jenkins_url, jenkins_ssh_key, c['type'], c['username'],
                        secret_path
                    )
                    # The temp workspace should get cleaned up when the build
                    # finishes, but let's just remove the secret file anyway
                    # once it's no longer needed.
                    os.remove(secret_path)


if __name__ == "__main__":
    main()
