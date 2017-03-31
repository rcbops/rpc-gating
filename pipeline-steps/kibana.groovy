def kibana(){
  common.runStage(
    stage_name: "Prepare Kibana Selenium",
    conditional: True,
    stage: {
      kibana_prep()
    }
  )
  common.runStage(
    stage_name: "Kibana Tests",
    conditional: True,
    stage: {
      kibana_tests()
    }
  )
}

def kibana_prep(){
  dir("kibana-selenium") {
    git url: "https://github.com/rcbops-qe/kibana-selenium.git", branch: "master"

    sh """#!/bin/bash
    apt-get install -y phantomjs

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
