# CIT Jenkins Testing Environment

This dockerfile is for producing a docker container that mimics the current CIT/TES jenkins master environment.

Before it can be run, three files must be obtained from the Jenkins master:

1. `vlj.tar.gz`. This is a tar of `/var/lib/jenkins` created in `/var/lib` and excluding a few patterns:
   ```
   cd /var/lib; tar cvzf vlj.tar.gz \
       --exclude .git \
       --exclude builds \
       --exclude \*workspace\* \
       --exclude reports \
       --exclude .cache \
       --exclude cache \
       --exclude monitoring \
       --exclude fingerprints \
       --exclude tmp \
       --exclude \*venv\* \
       --ignore-failed-read \
       jenkins
   ```
1. `cacerts`: pull this from `/etc/pki/java`
1. `jenkins-$version.war`: pull this from `/usr/lib/jenkins`, you may need to update the version in the `Dockerfile`.
