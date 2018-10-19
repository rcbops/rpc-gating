FROM centos:centos6.10
RUN yum install -y java-1.8.0-openjdk perl
RUN mkdir -p /var/log/jenkins /usr/lib/jenkins /var/lib/jenkins/tmp
RUN curl -sO https://ssltool.rackspace.com/cli/ssltool-cli
RUN chmod +x ./ssltool-cli
RUN echo yes | ./ssltool-cli rscerts
RUN mkdir -p /etc/pki/java
COPY cacerts /etc/pki/java/
COPY vlj.tar.gz /var/lib
COPY jenkins.2.107.1.war /usr/lib/jenkins
RUN cd /usr/lib/jenkins; ln -s jenkins.2.107.1.war  jenkins.war
RUN cd /var/lib; tar xzf vlj.tar.gz; chown -R root:root /var/lib/jenkins
RUN rm -rf /var/lib/jenkins/nodes/*
EXPOSE 8080/tcp
CMD /usr/bin/java \
    -Dorg.apache.commons.jelly.tags.fmt.timeZone=America/Chicago \
    -Xmx1g \
    -Djava.awt.headless=true \
    -Dhudson.model.DirectoryBrowserSupport.CSP= \
    -Djava.io.tmpdir=/var/lib/jenkins/tmp \
    -DJENKINS_HOME=/var/lib/jenkins \
    -jar /usr/lib/jenkins/jenkins.war \
    --webroot=/var/cache/jenkins/war \
    --httpPort=8080 \
    --debug=5 \
    --handlerCountMax=200 \
    --handlerCountMaxIdle=20

# --logfile=/var/log/jenkins/jenkins.log \
# Removed because its easier to use the console or docker logs.

# See readme for where to get the files required by COPY instructions.

#DO NOT PUSH images created from this dockerfile to a public repo as they contain secrets
