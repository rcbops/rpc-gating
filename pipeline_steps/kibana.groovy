def kibana(){
  common.conditionalStage(
    stage_name: "Prepare Kibana Selenium",
    stage: {
      kibana_prep()
    }
  )
  common.conditionalStage(
    stage_name: "Kibana Tests",
    stage: {
      kibana_tests()
    }
  )
}

def kibana_prep(){
  dir("kibana-selenium") {
    git url: "https://github.com/rcbops-qe/kibana-selenium.git", branch: "master"

    sh """#!/bin/bash
      # The phantomjs package on 16.04 is buggy, see:
      # https://github.com/ariya/phantomjs/issues/14900
      # https://bugs.launchpad.net/ubuntu/+source/phantomjs/+bug/1578444
      #apt-get install -y phantomjs
      apt-get install -y fontconfig
      wget https://bitbucket.org/ariya/phantomjs/downloads/phantomjs-2.1.1-linux-x86_64.tar.bz2
      tar -xjf phantomjs-2.1.1-linux-x86_64.tar.bz2

      if [[ ! -d ".venv" ]]; then
          pip install virtualenv
          virtualenv .venv
      fi
      source .venv/bin/activate
      if [ -f ~/.pip/pip.conf ]; then
        mv ~/.pip/pip.conf ~/.pip/pip.conf.bak
        pip install -r requirements.txt
        mv ~/.pip/pip.conf.bak ~/.pip/pip.conf
      else
        pip install -r requirements.txt
      fi
    """
  }
}

def kibana_tests(){
  try {
    dir("kibana-selenium") {
      git url: "https://github.com/rcbops-qe/kibana-selenium.git", branch: "master"
      sh """#!/bin/bash
        source .venv/bin/activate
        export PYTHONPATH=\$(pwd)
        export PATH=\$PATH:./phantomjs-2.1.1-linux-x86_64/bin
        # Remove any existing screenshots from old runs
        rm -f *.png
        python conf-gen.py --secure --password-file /etc/openstack_deploy/user_rpco_secrets.yml \
          --vip-file /etc/openstack_deploy/openstack_user_config.yml
        nosetests -sv --with-xunit testrepo/kibana/kibana.py
      """
    } // dir
  } catch (e){
    print(e)
    throw(e)
  } finally {
    junit allowEmptyResults: true, testResults: 'kibana-selenium/nosetests.xml'
    archiveArtifacts(
      allowEmptyArchive: true,
      artifacts: "kibana-selenium/*.png"
    ) //archive
  } // finally
}

return this;
