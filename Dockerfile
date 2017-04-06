FROM ubuntu
RUN apt-get update && apt-get install -y groovy2 python-pip build-essential python-dev libssl-dev
RUN apt-get install -y libffi-dev
RUN apt-get install -y sudo
COPY requirements.txt /requirements.txt
COPY constraints.txt /constraints.txt
RUN pip install -c /constraints.txt -r /requirements.txt
RUN useradd jenkins --shell /bin/bash --create-home --uid 500
RUN echo "jenkins ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers
