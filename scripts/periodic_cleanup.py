#!/usr/bin/env python

# This script deletes instances whose name matches a supplied pattern if they
# are in error state or older than AGE_LIMIT hours.


from __future__ import print_function


import datetime
import dateutil.parser
from dateutil.tz import tzutc
from functools import wraps
from operator import attrgetter
import os
import re


import pyrax
import jenkins_node
from rackspace_monitoring.providers import get_driver
from rackspace_monitoring.types import Provider
import libcloud


INDENT = 0
INDENT_STR = 2 * " "


def _indp(message, **kwargs):
    """ Indent Print """
    print("{indent}{message}".format(
            indent=INDENT_STR * INDENT,
            message=message
        ),
        **kwargs
    )


def log(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        global INDENT
        start = datetime.datetime.now()
        _indp("Starting: {function}".format(
            function=f.func_name)
        )
        INDENT += 1
        result = f(*args, **kwargs)
        end = datetime.datetime.now()
        delta = end - start
        INDENT -= 1
        _indp("Completed: {function} ({delta}s)".format(
                function=f.func_name,
                delta=delta.seconds))
        return result
    return wrapper


class Cleanup:
    def __init__(self):
        self.read_env_vars()
        self.maas = self.init_rackspace_monitoring()
        self.jenkins_client = jenkins_node.get_jenkins_client()
        self.servers_by_region = dict()

    @log
    def read_env_vars(self):
        self.age_limit = int(os.environ.get("INSTANCE_AGE_LIMIT", 48))
        self.instance_prefix = os.environ.get("INSTANCE_PREFIX")
        if not self.instance_prefix:
            raise ValueError("INSTANCE_PREFIX must not be empty")

        self.username = os.environ["PUBCLOUD_USERNAME"]
        self.api_key = os.environ["PUBCLOUD_API_KEY"]
        self.regions = os.environ["REGIONS"].split(' ')

    @log
    def init_rackspace_monitoring(self):
        self.entities = []
        self.agents = []
        self.agent_tokens = []
        Cls = get_driver(Provider.RACKSPACE)
        return Cls(self.username, self.api_key)

    @log
    def init_pyrax(self, region):
        self.region = region
        pyrax.set_setting("identity_type", "rackspace")
        pyrax.set_credentials(self.username, self.api_key)
        self.cs = pyrax.connect_to_cloudservers(
            region, verify_ssl=True)

    @log
    def cache_servers(self):
        self.servers = self.cs.servers.list()
        self.servers_by_region[self.region] = self.servers
        self.prefixed_servers = (
            server for server in self.servers
            if re.match(self.instance_prefix, server.name)
        )

    def get_servers_from_all_regions(self):
        # flatten servers by region into list
        return (s for sl in self.servers_by_region.values() for s in sl)

    @log
    def cleanup_instances(self):
        """ Delete instances if they are in an error state or are over the
        age defined in INSTANCE_AGE_LIMIT.

        Instances that don't match INSTANCE_PREFIX are ignored.
        """
        current_time = datetime.datetime.now(tzutc())
        max_age = datetime.timedelta(hours=self.age_limit)

        for server in self.prefixed_servers:
            created_time = dateutil.parser.parse(server.created)
            age = current_time - created_time
            errored = server.status == "ERROR"
            if errored or age > max_age:
                _indp("Deleting {name} Errored: {error} Age: {age}".format(
                    name=server.name, error=errored, age=age))
                server.delete()

    @log
    def cache_maas_objects(self):
        try:
            for mtype in ['entities', 'agents', 'agent_tokens']:
                if not getattr(self, mtype):
                    objs = getattr(self.maas, "list_{}".format(mtype))()
                    # force population of libcloud.common.types.LazyList
                    # instances this is done to keep network IO within this try
                    # block
                    objs._load_all()
                    setattr(self, mtype, objs)
            self.server_names = (
                s.name for s in self.get_servers_from_all_regions()
            )
        except libcloud.common.exceptions.BaseHTTPError as e:
            print(
                "Failed to query MaaS for entities"
                " and agents: {}".format(e.message)
            )
            raise e

    def _hostname_from_label(self, label):
        return label.split('.')[-1]

    @log
    def cleanup_maas_entities(self):
        """ Remove maas entities that relate to deleted instances """

        self.cache_maas_objects()

        # An entity will be kept if an associated agent has connected within
        # this period
        agent_connection_cutoff = \
            datetime.datetime.now() - datetime.timedelta(hours=self.age_limit)

        for entity in self.entities:
            # multi node aio entities are vm.hostname eg infra1.omnantp-52-66d1
            # standard aio entities don't include a vm eg: ramcp-77-5dcd
            hostname = self._hostname_from_label(entity.label)

            _indp("Entity: {}".format(entity.label), end=" ")

            # --- Check various conditions for deletion,
            # continue if any are not met ---

            if not re.match(self.instance_prefix, hostname):
                print()
                continue
            print("[prefix match]", end=" ")

            # entities with URIs are tied to cloud instances and cannot be
            # manually deleted
            if entity.uri:
                print()
                continue
            print("[no uri]", end=" ")

            if hostname in self.server_names:
                print()
                continue
            print("[not related to active instance]", end=" ")

            # sort agents with most recently connected agent last
            agents = sorted(
                (a for a in self.agents if a.id == entity.agent_id),
                key=attrgetter('last_connected')
            )
            if agents:
                print("[has agent]", end=" ")

                # find last connection time for most recently connected agent
                last_connected = datetime.datetime.fromtimestamp(
                    agents[-1].last_connected / 1000
                )
                if last_connected > agent_connection_cutoff:
                    print()
                    continue
                else:
                    print("[agent not connected within cutoff period]")
            else:
                print("[no agent]")

            # --- At this point the entity has met all conditions
            # for deletion ---
            try:
                print("Deleting entity: {e}...".format(e=entity.label),
                      end="")
                self.maas.delete_entity(entity)
                print("[ok]")
            except libcloud.common.exceptions.BaseHTTPError as e:
                print(
                    "Failed to delete entity: {ent}, error: {err}".format(
                        ent=entity,
                        err=e.message
                    )
                )

    @log
    def cleanup_maas_agent_tokens(self):
        self.cache_maas_objects()
        for agent_token in self.agent_tokens:
            label = agent_token.label
            hostname = self._hostname_from_label(label)
            _indp("Agent token: {}".format(label), end=" ")

            if re.match(self.instance_prefix, hostname):
                print ("[prefix match]", end=" ")
            else:
                print()
                continue

            if hostname not in self.server_names:
                print ("[not related to active instance]", end=" ")
            else:
                print()
                continue

            try:
                print("Deleting agent token: {} ...".format(label), end="")
                self.maas.delete_agent_token(agent_token)
                print("[ok]")
            except libcloud.common.exceptions.BaseHTTPError as e:
                _indp(
                    "Failed to delete agent_token: {at},"
                    " error: {err}".format(
                        at=agent_token,
                        err=e.message
                    )
                )

    @log
    def multi_region_cloudservers_cleanup(self):
        for region in self.regions:
            _indp("Current Region: {r}".format(r=region))
            self.init_pyrax(region=region)
            self.cache_servers()
            self.cleanup_instances()

    @log
    def cleanup_jenkins_nodes(self):
        """ Remove offline jenkins nodes that match instance prefix """
        try:
            jenkins_node.delete_inactive_nodes(
                jenkins=self.jenkins_client,
                instance_prefix=self.instance_prefix,
            )
        except Exception as e:
            print("Failure encoutered while cleaning up "
                  "jenkins nodes: {}".format(e.message))


@log
def periodic_cleanup():
    c = Cleanup()
    c.multi_region_cloudservers_cleanup()
    c.cleanup_maas_entities()
    c.cleanup_maas_agent_tokens()
    c.cleanup_jenkins_nodes()


if __name__ == "__main__":
    periodic_cleanup()
