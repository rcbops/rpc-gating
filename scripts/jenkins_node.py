"""
Copyright 2017 Rackspace

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""
import argparse
import os
import re

from jenkinsapi.jenkins import Jenkins


def create_node(jenkins, host_ip, name, credential_description, labels=None,
                remote_root_dir=None):
    node_dict = {
        "num_executors": 2,
        "remote_fs": remote_root_dir or "/var/lib/jenkins",
        "labels": labels or "",
        "exclusive": True,
        "host": host_ip,
        "port": 22,
        "credential_description": credential_description,
        "retention": "Always",
        "node_description": "{0}".format(host_ip),
        "jvm_options": "",
        "java_path": "",
        "prefix_start_slave_cmd": "",
        "suffix_start_slave_cmd": ""
    }
    jenkins.nodes.create_node(name, node_dict)


def delete_node(jenkins, name):
    jenkins.delete_node(nodename=name)


def delete_inactive_nodes(jenkins, instance_prefix):
    for node_id, node in jenkins.get_nodes().iteritems():
        # Ignore nodes that are coming online for the first time
        # which won't have an offline cause.
        if (re.match(instance_prefix, node_id) and not node.is_online()
            and node.poll(tree="offlineCauseReason")
                .get("offlineCauseReason")):
            print("Deleting inactive Jenkins node {}".format(node_id))
            jenkins.delete_node(node_id)


def get_jenkins_client():
    username = os.environ.get("JENKINS_USERNAME")
    password = os.environ.get("JENKINS_API_KEY")
    jenkins_url = os.environ.get(
        "JENKINS_URL", "https://rpc.jenkins.cit.rackspace.net/")
    return Jenkins(baseurl=jenkins_url, username=username, password=password)


if __name__ == "__main__":
    description = "Adds or deletes a Jenkins node via the Jenkins API"
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("action", choices=['create', 'delete'])
    parser.add_argument("--name", help="Name of slave node")
    parser.add_argument("--ip", help="IP address of node")
    parser.add_argument("--creds", help="Description of credentials to use")
    parser.add_argument("--labels",
                        help="Labels to give node separated by spaces")
    parser.add_argument("--remote-dir",
                        help="Path to use as the home directory on the node")
    args = parser.parse_args()

    j = get_jenkins_client()

    if args.action == "create":
        create_node(jenkins=j, host_ip=args.ip, name=args.name,
                    credential_description=args.creds,
                    labels=args.labels,
                    remote_root_dir=args.remote_dir)
    elif args.action == "delete":
        delete_node(jenkins=j, name=args.name)
