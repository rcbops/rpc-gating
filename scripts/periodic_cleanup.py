#!/usr/bin/env python

# This script deletes instances whose name starts with "ra" if they are in
# error state or older than AGE_LIMIT hours.


import datetime
import dateutil.parser
from dateutil.tz import tzutc
import os

import pyrax
import jenkins_node


def get_env_vars():
    env_vars = {}

    env_vars["age_limit"] = int(os.environ.get("INSTANCE_AGE_LIMIT", 48))
    env_vars["instance_prefix"] = os.environ.get("INSTANCE_PREFIX", "ra")
    if not env_vars["instance_prefix"]:
        raise ValueError("INSTANCE_PREFIX must not be empty")

    env_vars["username"] = os.environ["PUBCLOUD_USERNAME"]
    env_vars["api_key"] = os.environ["PUBCLOUD_API_KEY"]
    env_vars["region_name"] = os.environ["REGION"]

    return env_vars


def cleanup_instances(cs_client, age_limit, instance_prefix):
    current_time = datetime.datetime.now(tzutc())
    max_age = datetime.timedelta(hours=age_limit)

    prefixed_servers = (
        server for server in cs_client.servers.list()
        if server.name.startswith(instance_prefix)
    )

    for server in prefixed_servers:
        created_time = dateutil.parser.parse(server.created)
        age = current_time - created_time
        errored = server.status == "ERROR"
        if errored or age > max_age:
            print("Deleting {name} Errored: {error} Age: {age}".format(
                name=server.name, error=errored, age=age))
            server.delete()


if __name__ == "__main__":
    args = get_env_vars()

    pyrax.set_setting("identity_type", "rackspace")
    pyrax.set_credentials(args["username"], args["api_key"])
    cs = pyrax.connect_to_cloudservers(args["region_name"], verify_ssl=True)

    cleanup_instances(
        cs_client=cs,
        age_limit=args["age_limit"],
        instance_prefix=args["instance_prefix"],
    )

    jenkins_client = jenkins_node.get_jenkins_client()
    jenkins_node.delete_inactive_nodes(
        jenkins=jenkins_client,
        instance_prefix=args["instance_prefix"],
    )
