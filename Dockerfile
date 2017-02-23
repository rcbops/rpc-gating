FROM ubuntu
RUN apt-get update && apt-get install -y groovy2 python-pip build-essential python-dev libssl-dev
RUN apt-get install -y libffi-dev
RUN apt-get install -y sudo
RUN pip install jenkins-job-builder ansible
RUN useradd jenkins --shell /bin/bash --create-home --uid 500
RUN echo "jenkins ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers
