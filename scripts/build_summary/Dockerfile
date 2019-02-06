FROM ubuntu:16.04
RUN apt-get update \
 && apt-get install -y \
      groovy2 \
      python3-pip \
      build-essential \
      python3-dev \
      libssl-dev \
      curl \
      libffi-dev \
      sudo \
      git-core \
      python3-lxml \
      python-dev \
      python-pip
COPY requirements.txt /requirements.txt
COPY constraints.txt /constraints.txt
RUN pip3 install \
      -c /constraints.txt \
      -r /requirements.txt
RUN pip3 install lxml
RUN useradd jenkins --shell /bin/bash --create-home --uid 500
RUN echo "jenkins ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers
VOLUME /in
VOLUME /out
VOLUME /cache
RUN mkdir -p /opt/buildsummary
COPY *.py /opt/buildsummary/
COPY *.sh /opt/buildsummary/
WORKDIR /opt/buildsummary
CMD bash -xeu /opt/buildsummary/run.sh
