ARG BASE_IMAGE=ubuntu
FROM ${BASE_IMAGE}
RUN apt-get update && apt-get install -y groovy2 python-pip build-essential python-dev libssl-dev curl libffi-dev sudo git-core
COPY requirements.txt /requirements.txt
COPY test-requirements.txt /test-requirements.txt
COPY constraints.txt /constraints.txt
RUN pip install -c /constraints.txt -r /requirements.txt
RUN pip install -c /constraints.txt -r /test-requirements.txt
RUN useradd jenkins --shell /bin/bash --create-home --uid 500
RUN echo "jenkins ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers
