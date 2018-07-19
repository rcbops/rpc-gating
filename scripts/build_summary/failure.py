from abc import ABC, abstractmethod
import datetime
import itertools
import re
import uuid


class Failure(ABC):
    description = "Failure Base Class"
    failures = {}
    console_note_re = re.compile(r'\[8mha:[^\s]*\s?\[0m(.\[[0-9];[0-9]{2}m)?')
    max_detail_length = 1000

    def __init__(self, build):
        self.id = str(uuid.uuid4())
        self.matches = False
        self.detail = "No detail provided"
        self.build = build
        Failure.failures[self.id] = self

    def get_serialisation_dict(self):
        return {
            "type": type(self).__name__,
            # limit the detail field to 1000 chars, to prevent logs
            # with super long lines from causing the data file to
            # baloon
            "detail": self.detail[:Failure.max_detail_length],
            "description": self.description,
            "build": self.build.id,
            "category": self.category,
            "id": self.id
        }

    @property
    def detail(self):
        return self.__detail

    @detail.setter
    def detail(self, detail):
        self.__detail = re.sub(self.console_note_re,
                               "", detail)[:Failure.max_detail_length]

    @classmethod
    def scan_build(cls, build):
        cls.scan_logs(build)
        if (build.junit is not None):
            cls.scan_junit(build)
        if not build.failures:
            build.failures.append(UnknownFailure(build).id)

    @classmethod
    def scan_junit(cls, build):
        failed_cases = build.junit.xpath('//failedSince[text()!="0"]/..')
        for case in failed_cases:

            # don't count failures if they are skipped
            try:
                skipped = case.find('skipped')
                if skipped.text == "true":
                    continue
            except Exception:
                pass

            # extract information from the xml element
            class_name = case.find('className').text
            if class_name is None:
                class_name = ""
            test_name = case.find('testName').text
            if test_name is None:
                test_name = ""
            # try:
            #     errorDetails = case.find('errorDetails').text
            # except Exception as e:
            #     errorDetails = ""
            # errorStackTrace = case.find('errorStackTrace').text
            # try:
            #     stdout = case.find('stdout').text
            # except Exception as e:
            #     stdout = ""

            # create a failure object
            f = JunitFailure(build)
            f.detail = "{}.{}".format(class_name, test_name)
            if ("key" in test_name):
                f.category = "C4 Keys"
            elif("bootstrap" in test_name or "bootstrap" in class_name):
                f.category = "C6 Bootstrap"
            elif (".yml" in class_name):
                f.category = "C5 Local Task"
            elif("tempest" in class_name or "tempest" in test_name):
                f.category = "C8 Tempest"

            build.failures.append(f.id)

    @classmethod
    def scan_logs(cls, build):
        job_start_time = datetime.datetime.now()
        for subtype in Failure.__subclasses__():
            filter_start_time = datetime.datetime.now()
            failure = subtype(build)
            failure.scan()
            filter_end_time = datetime.datetime.now()
            if (filter_end_time - filter_start_time >
                    datetime.timedelta(seconds=1)):
                print("Slow filter: {d} on build {b}"
                      .format(d=subtype.description,
                              b=build))
            if failure.matches:
                build.failures.append(failure.id)
                Failure.failures[failure.id] = failure
        job_end_time = datetime.datetime.now()
        if job_end_time - job_start_time > datetime.timedelta(seconds=5):
            print("Slow Build: build {b}"
                  .format(d=subtype.description,
                          b=build))

    def get_previous_task(self, line, order=-1, get_line_num=False):
        previous_task_re = re.compile(
            'TASK:? \[((?P<role>.*)\|)?(?P<task>.*)\]')
        previous_play_re = re.compile(
            'PLAY \[(?P<play>.*)\]')
        task_match = None
        play_match = None
        lines = self.build.log_lines
        if order == -1:
            end = 0
        else:
            end = len(lines)
        for index in range(line, end, order):
            if not task_match:
                task_match = previous_task_re.search(lines[index])
            if task_match and get_line_num:
                return index
            play_match = previous_play_re.search(lines[index])
            if task_match and play_match:
                task_groups = task_match.groupdict()
                play_groups = play_match.groupdict()
                # If we match the last task to be executed
                # chances are the failure happened post-ansible,
                # so the last task indicator isn't that useful.
                if (task_groups['task'].strip()
                        == 'Deploy RPC HAProxy configuration files'):
                    return 'N/A'
                if 'role' in task_groups and task_groups['role']:
                    return '{play} / {role} / {task}'.format(
                        role=task_groups['role'],
                        play=play_groups['play'],
                        task=task_groups['task'])
                else:
                    return '{play} / {task}'.format(
                        play=play_groups['play'],
                        task=task_groups['task'])

        return ""

    def failure_ignored(self, fail_line):
        lines = self.build.log_lines
        next_task_line = self.get_previous_task(fail_line,
                                                order=1,
                                                get_line_num=True)

        if next_task_line == "":
            return False

        for line in lines[fail_line:next_task_line]:
            if '...ignoring' in line:
                return True
        return False

    @abstractmethod
    def scan(self):
        pass


class GitFetchFailure(Failure):
    description = "Failed to fetch from remote git repo"
    category = "C1 Remote Dependency"

    def scan(self):
        match_res = [
            re.compile(r'Failed to fetch (\s*from\s*)?([^\s]*)\.git'),
            re.compile(r'hudson\.plugins\.git')
        ]
        for line, match_re in itertools.product(self.build.log_lines,
                                                match_res):
            match = match_re.search(line)
            if match:
                self.matches = True
                self.detail = "Git Fetch Fail: {fail}".format(
                    fail=match.string)
                break


class AptFailure(Failure):
    description = "Failures relating to APT"
    category = "C1 Remote Dependency"

    def scan(self):
        match_res = [
            re.compile(
                r"Failed to fetch (\s*from\s*)?([^\s]*)( Hash Sum mismatch)?"),
            re.compile("E: (?:Unable to locate package ([a-zA-Z-_]+)"
                       "|Package '([a-zA-Z-_]+)' has "
                       "no installation candidate)")
        ]
        for line, match_re in itertools.product(self.build.log_lines,
                                                match_res):
            match = match_re.search(line)
            if match and ".git" not in line:
                self.matches = True
                self.detail = "Apt Fetch Fail: {fail}".format(
                    fail=match.string)
                break


class AptMirrorFailure(Failure):
    description = "Mirror is not in a consistent state"
    category = "C2 Mirror"

    def scan(self):
        match_str = ("WARNING: The following packages cannot be "
                     "authenticated!\n")
        try:
            i = self.build.log_lines.index(match_str)
            previous_task = self.get_previous_task(i)
            self.matches = True
            self.detail = "Apt Mirror Fail: {line} {task}".format(
                line=match_str.strip(),
                task=previous_task)
        except ValueError:
            return


class ServiceUnavailableFailure(Failure):
    description = "HTTP 503"
    category = "C7 Uncategorised"

    def scan(self):
        match_str = ('ERROR: Service Unavailable (HTTP 503)')
        for i, line in enumerate(self.build.log_lines):
            if match_str in line:
                fail_line = i
                break
        else:
            # didn't find a match
            return
        previous_task = self.get_previous_task(fail_line)
        self.matches = True
        self.detail = ('Service Unavailable 503. PrevTask: {previous_task}'
                       .format(previous_task=previous_task))


class TempestFailure(Failure):
    description = "A tempest test failed"
    category = "C8 Tempest"

    def scan(self):
        match_re = re.compile('\{0\} (?P<test>tempest[^ ]*).*\.\.\. FAILED')
        for i, line in enumerate(self.build.log_lines):
            match = match_re.search(line)
            if match:
                self.matches = True
                test = match.groupdict()['test']
                self.detail = 'Tempest Test Failed: {test}'.format(
                    test=test)


class SlaveOfflineFailure(Failure):
    description = ("Slave executing the build went offline before the "
                   "build completed")
    category = "C7 Uncategorised"

    def scan(self):
        match_re = ('Agent went offline during the build')
        pattern = re.compile(match_re)
        for i, line in enumerate(self.build.log_lines):
            if pattern.search(line):
                previous_task = self.get_previous_task(i)
                self.matches = True
                self.detail = ('Slave Died / Agent went offline'
                               ' during the build: {previous_task}'.format(
                                   previous_task=previous_task))
                break


class DpkgLock(Failure):
    description = "Multiple processes attempting to use dpkg db simultaneously"
    category = "C5 Local Task"

    def scan(self):
        match_str = 'dpkg status database is locked by another process'
        alt_match_str = 'Could not get lock /var/lib/dpkg/lock'
        for i, line in enumerate(self.build.log_lines):
            if match_str in line or alt_match_str in line:
                previous_task = self.get_previous_task(i)
                self.matches = True
                self.detail = "dpkg locked. PrevTask: {task}".format(
                              task=previous_task)


class PipFailure(Failure):
    description = "Failures relating to Python Pip"
    category = "C1 Remote Dependency"

    def scan(self):
        match_re = re.compile("Could not find a version that satisfies "
                              "the requirement ([^ ]*)")
        for i, line in enumerate(self.build.log_lines):
            match = match_re.search(line)
            if match:
                if not self.failure_ignored(i):
                    self.matches = True
                    self.detail = "Can't find pip package: {fail}".format(
                                  fail=match.group(1))
                break


class JenkinsException(Failure):
    description = "An Exception in a Jenkins Class"
    category = "C7 Uncategorised"

    def scan(self):
        match_re = re.compile("hudson\.[^ ]*Exception.*")
        excludes = [
            "script returned exit code",
            "hudson.plugins.git"
        ]
        for line in self.build.log_lines:
            match = match_re.search(line)
            exclude_match = any(e in line for e in excludes)
            if match and not exclude_match:
                self.matches = True
                self.detail = match.group()


class AnsibleSyntaxFailure(Failure):
    description = "An Ansible syntax failure"
    category = "C5 Local Task"

    def scan(self):
        match_re = re.compile("ERROR:.*is not a legal parameter in an "
                              "Ansible task or handler")
        for line in self.build.log_lines:
            match = match_re.search(line)
            if match:
                self.matches = True
                self.detail = match.group()


class AnsibleTaskFailure(Failure):
    description = "An ansible task failed"
    category = "C5 Local Task"

    def scan(self):
        match_re = re.compile('(fatal|failed):.*=>')
        lines = self.build.log_lines
        for i, line in enumerate(lines):
            match = match_re.search(line)
            if match:
                previous_task = self.get_previous_task(i)
                if not self.failure_ignored(i):
                    self.matches = True
                    self.detail = 'Task Failed: {task}'.format(
                        task=previous_task)
                    if re.search("apt|download|package|retrieve", self.detail,
                                 re.I):
                        self.category = "C1 Remote Dependency"
                    elif re.search("key", self.detail, re.I):
                        self.category = "C4 Keys"
                    elif re.search("ssh", self.detail, re.I):
                        self.category = "C3 SSH"
                    elif re.search("bootstrap", self.detail, re.I):
                        self.category = "C6 Bootstrap"


class BuildTimeoutFailure(Failure):
    description = "Build ran over the time limit"
    category = "C7 Uncategorised"

    def scan(self):
        match_re = ('Build timed out \(after [0-9]* minutes\). '
                    'Marking the build as aborted.')
        pattern = re.compile(match_re)
        for i, line in enumerate(self.build.log_lines):
            if pattern.search(line):
                previous_task = self.get_previous_task(i)
                self.matches = True
                self.detail = 'Build Timeout: {previous_task}'.format(
                    previous_task=previous_task)
                break


class SSHFailure(Failure):
    description = "SSH communication failure"
    category = "C3 SSH"

    def scan(self):
        match_strings = [
            ("SSH Error: data could not be sent to the remote host. "
             "Make sure this host can be reached over ssh"),
            "Failed to connect to the host via ssh",
            "Timeout when waiting for search string OpenSSH",
        ]
        match_res = [
            re.compile("Timeout when waiting for (.*):22")
        ]
        for line in self.build.log_lines:
            for str_ in match_strings:
                if str_ in line:
                    self.matches = True
                    self.detail = str_.strip()
                    break
            if self.matches:
                break
            for p in match_res:
                m = p.search(line)
                if m:
                    self.matches = True
                    self.detail = m.string.strip()
                    break
            if self.matches:
                break


class JunitFailure(Failure):
    description = "Junit Failure"
    category = "C7 Uncategorised"

    def scan(self):
        # not used for scanning logs so return false
        return False


class ArtifactArchiveFailure(Failure):
    description = "Failure related to storing data generated by a build"
    category = "C8 RE Infra"

    def scan(self):
        match_strings = [
            "Warning: failed to create container",
            ("This server could not verify that you are authorized to "
             "access the document you requested"),
        ]
        for line, match_string in itertools.product(self.build.log_lines,
                                                    match_strings):
                if match_string in line:
                    self.matches = True
                    self.detail = str.strip()
                    break


class UnknownFailure(Failure):
    description = "No known failures matched"
    category = "C7 Uncategorised"

    def scan(self):
        return False
