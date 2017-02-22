def horizon_integration(){
  common.conditionalStage(
    stage_name: "Prepare Horizon Selenium",
    stage: {
      if (env.RPC_BRANCH.contains("mitaka") || env.RPC_BRANCH.contains("r13.")) {
        mitaka_prep()
      }
    }
  )
  common.conditionalStage(
    stage_name: "Horizon Tests",
    stage: {
      if (env.RPC_BRANCH.contains("mitaka") || env.RPC_BRANCH.contains("r13.")) {
        mitaka_tests()
      }
    }
  )
}

def mitaka_prep() {
  dir("horizon") {
    git url: "https://github.com/openstack/horizon.git", branch: "stable/mitaka"

    sh """#!/bin/bash
    apt-get install -y xvfb firefox
    apt-get remove -y firefox
    wget -q https://ftp.mozilla.org/pub/firefox/releases/44.0/linux-x86_64/en-US/firefox-44.0.tar.bz2
    tar -xjf firefox-44.0.tar.bz2
    mv firefox /opt/firefox44/
    ln -s /opt/firefox44/firefox /usr/bin/firefox

    if [[ ! -d ".venv" ]]; then
        pip install virtualenv
        virtualenv .venv
        virtualenv --relocatable .venv
    fi
    source .venv/bin/activate

    mv ~/.pip/pip.conf ~/.pip/pip.conf.bak
    pip install selenium==2.53.1
    pip install -r test-requirements.txt
    pip install -r requirements.txt
    virtualenv --relocatable .venv
    mv ~/.pip/pip.conf.bak ~/.pip/pip.conf
    """
  }
}

def mitaka_tests(){
    try {
      dir("horizon") {
        git url: "https://github.com/openstack/horizon.git", branch: "stable/mitaka"

        sh """#!/bin/bash
        # Create Horizon config file
        export DASHBOARD_URL=\$(sudo grep external_lb_vip_address /etc/openstack_deploy/openstack_user_config.yml | awk '{print \$2}')
        export DASHBOARD_URL=https://\$(echo \$DASHBOARD_URL | tr -d '\"')
        sed -i "/dashboard_url/c\\dashboard_url=\$DASHBOARD_URL" openstack_dashboard/test/integration_tests/horizon.conf

        set +x
        sed -i "s/^password=.*/password=demo/g" openstack_dashboard/test/integration_tests/horizon.conf
        export ADMIN_PWD=\$(sudo grep keystone_auth_admin_password /etc/openstack_deploy/user_osa_secrets.yml | awk '{print \$2}')
        sed -i "s/^admin_password=.*/admin_password=\$ADMIN_PWD/g" openstack_dashboard/test/integration_tests/horizon.conf
        set -x

        # Run Horizon tests
        source .venv/bin/activate
        export TMP_CONF="/tmp/\${BUILD_TAG}_horizon_conf"
        mv openstack_dashboard/test/integration_tests/horizon.conf \$TMP_CONF
        export HORIZON_INTEGRATION_TESTS_CONFIG_FILE=\$TMP_CONF

        export NOSE_WITH_XUNIT=1
        rm -f nosetests.xml
        ./run_tests.sh -N --selenium-headless --integration
        """
      }
    } catch (e){
      print(e)
      throw(e)
    } finally {
      junit allowEmptyResults: true, testResults: 'horizon/nosetests.xml'
    }
}

return this;
