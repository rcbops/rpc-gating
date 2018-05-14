# Stdlib import
import collections
import datetime
import gzip
import re
import sys

from failure import Failure

# 3rd Party imports
# Imports with C deps
# remember to install all the apt xml stuff - not just the pip packages.
from lxml import etree


class FilterException(Exception):
    pass


class Build(object):
    """Build Object

    Represents one RPC-AIO build. Contains functionality for intepreting
    the build.xml, injected_vars and log files.
    """

    def __init__(self, build_folder, job_name, build_num):
        self.failed = False
        self.build_start = datetime.datetime.now()
        self.stdlib_path_re = re.compile(
            "/usr/lib/python[0-9]*.[0-9]*/[^ /]*\.pyc?")
        self.tree = etree.parse('{bf}/build.xml'.format(bf=build_folder))
        self.result = self.tree.find('./result').text
        # jenkins uses miliseconds not seconds
        self.timestamp = datetime.datetime.fromtimestamp(
            float(self.tree.find('startTime').text)/1000)
        self.build_folder = build_folder
        self.job_name = job_name
        self.build_num = build_num
        self.raw_branch = self.xpath_pm_pr(
            pmpath=('//hudson.model.StringParameterValue' +
                    '[name/text()="BRANCH"]/value'),
            prpath='//org.jenkinsci.plugins.ghprb.GhprbCause/targetBranch'
        )
        self.branch = self.raw_branch.replace('-', '_').replace('.', '_')
        self.series = re.sub('-.*$', '', self.raw_branch)
        self.get_parent_info()
        self.os = self.get_os()
        self.repo_url = self.xpath_pm_pr(
            pmpath='//hudson.model.StringParameterValue' +
            '[name/text()="REPO_URL"]/value',
            prpath='//org.jenkinsci.plugins.ghprb.GhprbCause/repoName'
        )
        if "internal" in self.repo_url:
            raise Exception("can't parse internal: repo urls")
        repo_dict = re.match(
            '(https?://github.(rackspace.)?com/)?(?P<org>[^/]*)/(?P<repo>[^/]*)$',
            self.repo_url).groupdict()
        self.org = repo_dict['org']
        self.repo = repo_dict['repo']
        self.failures = []
        self.stage = self.get_stage()
        if self.result != 'SUCCESS':
            self.failed = True
        try:
            self.junit = etree.parse('{bf}/junitResult.xml'.format(bf=build_folder))
        except IOError:
            # junitResult.xml won't exist in lots of cases
            self.junit = None

    def get_serialisation_dict(self):
        return {
            "job_name": self.job_name,
            "build_num": self.build_num,
            "failures": self.failures,
            "timestamp": self.timestamp,
            "repo": self.repo,
            "branch": self.branch,
            "result": self.result,
            "build_hierachy": self.build_hierachy,
            "stage": self.stage
        }

    def get_serialisation_dict_without_failure_ref(self):
        sd = self.get_serialisation_dict()
        del sd['failures']
        return sd

    def get_stage(self):
        for candidate in ['PM', 'PR']:
            if self.job_name.startswith(candidate+"_"):
                return candidate
        else:
            raise Exception("Job stage unknown: {}".format(self.job_name))

    def get_os(self):
        for candidate in ['trusty', 'xenial', 'bionic']:
            if candidate in self.job_name:
                return candidate
        else:
            return "os_unknown"

    def xpath_pm_pr(self, pmpath, prpath):
        """Get a value using different xpaths for pm and pr.

        PM = post merge, PR = pull request.
        """
        if self.get_stage() == "PM":
            return self.tree.xpath(pmpath)[0].text
        elif self.get_stage() == "PR":
            return self.tree.xpath(prpath)[0].text

    def get_branch(self):
        '//hudson.model.StringParameterValue[name/text()="BRANCH"]/value'

    def normalise_failure(self, failure_string):
        """Remove identifiers from failures

        This prevents multiple incidents of the same failure being counted as
        multiple failures
        """

        normalisers = [
            (self.uuid_re, '**UUID**'),
            (self.ip_re, '**IPv4**'),
            (self.colour_code_re, ''),
            (self.maas_tx_id_re, '**TX_ID**'),
            (self.maas_entity_uri_re, '**Entity**'),
            (self.maas_httpd_tx_id_re,
                '**HTTP_TX_ID**'),
            (self.ansible_tmp_re, 'ansible-tmp-**Removed**'),
            (self.node_name_re, '**node-name**')
        ]

        for pattern, sub in normalisers:
            failure_string = pattern.sub(sub, failure_string)

        return failure_string

    def get_parent_info(self):
        jenkins_base = "https://rpc.jenkins.cit.rackspace.net"
        self.trigger = "periodic"
        self.build_hierachy = []
        cause_elem = self.tree.xpath(
            '//causes | //causeBag/entry')[0].getchildren()[0]

        def normalise_job_name(name):
            # ensure that long names can be wrapped by inserting spaces
            return re.sub('([/=,.])', '\\1 ', name)
        while True:
            cause_dict = {}
            tag = cause_elem.tag
            if tag == 'hudson.model.Cause_-UpstreamCause':
                cause_dict['name'] = normalise_job_name(
                    cause_elem.find('./upstreamProject').text)
                cause_dict['build_num'] = \
                    cause_elem.find('./upstreamBuild').text
                cause_dict['url'] = (
                    "{jenkins}/{job}/{build}".format(
                        jenkins=jenkins_base,
                        job=cause_elem.find('./upstreamUrl').text,
                        build=cause_dict['build_num']))
                self.build_hierachy.append(cause_dict)
            elif tag == 'org.jenkinsci.plugins.ghprb.GhprbCause':
                pullID = cause_elem.find('./pullID')
                cause_dict['name'] = "PR: {title}".format(
                    title=normalise_job_name(cause_elem.find('./title').text))
                cause_dict['build_num'] = pullID.text
                cause_dict['url'] = cause_elem.find('./url').text
                self.trigger = "pr"
                self.gh_pull = pullID.text
                self.gh_target = cause_elem.find('./targetBranch').text
                self.gh_title = cause_dict['name']
                self.build_hierachy.append(cause_dict)
            elif tag == 'hudson.triggers.TimerTrigger_-TimerTriggerCause':
                self.build_hierachy.append({
                    'name': 'TimerTrigger (Periodic)',
                    'build_num': '',
                    'url': '#'
                })
            elif tag == 'hudson.model.Cause_-UserIdCause':
                user = cause_elem.find('./userId').text
                self.trigger = "user"
                self.build_hierachy.append({
                    'name': 'Manual Trigger by {user}'.format(user=user),
                    'build_num': '',
                    'url': '{jenkins}user/{user}'.format(
                        jenkins=jenkins_base,
                        user=user),
                })
            elif tag == 'com.cloudbees.jenkins.GitHubPushCause':
                user = cause_elem.find('./pushedBy').text
                self.build_hierachy.append({
                    'name': "Github Push by {user}".format(user=user),
                    'build_num': '',
                    'url': '#'
                })
            else:
                self.build_hierachy.append({
                    'name': 'Unknown Trigger: {tag}'.format(
                        tag=normalise_job_name(tag)),
                    'build_num': '',
                    'url': '#'
                })

            # Go round again if the current cause has upstream causes
            upstream_causes = cause_elem.find('./upstreamCauses')
            if upstream_causes is not None:
                cause_elem = upstream_causes.getchildren()[0]
                continue

            # Otherwise found the root cause, exit loop.
            break

        # causes are collected from the AIO job working up to the root causes
        # reverse the list to have the root cause as the first item.
        self.build_hierachy.reverse()

        # Add currrent job to causes as its the last step in the hierachy
        self.build_hierachy.append(dict(
            name=self.job_name,
            build_num=self.build_num,
            url="{jenkins}/job/{job}/{build_num}".format(
                jenkins=jenkins_base,
                job=self.job_name,
                build_num=self.build_num)))

    def read_logs(self):
        def open_log(filename):
            log_file = '{build_folder}/{filename}'.format(
                build_folder=self.build_folder,
                filename=filename)
            lines = []
            try:
                with open(log_file, 'rt') as f:
                    lines = f.readlines()
            except IOError:
                try:
                    with gzip.open(log_file+".gz", 'rt') as f:
                        lines = f.readlines()
                except IOError:
                    return []

            if isinstance(lines[0], bytes):
                templines = []
                for line in lines:
                    templines.append(line.decode('utf-8'))
                lines = templines

            try:
                post_build = lines.index(
                    '[PostBuildScript] - Execution post build scripts.\n')
                return lines[0: post_build]
            except ValueError:
                return lines

        lines = []
        lines += open_log('log')
        lines += open_log('archive/artifacts/runcmd-bash.log')
        lines += open_log('archive/artifacts/deploy.sh.log')
        return lines


    def __str__(self):
        return ("{timestamp} {result} {job_name}/{build_num}"
                " {branch}").format(
            timestamp=self.timestamp.isoformat(),
            job_name=self.job_name,
            build_num=self.build_num,
            result=self.result,
            branch=self.branch)
